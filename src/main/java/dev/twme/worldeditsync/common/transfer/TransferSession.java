package dev.twme.worldeditsync.common.transfer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TransferSession {
    private final UUID playerUuid;
    private final String sessionId;
    private final int totalChunks;
    private final int chunkSize;
    private final Map<Integer, byte[]> chunks;
    private long lastUpdateTime;

    public TransferSession(UUID playerUuid, String sessionId, int totalChunks, int chunkSize) {
        this.playerUuid = playerUuid;
        this.sessionId = sessionId;
        this.totalChunks = totalChunks;
        this.chunkSize = chunkSize;
        this.chunks = new ConcurrentHashMap<>();
        this.lastUpdateTime = System.currentTimeMillis();
    }

    public void addChunk(int index, byte[] data) {
        chunks.put(index, data);
        lastUpdateTime = System.currentTimeMillis();
    }

    public byte[] getChunk(int index) {
        return chunks.get(index);
    }

    public boolean isComplete() {
        return chunks.size() == totalChunks;
    }

    public byte[] assembleData() {
        if (!isComplete()) {
            return null;
        }

        byte[] result = new byte[totalChunks * chunkSize];
        for (int i = 0; i < totalChunks; i++) {
            byte[] chunk = getChunk(i + 1);
            if (chunk != null) {
                System.arraycopy(chunk, 0, result, i * chunkSize, chunk.length);
            }
        }
        return result;
    }

    // Getters
    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getSessionId() {
        return sessionId;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public int getChunkCount() {
        return chunks.size();
    }

    public long getLastUpdateTime() {
        return lastUpdateTime;
    }
}