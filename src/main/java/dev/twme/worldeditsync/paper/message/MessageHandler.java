package dev.twme.worldeditsync.paper.message;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.paper.WorldEditSyncPaper;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

public class MessageHandler implements PluginMessageListener {

    private final WorldEditSyncPaper plugin;

    public MessageHandler(WorldEditSyncPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        // plugin.getLogger().info("收到原始消息，通道: " + channel + ", 長度: " + message.length);

        if (!channel.equals(Constants.CHANNEL)) {
            return;
        }

        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String subChannel = in.readUTF();

            // plugin.getLogger().info("收到插件消息(子頻道): " + subChannel);

            switch (subChannel) {
                case "ClipboardInfo" -> handleClipboardInfo(player, in);
                case "ClipboardDownloadStart" -> handleClipboardDownloadStart(player, in);
                case "ClipboardChunk" -> handleClipboardChunk(player, in);
                case "ClipboardDownload" -> handleClipboardDownload(player, in);
                case "ClipboardUpload" -> handleClipboardUploadRequest(player, in);
                case "NoClipboardData" -> handleNoClipboardData(player, in);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("An error occurred while handling plugin message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleClipboardInfo(Player player, ByteArrayDataInput in) {

        // plugin.getLogger().info("處理 ClipboardInfo");
        try {
            String playerUuid = in.readUTF();
            if (!playerUuid.equals(player.getUniqueId().toString())) {
                return;
            }

            String remoteHash = in.readUTF();
            if (remoteHash.isEmpty()) {
                return;
            }

            // 獲取本地剪貼簿雜湊值，比較後決定是否需要下載
            String localHash = plugin.getClipboardManager().getLocalHash(player.getUniqueId());
            if (!localHash.equals(remoteHash)) {
                requestClipboardDownload(player);
                // plugin.getLogger().info("本地剪貼簿與遠程剪貼簿不匹配，請求下載剪貼簿");
                plugin.getClipboardManager().requestClipboardDownload(player);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("An error occurred while handling ClipboardInfo: " + e.getMessage());
        }
    }

    private void handleClipboardDownloadStart(Player player, ByteArrayDataInput in) {

        // plugin.getLogger().info("處理 ClipboardDownloadStart");
        try {
            String playerUuid = in.readUTF();
            if (!playerUuid.equals(player.getUniqueId().toString())) {
                return;
            }

            String sessionId = in.readUTF();
            int totalChunks = in.readInt();
            int chunkSize = in.readInt();

//            plugin.getLogger().info(String.format(
//                    "開始接收玩家 %s 的剪貼簿，共 %d 個區塊",
//                    player.getName(), totalChunks
//            ));

            // 創建新的下載會話
            plugin.getClipboardManager().startDownloadSession(player, sessionId, totalChunks, chunkSize);

        } catch (Exception e) {
            plugin.getLogger().severe("An error occurred while handling ClipboardDownloadStart: " + e.getMessage());
        }
    }

    private void handleClipboardChunk(Player player, ByteArrayDataInput in) {

        // plugin.getLogger().info("處理 ClipboardChunk");
        try {
            String sessionId = in.readUTF();
            int chunkIndex = in.readInt();
            int length = in.readInt();

            // 驗證長度
            if (length <= 0 || length > Constants.DEFAULT_CHUNK_SIZE) {
//                plugin.getLogger().warning(String.format(
//                        "無效的區塊大小: %d (最大允許: %d)",
//                        length, Constants.DEFAULT_CHUNK_SIZE
//                ));
                return;
            }

            // 嘗試讀取數據
            byte[] chunkData;
            try {
                chunkData = new byte[length];
                in.readFully(chunkData);


            } catch (Exception e) {
                plugin.getLogger().warning("Failed to read chunk data: " + e.getMessage());
                return;
            }

            // 將區塊數據添加到管理器中
            plugin.getClipboardManager().handleChunkData(player, sessionId, chunkIndex, chunkData);
//            plugin.getLogger().info(String.format(
//                    "接收區塊數據 - 會話: %s, 區塊索引: %d, 大小: %d",
//                    sessionId, chunkIndex, length
//            ));


        } catch (Exception e) {
            plugin.getLogger().severe("An error occurred while handling ClipboardChunk: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleClipboardDownload(Player player, ByteArrayDataInput in) {
        // plugin.getLogger().info("處理 ClipboardDownload");
        try {
            String playerUuid = in.readUTF();
            if (!playerUuid.equals(player.getUniqueId().toString())) {
                return;
            }

            // 請求下載剪貼簿
            requestClipboardDownload(player);
        } catch (Exception e) {
            plugin.getLogger().severe("An error occurred while handling ClipboardDownload: " + e.getMessage());
        }
    }

    private void requestClipboardDownload(Player player) {

        // plugin.getLogger().info("請求下載剪貼簿");
        plugin.getClipboardManager().requestClipboardDownload(player);
    }

    private void handleClipboardUploadRequest(Player player, ByteArrayDataInput in) {
        // plugin.getLogger().info("處理 ClipboardUpload");
        try {
            String playerUuid = in.readUTF();
            if (!playerUuid.equals(player.getUniqueId().toString())) {
                return;
            }

            plugin.getClipboardManager().startUploadClipboard(player);

        } catch (Exception e) {
            plugin.getLogger().severe("An error occurred while handling ClipboardUpload: " + e.getMessage());
        }
    }

    private void handleNoClipboardData(Player player, ByteArrayDataInput in) {
        // plugin.getLogger().info("處理 NoClipboardData");
        try {
            String playerUuid = in.readUTF();
            if (!playerUuid.equals(player.getUniqueId().toString())) {
                return;
            }
            plugin.getClipboardManager().check(player.getUniqueId());


        } catch (Exception e) {
            plugin.getLogger().severe("An error occurred while handling NoClipboardData: " + e.getMessage());
        }
    }
}
