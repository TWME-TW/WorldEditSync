package dev.twme.worldeditsync.common.protocol;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages the reception of chunked data transfers.
 * Thread-safe: chunks can be added from any thread.
 */
public class TransferSession {

    private final String sessionId;
    private final int totalChunks;
    private final int totalBytes;
    private final String expectedHash;
    private final long createdAt;
    private final AtomicLong lastActivityAt;
    private final ConcurrentHashMap<Integer, byte[]> chunks = new ConcurrentHashMap<>();
    private final AtomicInteger receivedBytes = new AtomicInteger();
    private final AtomicBoolean completionClaimed = new AtomicBoolean();

    public TransferSession(String sessionId, int totalChunks, int totalBytes, String expectedHash) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (totalChunks <= 0) {
            throw new IllegalArgumentException("totalChunks must be positive");
        }
        if (totalBytes <= 0) {
            throw new IllegalArgumentException("totalBytes must be positive");
        }
        if (expectedHash == null || expectedHash.isBlank()) {
            throw new IllegalArgumentException("expectedHash must not be blank");
        }
        this.sessionId = sessionId;
        this.totalChunks = totalChunks;
        this.totalBytes = totalBytes;
        this.expectedHash = expectedHash;
        this.createdAt = System.currentTimeMillis();
        this.lastActivityAt = new AtomicLong(createdAt);
    }

    public static boolean isValidLayout(int totalBytes, int totalChunks, int chunkSize) {
        if (totalBytes <= 0 || totalChunks <= 0 || chunkSize <= 0) {
            return false;
        }
        int expectedChunks = (int) Math.ceil((double) totalBytes / chunkSize);
        return totalChunks == expectedChunks;
    }

    public String getSessionId() {
        return sessionId;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public int getTotalBytes() {
        return totalBytes;
    }

    public String getExpectedHash() {
        return expectedHash;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public boolean addChunk(int index, byte[] data) {
        if (index < 0 || index >= totalChunks) {
            throw new IllegalArgumentException("Chunk index out of range: " + index);
        }
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Chunk data must not be empty");
        }

        if (chunks.putIfAbsent(index, data) != null) {
            return false;
        }

        int newTotal = receivedBytes.addAndGet(data.length);
        if (newTotal > totalBytes) {
            chunks.remove(index, data);
            receivedBytes.addAndGet(-data.length);
            throw new IllegalArgumentException("Received data exceeds declared transfer size");
        }
        lastActivityAt.set(System.currentTimeMillis());
        return true;
    }

    public int getReceivedChunks() {
        return chunks.size();
    }

    public int getReceivedBytes() {
        return receivedBytes.get();
    }

    public boolean isComplete() {
        return chunks.size() == totalChunks && receivedBytes.get() == totalBytes;
    }

    public boolean tryClaimCompletion() {
        return isComplete() && completionClaimed.compareAndSet(false, true);
    }

    public boolean isExpired(long timeoutMs) {
        return System.currentTimeMillis() - lastActivityAt.get() >= timeoutMs;
    }

    public double getProgress() {
        if (totalChunks == 0) return 1.0;
        return (double) chunks.size() / totalChunks;
    }

    /**
     * Assemble all chunks into a single byte array.
     * Must only be called when {@link #isComplete()} returns true.
     */
    public byte[] assemble() {
        byte[] result = new byte[totalBytes];
        int offset = 0;
        for (int i = 0; i < totalChunks; i++) {
            byte[] chunk = chunks.get(i);
            if (chunk == null) {
                throw new IllegalStateException("Missing chunk " + i + " in session " + sessionId);
            }
            if (offset + chunk.length > result.length) {
                throw new IllegalStateException("Chunk data exceeds declared size in session " + sessionId);
            }
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        if (offset != totalBytes) {
            throw new IllegalStateException("Assembled size does not match declared size in session " + sessionId);
        }
        return result;
    }
}
