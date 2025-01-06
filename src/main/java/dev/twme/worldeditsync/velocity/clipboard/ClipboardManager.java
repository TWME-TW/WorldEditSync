package dev.twme.worldeditsync.velocity.clipboard;

import com.google.common.io.ByteArrayDataOutput;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.transfer.TransferSession;
import dev.twme.worldeditsync.velocity.WorldEditSyncVelocity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ClipboardManager {
    private final WorldEditSyncVelocity plugin;
    private final Map<UUID, ClipboardData> clipboardStorage;
    private final Map<String, TransferSession> transferSessions;
    private final Map<UUID, Boolean> playerTransferStatus;

    public ClipboardManager(WorldEditSyncVelocity plugin) {
        this.plugin = plugin;
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
                    plugin.getLogger().warn("Failed to assemble data: {}", sessionId);
                    return;
                }
                plugin.getLogger().info("Finished receiving clipboard data: {}", sessionId);
                // 儲存剪貼簿
                storeClipboard(playerUuid, fullData, hash);

                // 儲存並廣播到其他服務器
                // broadcastClipboardUpdate(playerUuid, fullData);

                // 清理會話
                transferSessions.remove(sessionId);
            }
        } else {
            plugin.getLogger().warn("Session not found: {}", sessionId);
        }
    }

    public void broadcastClipboardUpdate(UUID playerUuid, byte[] data) {
        Player targetPlayer = plugin.getServer().getPlayer(playerUuid).orElse(null);
        if (targetPlayer == null) return;

        for (RegisteredServer server : plugin.getServer().getAllServers()) {
            if (!targetPlayer.getCurrentServer().isPresent()) {
                // 玩家不在線上
                return;
            }
            if (!server.equals(targetPlayer.getCurrentServer().get().getServer())) {
                // 發送到其他服務器
                server.sendPluginMessage(
                        MinecraftChannelIdentifier.from(Constants.CHANNEL),
                        createUpdateMessage(playerUuid, data)
                );
            }
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


    private String calculateHash(byte[] data) {
        return com.google.common.hash.Hashing.sha256()
                .hashBytes(data)
                .toString();
    }

    private byte[] createUpdateMessage(UUID playerUuid, byte[] data) {
        ByteArrayDataOutput out =
                com.google.common.io.ByteStreams.newDataOutput();
        out.writeUTF("ClipboardUpdate");
        out.writeUTF(playerUuid.toString());
        out.write(data);
        return out.toByteArray();
    }

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

    public boolean isPlayerTransferring(UUID playerUuid) {
        return playerTransferStatus.getOrDefault(playerUuid, false);
    }

    public void setPlayerTransferring(UUID playerUuid, boolean transferring) {
        playerTransferStatus.put(playerUuid, transferring);
    }
}