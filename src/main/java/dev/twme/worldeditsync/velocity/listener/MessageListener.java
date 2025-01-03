package dev.twme.worldeditsync.velocity.listener;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.velocity.WorldEditSyncVelocity;
import dev.twme.worldeditsync.velocity.clipboard.ClipboardManager;

import java.util.Optional;
import java.util.UUID;

public class MessageListener {
    private final WorldEditSyncVelocity plugin;
    private final ClipboardManager clipboardManager;

    public MessageListener(WorldEditSyncVelocity plugin) {
        this.plugin = plugin;
        this.clipboardManager = plugin.getClipboardManager();
    }

    @Subscribe
    public void onPluginMessageFromBackend(PluginMessageEvent event) {

        if (!event.getIdentifier().equals(MinecraftChannelIdentifier.from(Constants.CHANNEL))) {
            return;
        }

        event.setResult(PluginMessageEvent.ForwardResult.handled());


        if (!(event.getSource() instanceof ServerConnection backend)) {
            return;
        }


        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
            String subChannel = in.readUTF();

            // plugin.getLogger().info("收到插件消息(子通道): {}", subChannel);

            
            switch (subChannel) {
                case "ClipboardUpload":
                    handleClipboardUpload(in);
                    break;
                case "ClipboardDownload": // 當 Paper 端要求下載剪貼簿時觸發
                    handleClipboardDownload(in, event);
                    break;
                case "ClipboardChunk":
                    handleClipboardChunk(in);
                    break;
                case "ClipboardInfo":
                    handleClipboardInfo(in, event);
                    break;
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error handling plugin message", e);
        }
    }

    private void handleClipboardUpload(ByteArrayDataInput in) {
        String playerUuid = in.readUTF();
        String sessionId = in.readUTF();
        int totalChunks = in.readInt();
        int chunkSize = in.readInt();

        if (totalChunks > Constants.MAX_CHUNKS) {
            plugin.getLogger().warn("Received upload request with too many chunks: {}", totalChunks);
            return;
        }

        clipboardManager.createTransferSession(sessionId,
                UUID.fromString(playerUuid), totalChunks, chunkSize);
    }

    private void handleClipboardDownload(ByteArrayDataInput in, PluginMessageEvent event) {
        // plugin.getLogger().info("處理 ClipboardDownload");
        String playerUuid = in.readUTF();
        Player player;

        Optional<Player> playerOptional = plugin.getServer().getPlayer(UUID.fromString(playerUuid));
        if (playerOptional.isPresent()) {
            player = playerOptional.get();
        } else {
            plugin.getLogger().warn("Player not found: {}", playerUuid);
            return;
        }
        ClipboardManager.ClipboardData clipboardData =
                clipboardManager.getClipboard(UUID.fromString(playerUuid));

        if (clipboardData != null) {
            // plugin.getLogger().info("找到剪貼板資料，開始發送給玩家: {}", player.getUsername());

            sendClipboardData(player, clipboardData.getData());
        } else {
            // plugin.getLogger().info("未找到剪貼板資料，請求下載失敗: {}", player.getUsername());
        }
    }

    private void handleClipboardChunk(ByteArrayDataInput in) {
        String sessionId = in.readUTF();
        int chunkIndex = in.readInt();
        int length = in.readInt();



        if (length <= 0 || length > Constants.DEFAULT_CHUNK_SIZE) {
            plugin.getLogger().warn("Invalid chunk size received: {}", length);
            return;
        }

        // plugin.getLogger().info("接收區塊數據 - 會話: {}, 區塊索引: {}, 大小: {}", sessionId, chunkIndex, length);


        byte[] chunkData = new byte[length];
        in.readFully(chunkData);

        clipboardManager.addChunk(sessionId, chunkIndex, chunkData);
    }

    private void handleClipboardInfo(ByteArrayDataInput in, PluginMessageEvent event) {
        if (!(event.getSource() instanceof Player player)) {
            return;
        }

        String playerUuid = in.readUTF();
        ClipboardManager.ClipboardData clipboardData =
                clipboardManager.getClipboard(UUID.fromString(playerUuid));

        if (clipboardData != null) {
            sendClipboardInfo(player, clipboardData.getHash());
        }
    }

    private void sendClipboardData(Player player, byte[] data) {
        // 分塊發送數據
        int totalChunks = (int) Math.ceil(data.length / (double) Constants.DEFAULT_CHUNK_SIZE);
        String sessionId = UUID.randomUUID().toString();

        // 發送開始訊息
        ByteArrayDataOutput startOut = ByteStreams.newDataOutput();
        startOut.writeUTF("ClipboardDownloadStart");
        startOut.writeUTF(player.getUniqueId().toString());
        startOut.writeUTF(sessionId);
        startOut.writeInt(totalChunks);
        startOut.writeInt(Constants.DEFAULT_CHUNK_SIZE);


//        player.sendPluginMessage(
//                MinecraftChannelIdentifier.from(Constants.CHANNEL),
//                startOut.toByteArray()
//        );



        // plugin.getLogger().info("開始向玩家 {} 發送剪貼板數據", player.getUsername());
        // player.getCurrentServer().ifPresent(server ->
        //         server.sendPluginMessage(
        //                 MinecraftChannelIdentifier.from(Constants.CHANNEL),
        //                 startOut.toByteArray()
        //         )
        // );

        player.getCurrentServer().ifPresent(server ->
                server.sendPluginMessage(
                        MinecraftChannelIdentifier.from(Constants.CHANNEL),
                        startOut.toByteArray()
                )
        );

        // 發送數據塊
        for (int i = 0; i < totalChunks; i++) {
            int start = i * Constants.DEFAULT_CHUNK_SIZE;
            int end = Math.min(start + Constants.DEFAULT_CHUNK_SIZE, data.length);
            byte[] chunk = new byte[end - start];
            System.arraycopy(data, start, chunk, 0, chunk.length);

            ByteArrayDataOutput chunkOut = ByteStreams.newDataOutput();
            chunkOut.writeUTF("ClipboardChunk");
            chunkOut.writeUTF(sessionId);
            chunkOut.writeInt(i + 1);
            chunkOut.writeInt(chunk.length);
            chunkOut.write(chunk);

//            player.sendPluginMessage(
//                MinecraftChannelIdentifier.from(Constants.CHANNEL),
//                chunkOut.toByteArray()
//            );

             player.getCurrentServer().ifPresent(server ->
                     server.sendPluginMessage(
                             MinecraftChannelIdentifier.from(Constants.CHANNEL),
                             chunkOut.toByteArray()
                     )
             );

            if (i % 10 == 0 || i == totalChunks - 1) {
                // plugin.getLogger().info("發送進度 - 玩家: {}, 會話: {}, 已發送: {}/{} 區塊", player.getUsername(), sessionId, i + 1, totalChunks);
            }
        }

        plugin.getLogger().info("Finished sending clipboard data to player: {} Session: {}", player.getUsername(), sessionId);

        // plugin.getLogger().info("完成向玩家 {} 發送剪貼板數據", player.getUsername());
    }

    private void sendClipboardInfo(Player player, String hash) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("ClipboardInfo");
        out.writeUTF(player.getUniqueId().toString());
        out.writeUTF(hash);

        player.getCurrentServer().ifPresent(server ->
                server.sendPluginMessage(
                        MinecraftChannelIdentifier.from(Constants.CHANNEL),
                        out.toByteArray()
                )
        );
    }
}
