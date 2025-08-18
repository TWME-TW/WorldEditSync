package dev.twme.worldeditsync.bungeecord.listener;

import java.util.UUID;

import com.google.common.io.ByteArrayDataInput;

import dev.twme.worldeditsync.bungeecord.WorldEditSyncBungee;
import dev.twme.worldeditsync.bungeecord.clipboard.ClipboardManager;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.listener.BaseMessageListener;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Listener;

public class MessageListener extends BaseMessageListener<ProxiedPlayer> implements Listener {
    private final WorldEditSyncBungee plugin;
    private final ClipboardManager clipboardManager;

    public MessageListener(WorldEditSyncBungee plugin) {
        super(plugin.getClipboardManager());
        this.plugin = plugin;
        this.clipboardManager = plugin.getClipboardManager();
    }

    public void onPluginMessageReceived(ProxiedPlayer player, byte[] message) {
        handlePluginMessage(player, message);
    }

    @Override
    protected void handleClipboardDownload(ByteArrayDataInput in, ProxiedPlayer player) {
        String playerUuid = in.readUTF();
        ClipboardManager.ClipboardData clipboardData = clipboardManager.getClipboard(UUID.fromString(playerUuid));

        if (clipboardData != null) {
            if (clipboardManager.isPlayerTransferring(player.getUniqueId())) {
                // 如果玩家正在傳輸剪貼簿，則不允許下載
                return;
            }
            clipboardManager.setPlayerTransferring(player.getUniqueId(), true);
            sendClipboardData(player, clipboardData.getData());
        }
    }

    @Override
    protected void sendClipboardData(ProxiedPlayer player, byte[] data) {
        // 分塊發送數據
        int totalChunks = (int) Math.ceil(data.length / (double) Constants.DEFAULT_CHUNK_SIZE);
        String sessionId = UUID.randomUUID().toString();

        // 發送開始訊息
        byte[] startMessage = createDownloadStartMessage(player.getUniqueId(), sessionId, totalChunks);
        player.getServer().getInfo().sendData(Constants.CHANNEL, startMessage);

        // 發送數據塊 異步
        plugin.getProxy().getScheduler().runAsync(plugin, () -> {
            for (int i = 0; i < totalChunks; i++) {
                try {
                    Thread.sleep(Constants.THREAD_DELAY_MS);
                } catch (InterruptedException e) {
                    e.fillInStackTrace();
                }

                if (!player.isConnected()) {
                    plugin.getLogger().info("Player disconnected, stopping clipboard transfer: " + player.getName());
                    clipboardManager.setPlayerTransferring(player.getUniqueId(), false);
                    break;
                }

                if (!clipboardManager.isPlayerTransferring(player.getUniqueId())) {
                    plugin.getLogger().info("Player is no longer transferring, stopping clipboard transfer: " + player.getName());
                    break;
                }

                int start = i * Constants.DEFAULT_CHUNK_SIZE;
                int end = Math.min(start + Constants.DEFAULT_CHUNK_SIZE, data.length);
                byte[] chunk = new byte[end - start];
                System.arraycopy(data, start, chunk, 0, chunk.length);

                // 使用基類提供的方法創建區塊消息
                String chunkSessionId = sessionId;
                int chunkIndex = i + 1;
                byte[] chunkMessage = createChunkMessage(chunkSessionId, chunkIndex, chunk);
                player.getServer().getInfo().sendData(Constants.CHANNEL, chunkMessage);
            }
            clipboardManager.setPlayerTransferring(player.getUniqueId(), false);
            plugin.getLogger().info("Finished sending clipboard data to player: " + player.getName() +" Session: " +  sessionId);
        });
    }

    @Override
    protected void sendClipboardInfo(ProxiedPlayer player, String hash) {
        byte[] message = createInfoMessage(player.getUniqueId(), hash);
        player.getServer().getInfo().sendData(Constants.CHANNEL, message);
    }

    @Override
    protected void handlePluginMessageError(Exception e) {
        plugin.getLogger().warning("Error handling plugin message " + e);
    }

    @Override
    protected void handleTooManyChunks(int totalChunks) {
        plugin.getLogger().warning("Received upload request with too many chunks: " + totalChunks);
    }

    @Override
    protected void handleInvalidChunkSize(int length) {
        plugin.getLogger().warning("Invalid chunk size received: " + length);
    }
}
