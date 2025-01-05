package dev.twme.worldeditsync.bungeecord.listener;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Listener;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.bungeecord.WorldEditSyncBungee;
import dev.twme.worldeditsync.bungeecord.clipboard.ClipboardManager;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import java.util.UUID;

public class MessageListener implements Listener {
    private final WorldEditSyncBungee plugin;
    private final ClipboardManager clipboardManager;

    public MessageListener(WorldEditSyncBungee plugin) {
        this.plugin = plugin;
        this.clipboardManager = plugin.getClipboardManager();
    }

    public void onPluginMessageReceived(ProxiedPlayer player, byte[] message) {

        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String subChannel = in.readUTF();

            switch (subChannel) {
                case "ClipboardUpload":
                    handleClipboardUpload(in);
                    break;
                case "ClipboardDownload":
                    handleClipboardDownload(in, player);
                    break;
                case "ClipboardChunk":
                    handleClipboardChunk(in);
                    break;
                case "ClipboardInfo":
                    handleClipboardInfo(in, player);
                    break;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error handling plugin message " + e);
        }
    }

    private void handleClipboardUpload(ByteArrayDataInput in) {
        String playerUuid = in.readUTF();
        String sessionId = in.readUTF();
        int totalChunks = in.readInt();
        int chunkSize = in.readInt();

        if (totalChunks > Constants.MAX_CHUNKS) {
            plugin.getLogger().warning("Received upload request with too many chunks: " + totalChunks);
            return;
        }

        clipboardManager.createTransferSession(sessionId,
                UUID.fromString(playerUuid), totalChunks, chunkSize);

        plugin.getLogger().info("Created transfer session: " + sessionId);
    }

    private void handleClipboardDownload(ByteArrayDataInput in, ProxiedPlayer player) {
        String playerUuid = in.readUTF();
        ClipboardManager.ClipboardData clipboardData = clipboardManager.getClipboard(UUID.fromString(playerUuid));
        plugin.getLogger().info("Received download request from player: " + player.getName() + " UUID: " + playerUuid);
        if (clipboardData != null) {
            plugin.getLogger().info("Sending clipboard data to player: " + player.getName());
            sendClipboardData(player, clipboardData.getData());
        }
    }

    private void handleClipboardChunk(ByteArrayDataInput in) {
        String sessionId = in.readUTF();
        int chunkIndex = in.readInt();
        int length = in.readInt();

        if (length <= 0 || length > Constants.DEFAULT_CHUNK_SIZE) {
            plugin.getLogger().warning("Invalid chunk size received: " + length);
            return;
        }

        byte[] chunkData = new byte[length];
        in.readFully(chunkData);

        clipboardManager.addChunk(sessionId, chunkIndex, chunkData);
    }

    private void handleClipboardInfo(ByteArrayDataInput in, ProxiedPlayer player) {
        String playerUuid = in.readUTF();
        ClipboardManager.ClipboardData clipboardData =
                clipboardManager.getClipboard(UUID.fromString(playerUuid));

        if (clipboardData != null) {
            sendClipboardInfo(player, clipboardData.getHash());
        }
    }

    private void sendClipboardData(ProxiedPlayer player, byte[] data) {
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

        player.getServer().getInfo().sendData(Constants.CHANNEL, startOut.toByteArray());

        // 發送數據塊 異步
        plugin.getProxy().getScheduler().runAsync(plugin, () -> {
            for (int i = 0; i < totalChunks; i++) {
                try {
                    Thread.sleep(Constants.THREAD_DELAY_MS);
                } catch (InterruptedException e) {
                    e.fillInStackTrace();
                }
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

                player.getServer().getInfo().sendData(Constants.CHANNEL, chunkOut.toByteArray());
            }
            plugin.getLogger().info("Finished sending clipboard data to player: " + player.getName() +" Session: " +  sessionId);
        });
    }

    private void sendClipboardInfo(ProxiedPlayer player, String hash) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("ClipboardInfo");
        out.writeUTF(player.getUniqueId().toString());
        out.writeUTF(hash);

        player.getServer().getInfo().sendData(Constants.CHANNEL, out.toByteArray());
    }
}
