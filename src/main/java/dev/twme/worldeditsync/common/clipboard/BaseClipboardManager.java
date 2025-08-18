package dev.twme.worldeditsync.common.clipboard;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.hash.Hashing;

import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.transfer.TransferSession;

public abstract class BaseClipboardManager {
    protected final Map<UUID, ClipboardData> clipboardStorage;
    protected final Map<String, TransferSession> transferSessions;
    protected final Map<UUID, Boolean> playerTransferStatus;

    public BaseClipboardManager() {
        this.clipboardStorage = new ConcurrentHashMap<>();
        this.transferSessions = new ConcurrentHashMap<>();
        this.playerTransferStatus = new ConcurrentHashMap<>();
    }

    public void storeClipboard(UUID playerUuid, byte[] data, String hash) {
        clipboardStorage.put(playerUuid, new ClipboardData(data, hash));
    }

    public ClipboardData getClipboard(UUID playerUuid) {
        return clipboardStorage.get(playerUuid);
    }

    public void createTransferSession(String sessionId, UUID playerUuid,
                                      int totalChunks, int chunkSize) {
        transferSessions.put(sessionId,
                new TransferSession(playerUuid, sessionId, totalChunks, chunkSize));
    }

    public void addChunk(String sessionId, int index, byte[] data) {
        TransferSession session = transferSessions.get(sessionId);
        if (session != null) {
            session.addChunk(index, data);

            if (session.isComplete()) {
                byte[] fullData = session.assembleData();
                UUID playerUuid = session.getPlayerUuid();
                String hash = calculateHash(fullData);

                if (fullData == null) {
                    handleSessionAssemblyFailure(sessionId);
                    return;
                }
                
                handleSessionComplete(sessionId, playerUuid, fullData, hash);
                
                // 清理會話
                transferSessions.remove(sessionId);
            }
        } else {
            handleSessionNotFound(sessionId);
        }
    }

    public void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        transferSessions.entrySet().removeIf(entry ->
                now - entry.getValue().getLastUpdateTime() > Constants.SESSION_TIMEOUT);
    }

    public void cleanup() {
        clipboardStorage.clear();
        transferSessions.clear();
    }

    protected String calculateHash(byte[] data) {
        return Hashing.sha256()
                .hashBytes(data)
                .toString();
    }

    public boolean isPlayerTransferring(UUID playerUuid) {
        return playerTransferStatus.getOrDefault(playerUuid, false);
    }

    public void setPlayerTransferring(UUID playerUuid, boolean transferring) {
        playerTransferStatus.put(playerUuid, transferring);
    }

    // 抽象方法，由平台特定實現提供
    protected abstract void handleSessionAssemblyFailure(String sessionId);
    protected abstract void handleSessionComplete(String sessionId, UUID playerUuid, byte[] fullData, String hash);
    protected abstract void handleSessionNotFound(String sessionId);
    protected abstract void handleBroadcastClipboardUpdate(UUID playerUuid, byte[] data);

    public static class ClipboardData {
        private final byte[] data;
        private final String hash;
        private final long timestamp;

        public ClipboardData(byte[] data, String hash) {
            this.data = data;
            this.hash = hash;
            this.timestamp = System.currentTimeMillis();
        }

        public byte[] getData() {
            return data;
        }

        public String getHash() {
            return hash;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }
}