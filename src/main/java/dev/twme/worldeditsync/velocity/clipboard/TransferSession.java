package dev.twme.worldeditsync.velocity.clipboard;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TransferSession {
    private final UUID playerUuid;
    private final int totalChunks;
    private final int chunkSize;
    private final Map<Integer, byte[]> chunks;
    private long lastUpdateTime;

    public TransferSession(UUID playerUuid, int totalChunks, int chunkSize) {
        this.playerUuid = playerUuid;
        this.totalChunks = totalChunks;
        this.chunkSize = chunkSize;
        this.chunks = new ConcurrentHashMap<>();
        this.lastUpdateTime = System.currentTimeMillis();
    }

    public void addChunk(int index, byte[] data) {
        chunks.put(index, data);
        lastUpdateTime = System.currentTimeMillis();
    }

    public boolean isComplete() {
        return chunks.size() == totalChunks;
    }

    public byte[] assembleData() {
        if (!isComplete()) return null;

        byte[] result = new byte[totalChunks * chunkSize];
        for (int i = 0; i < totalChunks; i++) {
            byte[] chunk = chunks.get(i);
            if (chunk != null) {
                System.arraycopy(chunk, 0, result, i * chunkSize, chunk.length);
            }
        }
        return result;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public long getLastUpdateTime() {
        return lastUpdateTime;
    }
}