package dev.twme.worldeditsync.paper.message;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
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
            }
        } catch (Exception e) {
            plugin.getLogger().severe("處理插件消息時發生錯誤: " + e.getMessage());
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
            }
        } catch (Exception e) {
            plugin.getLogger().severe("處理 ClipboardInfo 時發生錯誤: " + e.getMessage());
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

            plugin.getLogger().info(String.format(
                    "開始接收玩家 %s 的剪貼簿，共 %d 個區塊",
                    player.getName(), totalChunks
            ));

            // 創建新的下載會話
            plugin.getClipboardManager().startDownloadSession(player, sessionId, totalChunks, chunkSize);

        } catch (Exception e) {
            plugin.getLogger().severe("處理 ClipboardDownloadStart 時發生錯誤: " + e.getMessage());
        }
    }

    private void handleClipboardChunk(Player player, ByteArrayDataInput in) {
        try {
            String sessionId = in.readUTF();
            int chunkIndex = in.readInt();
            int length = in.readInt();

            // 驗證長度
            if (length <= 0 || length > Constants.DEFAULT_CHUNK_SIZE) {
                plugin.getLogger().warning(String.format(
                        "無效的區塊大小: %d (最大允許: %d)",
                        length, Constants.DEFAULT_CHUNK_SIZE
                ));
                return;
            }

            // 嘗試讀取數據
            byte[] chunkData;
            try {
                chunkData = new byte[length];
                in.readFully(chunkData);
            } catch (Exception e) {
                plugin.getLogger().warning("讀取區塊數據失敗: " + e.getMessage());
                return;
            }

            // 將區塊數據添加到管理器中
            plugin.getClipboardManager().handleChunkData(player, sessionId, chunkIndex, chunkData);

        } catch (Exception e) {
            plugin.getLogger().severe("處理 ClipboardChunk 時發生錯誤: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void requestClipboardDownload(Player player) {
        try {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("ClipboardDownload");
            out.writeUTF(player.getUniqueId().toString());

            player.sendPluginMessage(plugin, Constants.CHANNEL, out.toByteArray());
            player.sendMessage("§e正在從其他伺服器下載剪貼簿...");

        } catch (Exception e) {
            plugin.getLogger().severe("請求下載剪貼簿時發生錯誤: " + e.getMessage());
            player.sendMessage("§c請求下載剪貼簿時發生錯誤！");
        }
    }
}