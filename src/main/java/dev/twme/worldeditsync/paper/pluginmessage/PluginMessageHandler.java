package dev.twme.worldeditsync.paper.pluginmessage;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.paper.WorldEditSyncPaper;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

public class PluginMessageHandler implements PluginMessageListener {

    private final WorldEditSyncPaper plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public PluginMessageHandler(WorldEditSyncPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (!channel.equals(Constants.CHANNEL)) {
            return;
        }

        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String subChannel = in.readUTF();

            switch (subChannel) {
                case "ClipboardInfo" -> handleClipboardInfo(player, in);
                case "ClipboardDownloadStart" -> handleClipboardDownloadStart(player, in);
                case "ClipboardChunk" -> handleClipboardChunk(player, in);
                case "ClipboardDownload" -> handleClipboardDownload(player, in);
                case "ClipboardUpload" -> handleClipboardUploadRequest(player, in);
                case "NoClipboardData" -> handleNoClipboardData(player, in);
                case "ClipboardUpdate" -> requestClipboardDownload(player);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("An error occurred while handling plugin message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleClipboardInfo(Player player, ByteArrayDataInput in) {

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
                plugin.getClipboardManager().requestClipboardDownload(player);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("An error occurred while handling ClipboardInfo: " + e.getMessage());
        }
    }

    private void handleClipboardDownloadStart(Player player, ByteArrayDataInput in) {

        try {
            String playerUuid = in.readUTF();
            if (!playerUuid.equals(player.getUniqueId().toString())) {
                return;
            }

            String sessionId = in.readUTF();
            int totalChunks = in.readInt();
            int chunkSize = in.readInt();

            // 創建新的下載會話
            plugin.getClipboardManager().startDownloadSession(player, sessionId, totalChunks, chunkSize);

        } catch (Exception e) {
            plugin.getLogger().severe("An error occurred while handling ClipboardDownloadStart: " + e.getMessage());
        }
    }

    private void handleClipboardChunk(Player player, ByteArrayDataInput in) {

        try {
            String sessionId = in.readUTF();
            int chunkIndex = in.readInt();
            int length = in.readInt();

            // 驗證長度
            if (length <= 0 || length > Constants.DEFAULT_CHUNK_SIZE) {
                player.sendActionBar(mm.deserialize("<red>Copied data is too large!</red>"));
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


        } catch (Exception e) {
            plugin.getLogger().severe("An error occurred while handling ClipboardChunk: " + e.getMessage());
            e.fillInStackTrace();
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

        plugin.getClipboardManager().requestClipboardDownload(player);
    }

    private void handleClipboardUploadRequest(Player player, ByteArrayDataInput in) {

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
