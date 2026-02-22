package dev.twme.worldeditsync.paper.clipboard;

import com.google.common.hash.Hashing;
import dev.twme.worldeditsync.common.transfer.SyncState;
import dev.twme.worldeditsync.common.transfer.TransferSession;
import dev.twme.worldeditsync.paper.WorldEditSyncPaper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paper 端的剪貼簿管理器。
 * 負責：
 * - 管理每個玩家的同步狀態 (SyncState)
 * - 快取本地剪貼簿的序列化資料與 hash
 * - 管理下載會話 (TransferSession)
 */
public class ClipboardManager {
    private final WorldEditSyncPaper plugin;

    /** 每個玩家的同步狀態 */
    private final Map<UUID, SyncState> playerStates = new ConcurrentHashMap<>();

    /** 快取的本地剪貼簿 SHA-256 hash */
    private final Map<UUID, String> localHashes = new ConcurrentHashMap<>();

    /** 快取的本地剪貼簿序列化資料 */
    private final Map<UUID, byte[]> localData = new ConcurrentHashMap<>();

    /** 活躍的下載會話 (sessionId → TransferSession) */
    private final Map<String, TransferSession> downloadSessions = new ConcurrentHashMap<>();

    public ClipboardManager(WorldEditSyncPaper plugin) {
        this.plugin = plugin;
    }

    // ==================== 狀態管理 ====================

    /**
     * 取得玩家的同步狀態。新玩家預設為 INITIALIZING。
     */
    public SyncState getState(UUID playerUuid) {
        return playerStates.getOrDefault(playerUuid, SyncState.INITIALIZING);
    }

    /**
     * 設定玩家的同步狀態。
     */
    public void setState(UUID playerUuid, SyncState state) {
        playerStates.put(playerUuid, state);
    }

    /**
     * 檢查玩家是否處於閒置狀態（可以偵測變更）。
     */
    public boolean isIdle(UUID playerUuid) {
        return getState(playerUuid) == SyncState.IDLE;
    }

    // ==================== 本地快取 ====================

    /**
     * 更新本地剪貼簿快取。
     */
    public void updateLocalCache(UUID playerUuid, byte[] data, String hash) {
        localData.put(playerUuid, data);
        localHashes.put(playerUuid, hash);
    }

    /**
     * 取得本地快取的剪貼簿 hash。
     */
    public String getLocalHash(UUID playerUuid) {
        return localHashes.getOrDefault(playerUuid, "");
    }

    /**
     * 取得本地快取的剪貼簿序列化資料。
     */
    public byte[] getLocalData(UUID playerUuid) {
        return localData.get(playerUuid);
    }

    /**
     * 使用 SHA-256 計算資料的雜湊值。
     */
    public static String computeHash(byte[] data) {
        if (data == null || data.length == 0) return "";
        return Hashing.sha256().hashBytes(data).toString();
    }

    // ==================== 下載會話 ====================

    /**
     * 建立新的下載會話。
     */
    public void createDownloadSession(String sessionId, UUID playerUuid, int totalChunks, int totalBytes, String hash) {
        downloadSessions.put(sessionId, new TransferSession(playerUuid, sessionId, totalChunks, totalBytes, hash));
    }

    /**
     * 取得指定的下載會話。
     */
    public TransferSession getDownloadSession(String sessionId) {
        return downloadSessions.get(sessionId);
    }

    /**
     * 移除下載會話。
     */
    public void removeDownloadSession(String sessionId) {
        downloadSessions.remove(sessionId);
    }

    // ==================== 清理 ====================

    /**
     * 移除指定玩家的所有資料。
     */
    public void removePlayer(UUID playerUuid) {
        playerStates.remove(playerUuid);
        localHashes.remove(playerUuid);
        localData.remove(playerUuid);
        downloadSessions.entrySet().removeIf(e -> e.getValue().getPlayerUuid().equals(playerUuid));
    }

    /**
     * 清理所有資料。
     */
    public void cleanup() {
        playerStates.clear();
        localHashes.clear();
        localData.clear();
        downloadSessions.clear();
    }
}
