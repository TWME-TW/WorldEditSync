package dev.twme.worldeditsync.common.protocol;

import java.util.concurrent.atomic.AtomicBoolean;

import dev.twme.worldeditsync.common.Constants;

/**
 * Manages the reception of chunked data transfers.
 * Thread-safe: chunks can be added from any thread.
 */
public class TransferSession {

    private final String sessionId;
    private final int totalChunks;
    private final int totalBytes;
    private final int chunkSize;
    private final String expectedHash;
    private final long createdAt;
    private volatile long lastActivityAt;
    private final boolean[] received;
    private byte[] data;
    private int receivedChunks;
    private int receivedBytes;
    private boolean released;
    private final AtomicBoolean completionClaimed = new AtomicBoolean();

    public TransferSession(String sessionId, int totalChunks, int totalBytes,
                           int chunkSize, String expectedHash) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (totalChunks <= 0) {
            throw new IllegalArgumentException("totalChunks must be positive");
        }
        if (totalChunks > Constants.MAX_TRANSFER_CHUNKS) {
            throw new IllegalArgumentException("totalChunks exceeds transfer limit");
        }
        if (totalBytes <= 0) {
            throw new IllegalArgumentException("totalBytes must be positive");
        }
        if (expectedHash == null || expectedHash.isBlank()) {
            throw new IllegalArgumentException("expectedHash must not be blank");
        }
        if (!isValidLayout(totalBytes, totalChunks, chunkSize)) {
            throw new IllegalArgumentException("Invalid transfer layout");
        }
        this.sessionId = sessionId;
        this.totalChunks = totalChunks;
        this.totalBytes = totalBytes;
        this.chunkSize = chunkSize;
        this.expectedHash = expectedHash;
        this.createdAt = System.currentTimeMillis();
        this.lastActivityAt = createdAt;
        this.received = new boolean[totalChunks];
    }

    public static boolean isValidLayout(int totalBytes, int totalChunks, int chunkSize) {
        if (totalBytes <= 0 || totalChunks <= 0 || chunkSize <= 0) {
            return false;
        }
        long expectedChunks = ((long) totalBytes + chunkSize - 1L) / chunkSize;
        return totalChunks <= Constants.MAX_TRANSFER_CHUNKS
                && totalChunks == expectedChunks;
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

    public synchronized boolean addChunk(int index, byte[] chunk) {
        if (released) {
            throw new IllegalStateException("Transfer session has been released");
        }
        if (index < 0 || index >= totalChunks) {
            throw new IllegalArgumentException("Chunk index out of range: " + index);
        }
        if (chunk == null || chunk.length == 0) {
            throw new IllegalArgumentException("Chunk data must not be empty");
        }
        int offset = Math.toIntExact((long) index * chunkSize);
        int expectedLength = Math.min(chunkSize, totalBytes - offset);
        if (chunk.length != expectedLength) {
            throw new IllegalArgumentException("Chunk length does not match transfer layout");
        }
        if (received[index]) {
            return false;
        }
        if (data == null) {
            data = new byte[totalBytes];
        }
        System.arraycopy(chunk, 0, data, offset, chunk.length);
        received[index] = true;
        receivedChunks++;
        receivedBytes += chunk.length;
        lastActivityAt = System.currentTimeMillis();
        return true;
    }

    public synchronized int getReceivedChunks() {
        return receivedChunks;
    }

    public synchronized int getReceivedBytes() {
        return receivedBytes;
    }

    public synchronized boolean isComplete() {
        return !released && receivedChunks == totalChunks && receivedBytes == totalBytes;
    }

    public boolean tryClaimCompletion() {
        return isComplete() && completionClaimed.compareAndSet(false, true);
    }

    public boolean isExpired(long timeoutMs) {
        long now = System.currentTimeMillis();
        return now < lastActivityAt || now - lastActivityAt >= timeoutMs;
    }

    public synchronized double getProgress() {
        if (totalChunks == 0) return 1.0;
        return (double) receivedChunks / totalChunks;
    }

    /**
     * Assemble all chunks into a single byte array.
     * Must only be called when {@link #isComplete()} returns true.
     */
    public synchronized byte[] assemble() {
        if (!isComplete() || data == null) {
            throw new IllegalStateException("Transfer session is incomplete: " + sessionId);
        }
        return data;
    }

    /** Releases a failed or abandoned transfer's backing buffer. */
    public synchronized void release() {
        released = true;
        data = null;
        receivedChunks = 0;
        receivedBytes = 0;
    }
}
