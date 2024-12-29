package dev.twme.worldeditsync.paper.message;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.transfer.TransferSession;
import dev.twme.worldeditsync.paper.WorldEditSyncPaper;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MessageHandler implements PluginMessageListener {
    private final WorldEditSyncPaper plugin;
    private final Map<String, DownloadSession> downloadSessions;

    public MessageHandler(WorldEditSyncPaper plugin) {
        this.plugin = plugin;
        this.downloadSessions = new HashMap<>();
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, @NotNull byte[] message) {
        if (!channel.equals(Constants.CHANNEL)) {
            return;
        }

        ByteArrayDataInput in = ByteStreams.newDataInput(message);
        String subChannel = in.readUTF();

        try {
            switch (subChannel) {
                case "ClipboardInfo":
                    handleClipboardInfo(player, in);
                    break;
                case "ClipboardDownloadStart":
                    handleClipboardDownloadStart(player, in);
                    break;
                case "ClipboardChunk":
                    handleClipboardChunk(player, in);
                    break;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error handling message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleClipboardInfo(Player player, ByteArrayDataInput in) {
        String playerUuid = in.readUTF();
        String hash = in.readUTF();

        if (hash.isEmpty()) {
            return;
        }

        String localHash = plugin.getClipboardManager().getLocalHash(player.getUniqueId());
        if (localHash == null || !localHash.equals(hash)) {
            // 請求下載新的剪貼簿
            requestClipboardDownload(player);
        }
    }

    private void handleClipboardDownloadStart(Player player, ByteArrayDataInput in) {
        String playerUuid = in.readUTF();
        String sessionId = in.readUTF();
        int totalChunks = in.readInt();
        int chunkSize = in.readInt();

        downloadSessions.put(sessionId, new DownloadSession(
                UUID.fromString(playerUuid),
                totalChunks,
                chunkSize
        ));
    }

    private void handleClipboardChunk(Player player, ByteArrayDataInput in) {
        try {
            String sessionId = in.readUTF();
            int chunkIndex = in.readInt();

            // 檢查數據剩餘長度
            int available = in.available();
            plugin.getLogger().debug("Available bytes before length read: " + available);

            int length = in.readInt();

            // 驗證長度
            if (length <= 0 || length > Constants.DEFAULT_CHUNK_SIZE) {
                plugin.getLogger().warning("無效的區塊大小: " + length +
                        " (最大允許: " + Constants.DEFAULT_CHUNK_SIZE + ")");
                return;
            }

            // 確保有足夠的數據可讀
            if (in.available() < length) {
                plugin.getLogger().warning("數據不足: 需要 " + length +
                        " 字節但只有 " + in.available() + " 字節可用");
                return;
            }

            byte[] chunkData = new byte[length];
            in.readFully(chunkData);

            TransferSession session = transferManager.getSession(sessionId);
            if (session != null) {
                session.addChunk(chunkIndex, chunkData);

                plugin.getLogger().debug(String.format(
                        "接收區塊 - 會話: %s, 索引: %d/%d, 大小: %d bytes",
                        sessionId, chunkIndex + 1, session.getTotalChunks(), length));

                if (session.isComplete()) {
                    handleCompleteTransfer(player, session);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("處理區塊數據時發生錯誤: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void requestClipboardDownload(Player player) {
        com.google.common.io.ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("ClipboardDownload");
        out.writeUTF(player.getUniqueId().toString());
        player.sendPluginMessage(plugin, Constants.CHANNEL, out.toByteArray());
    }

    private static class DownloadSession {
        private final UUID playerUuid;
        private final int totalChunks;
        private final int chunkSize;
        private final Map<Integer, byte[]> chunks;

        public DownloadSession(UUID playerUuid, int totalChunks, int chunkSize) {
            this.playerUuid = playerUuid;
            this.totalChunks = totalChunks;
            this.chunkSize = chunkSize;
            this.chunks = new HashMap<>();
        }

        public void addChunk(int index, byte[] data) {
            chunks.put(index, data);
        }

        public boolean isComplete() {
            return chunks.size() == totalChunks;
        }

        public byte[] assembleData() {
            byte[] result = new byte[totalChunks * chunkSize];
            for (int i = 0; i < totalChunks; i++) {
                byte[] chunk = chunks.get(i);
                if (chunk != null) {
                    System.arraycopy(chunk, 0, result, i * chunkSize, chunk.length);
                }
            }
            return result;
        }
    }
}