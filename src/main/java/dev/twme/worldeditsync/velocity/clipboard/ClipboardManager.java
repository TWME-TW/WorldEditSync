package dev.twme.worldeditsync.velocity.clipboard;

import com.google.common.hash.Hashing;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.velocity.WorldEditSyncVelocity;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ClipboardManager {
    private final WorldEditSyncVelocity plugin;
    private final Map<UUID, ClipboardData> clipboardStorage;
    private final Map<String, TransferSession> transferSessions;

    public ClipboardManager(WorldEditSyncVelocity plugin) {
        this.plugin = plugin;
        this.clipboardStorage = new ConcurrentHashMap<>();
        this.transferSessions = new ConcurrentHashMap<>();
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
                new TransferSession(playerUuid, totalChunks, chunkSize));
    }

    public void addChunk(String sessionId, int index, byte[] data) {
        TransferSession session = transferSessions.get(sessionId);
        if (session != null) {
            session.addChunk(index, data);

            if (session.isComplete()) {
                byte[] fullData = session.assembleData();
                UUID playerUuid = session.getPlayerUuid();
                String hash = calculateHash(fullData);

                // 儲存並廣播到其他服務器
                storeClipboard(playerUuid, fullData, hash);
                broadcastClipboardUpdate(playerUuid, fullData);

                // 清理會話
                transferSessions.remove(sessionId);
            }
        }
    }

    public void broadcastClipboardUpdate(UUID playerUuid, byte[] data) {
        Player targetPlayer = plugin.getServer().getPlayer(playerUuid).orElse(null);
        if (targetPlayer == null) return;

        for (RegisteredServer server : plugin.getServer().getAllServers()) {
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

    /**
     * 計算剪貼簿的雜湊值，考慮實際內容而不僅僅是序列化數據
     */
    public String calculateClipboardHash(Clipboard clipboard) {
        if (clipboard == null) return "";

        try {
            StringBuilder contentBuilder = new StringBuilder();

            // 加入區域大小信息
            com.sk89q.worldedit.regions.Region region = clipboard.getRegion();
            contentBuilder.append(region.getWidth())
                    .append(":")
                    .append(region.getHeight())
                    .append(":")
                    .append(region.getLength())
                    .append(":");

            // 加入原點信息
            com.sk89q.worldedit.math.BlockVector3 origin = clipboard.getOrigin();
            contentBuilder.append(origin.x())
                    .append(":")
                    .append(origin.y())
                    .append(":")
                    .append(origin.z())
                    .append(":");

            // 加入方塊數據
            for (BlockVector3 pt : region) {
                BaseBlock block = clipboard.getFullBlock(pt);
                contentBuilder.append(block.getAsString());
            }

            // 計算雜湊值
            return Hashing.sha256()
                    .hashString(contentBuilder.toString(), StandardCharsets.UTF_8)
                    .toString();

        } catch (Exception e) {
            plugin.getLogger().warn("計算剪貼簿雜湊值時發生錯誤: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 檢查玩家的剪貼簿是否有變化
     */
    public boolean hasClipboardChanged(Player player, Clipboard currentClipboard) {
        String playerUUID = player.getUniqueId().toString();
        String currentHash = calculateClipboardHash(currentClipboard);
        String storedHash = getLocalHash(player.getUniqueId());

        // 添加調試日誌
        if (!currentHash.equals(storedHash)) {
            plugin.getLogger().debug("剪貼簿雜湊值不同:");
            plugin.getLogger().debug("當前: " + currentHash);
            plugin.getLogger().debug("儲存: " + storedHash);
        }

        return !currentHash.equals(storedHash);
    }

    private String calculateHash(byte[] data) {
        return com.google.common.hash.Hashing.sha256()
                .hashBytes(data)
                .toString();
    }

    private byte[] createUpdateMessage(UUID playerUuid, byte[] data) {
        com.google.common.io.ByteArrayDataOutput out =
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
}