package dev.twme.worldeditsync.common.transfer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理分塊傳輸會話。
 * 支援上傳和下載兩個方向，正確處理可變大小的最後一個 chunk。
 */
public class TransferSession {
    private final UUID playerUuid;
    private final String sessionId;
    private final int totalChunks;
    private final int totalBytes;
    private final String expectedHash;
    private final Map<Integer, byte[]> chunks;
    private final long createdAt;
    private volatile long lastUpdateTime;

    public TransferSession(UUID playerUuid, String sessionId, int totalChunks, int totalBytes, String expectedHash) {
        this.playerUuid = playerUuid;
        this.sessionId = sessionId;
        this.totalChunks = totalChunks;
        this.totalBytes = totalBytes;
        this.expectedHash = expectedHash;
        this.chunks = new ConcurrentHashMap<>();
        this.createdAt = System.currentTimeMillis();
        this.lastUpdateTime = this.createdAt;
    }

    /**
     * 添加一個資料 chunk。
     * @param index 從 0 開始的 chunk 索引
     * @param data chunk 資料
     */
    public void addChunk(int index, byte[] data) {
        chunks.put(index, data);
        lastUpdateTime = System.currentTimeMillis();
    }

    /**
     * 檢查是否所有 chunk 都已接收完成。
     */
    public boolean isComplete() {
        return chunks.size() >= totalChunks;
    }

    /**
     * 組裝所有 chunk 為完整資料。
     * 使用 totalBytes 精確分配陣列大小，正確處理最後一個較小的 chunk。
     * @return 完整資料，或 null 如果尚未完成或有缺失
     */
    public byte[] assembleData() {
        if (!isComplete()) return null;

        byte[] result = new byte[totalBytes];
        int offset = 0;

        for (int i = 0; i < totalChunks; i++) {
            byte[] chunk = chunks.get(i);
            if (chunk == null) return null;

            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }

        return result;
    }

    /**
     * 取得目前傳輸進度 (0.0 ~ 1.0)。
     */
    public float getProgress() {
        if (totalChunks == 0) return 1.0f;
        return (float) chunks.size() / totalChunks;
    }

    // === Getters ===

    public UUID getPlayerUuid() {
        return playerUuid;
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

    public int getReceivedChunks() {
        return chunks.size();
    }

    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    public long getCreatedAt() {
        return createdAt;
    }
}