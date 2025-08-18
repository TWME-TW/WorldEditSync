package dev.twme.worldeditsync.velocity.listener;

import java.util.UUID;

import com.google.common.io.ByteArrayDataInput;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.listener.BaseMessageListener;
import dev.twme.worldeditsync.velocity.WorldEditSyncVelocity;
import dev.twme.worldeditsync.velocity.clipboard.ClipboardManager;

public class MessageListener extends BaseMessageListener<Player> {
    private final WorldEditSyncVelocity plugin;
    private final ClipboardManager clipboardManager;

    public MessageListener(WorldEditSyncVelocity plugin) {
        super(plugin.getClipboardManager());
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

        Player player = backend.getPlayer();
        if (player != null) {
            handlePluginMessage(player, event.getData());
        }
    }

    @Override
    protected void handleClipboardDownload(ByteArrayDataInput in, Player player) {
        if (player == null) {
            plugin.getLogger().warn("Player not found");
            return;
        }

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
    protected void sendClipboardData(Player player, byte[] data) {
        // 分塊發送數據
        int totalChunks = (int) Math.ceil(data.length / (double) Constants.DEFAULT_CHUNK_SIZE);
        String sessionId = UUID.randomUUID().toString();

        // 發送開始訊息
        byte[] startMessage = createDownloadStartMessage(player.getUniqueId(), sessionId, totalChunks);
        player.getCurrentServer().ifPresent(server ->
                server.sendPluginMessage(
                        MinecraftChannelIdentifier.from(Constants.CHANNEL),
                        startMessage
                )
        );

        // 發送數據塊 異步
        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            String currentServer = player.getCurrentServer().map(serverConnection -> serverConnection.getServerInfo().getName()).orElse("null");
            for (int i = 0; i < totalChunks; i++) {
                if (currentServer.equals("null")) {
                    break;
                }

                if (!clipboardManager.isPlayerTransferring(player.getUniqueId())) {
                    break;
                }

                if (!player.isActive()) {
                    break;
                }

                String lastServer = player.getCurrentServer().map(serverConnection -> serverConnection.getServerInfo().getName()).orElse("null");
                if (!lastServer.equals(currentServer)) {
                    break;
                }

                try {
                    Thread.sleep(Constants.THREAD_DELAY_MS);
                } catch (InterruptedException e) {
                    e.fillInStackTrace();
                }
                int start = i * Constants.DEFAULT_CHUNK_SIZE;
                int end = Math.min(start + Constants.DEFAULT_CHUNK_SIZE, data.length);
                byte[] chunk = new byte[end - start];
                System.arraycopy(data, start, chunk, 0, chunk.length);

                // 使用基類提供的方法創建區塊消息
                String chunkSessionId = sessionId;
                int chunkIndex = i + 1;
                byte[] chunkMessage = createChunkMessage(chunkSessionId, chunkIndex, chunk);
                player.getCurrentServer().ifPresent(server ->
                        server.sendPluginMessage(
                                MinecraftChannelIdentifier.from(Constants.CHANNEL),
                                chunkMessage
                        )
                );
            }
            plugin.getLogger().info("Finished sending clipboard data to player: {} Session: {}", player.getUsername(), sessionId);
            clipboardManager.setPlayerTransferring(player.getUniqueId(), false);
        }).schedule();
    }

    @Override
    protected void sendClipboardInfo(Player player, String hash) {
        byte[] message = createInfoMessage(player.getUniqueId(), hash);
        player.getCurrentServer().ifPresent(server ->
                server.sendPluginMessage(
                        MinecraftChannelIdentifier.from(Constants.CHANNEL),
                        message
                )
        );
    }

    @Override
    protected void handlePluginMessageError(Exception e) {
        plugin.getLogger().error("Error handling plugin message", e);
    }

    @Override
    protected void handleTooManyChunks(int totalChunks) {
        plugin.getLogger().warn("Received upload request with too many chunks: {}", totalChunks);
    }

    @Override
    protected void handleInvalidChunkSize(int length) {
        plugin.getLogger().warn("Invalid chunk size received: {}", length);
    }
}
