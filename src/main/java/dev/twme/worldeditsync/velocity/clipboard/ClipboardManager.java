package dev.twme.worldeditsync.velocity.clipboard;

import com.google.common.hash.Hashing;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.clipboard.ClipboardData;
import dev.twme.worldeditsync.common.transfer.TransferSession;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Velocity 端的剪貼簿管理器。
 * 負責：
 * - 儲存每個玩家的剪貼簿資料（序列化的 byte[] + hash）
 * - 管理上傳會話（接收來自 Paper 的分塊上傳）
 * - 管理傳輸鎖（防止並發操作）
 */
public class ClipboardManager {

    /** 玩家的剪貼簿儲存 (playerUUID → ClipboardData) */
    private final Map<UUID, ClipboardData> clipboardStorage = new ConcurrentHashMap<>();

    /** 活躍的上傳會話 (sessionId → TransferSession) */
    private final Map<String, TransferSession> uploadSessions = new ConcurrentHashMap<>();

    /** 玩家傳輸鎖 */
    private final Map<UUID, Boolean> playerTransferring = new ConcurrentHashMap<>();

    // ==================== 剪貼簿儲存 ====================

    public void storeClipboard(UUID playerUuid, byte[] data, String hash) {
        clipboardStorage.put(playerUuid, new ClipboardData(data, hash));
    }

    public ClipboardData getClipboard(UUID playerUuid) {
        return clipboardStorage.get(playerUuid);
    }

    // ==================== 上傳會話 ====================

    public void createUploadSession(String sessionId, UUID playerUuid, int totalChunks, int totalBytes, String hash) {
        uploadSessions.put(sessionId, new TransferSession(playerUuid, sessionId, totalChunks, totalBytes, hash));
    }

    public TransferSession getUploadSession(String sessionId) {
        return uploadSessions.get(sessionId);
    }

    public void removeUploadSession(String sessionId) {
        uploadSessions.remove(sessionId);
    }

    // ==================== 傳輸鎖 ====================

    public boolean isPlayerTransferring(UUID playerUuid) {
        return playerTransferring.getOrDefault(playerUuid, false);
    }

    public void setPlayerTransferring(UUID playerUuid, boolean transferring) {
        playerTransferring.put(playerUuid, transferring);
    }

    // ==================== 維護 ====================

    /**
     * 清理過期的上傳會話。
     */
    public void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        uploadSessions.entrySet().removeIf(e ->
                now - e.getValue().getLastUpdateTime() > Constants.SESSION_TIMEOUT_MS);
    }

    /**
     * 清理所有資料。
     */
    public void cleanup() {
        clipboardStorage.clear();
        uploadSessions.clear();
        playerTransferring.clear();
    }

    /**
     * 使用 SHA-256 計算資料的雜湊值。
     */
    public static String computeHash(byte[] data) {
        if (data == null || data.length == 0) return "";
        return Hashing.sha256().hashBytes(data).toString();
    }
}
