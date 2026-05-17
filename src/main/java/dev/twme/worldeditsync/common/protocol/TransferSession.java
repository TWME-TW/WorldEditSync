package dev.twme.worldeditsync.common.protocol;

import java.util.concurrent.ConcurrentHashMap;

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
    private final ConcurrentHashMap<Integer, byte[]> chunks = new ConcurrentHashMap<>();

    public TransferSession(String sessionId, int totalChunks, int totalBytes, String expectedHash) {
        this.sessionId = sessionId;
        this.totalChunks = totalChunks;
        this.totalBytes = totalBytes;
        this.expectedHash = expectedHash;
        this.createdAt = System.currentTimeMillis();
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

    public void addChunk(int index, byte[] data) {
        chunks.put(index, data);
    }

    public int getReceivedChunks() {
        return chunks.size();
    }

    public boolean isComplete() {
        return chunks.size() >= totalChunks;
    }

    public boolean isExpired(long timeoutMs) {
        return System.currentTimeMillis() - createdAt > timeoutMs;
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
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }
}
