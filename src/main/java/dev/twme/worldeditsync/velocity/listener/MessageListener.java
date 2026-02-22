package dev.twme.worldeditsync.velocity.listener;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.clipboard.ClipboardData;
import dev.twme.worldeditsync.common.transfer.TransferProtocol;
import dev.twme.worldeditsync.common.transfer.TransferSession;
import dev.twme.worldeditsync.velocity.WorldEditSyncVelocity;
import dev.twme.worldeditsync.velocity.clipboard.ClipboardManager;

import java.util.UUID;

/**
 * Velocity 端的訊息處理器。
 * 處理來自 Paper 後端伺服器的 Plugin Message。
 *
 * 支援的訊息類型：
 * - Upload:Begin     → 開始接收上傳
 * - Upload:Chunk     → 接收上傳的 chunk
 * - Download:Request → Paper 請求下載剪貼簿
 * - Sync:Cancel      → 取消傳輸
 */
public class MessageListener {
    private final WorldEditSyncVelocity plugin;
    private final ClipboardManager clipboardManager;
    private static final MinecraftChannelIdentifier CHANNEL_ID = MinecraftChannelIdentifier.from(Constants.CHANNEL);

    public MessageListener(WorldEditSyncVelocity plugin) {
        this.plugin = plugin;
        this.clipboardManager = plugin.getClipboardManager();
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL_ID)) return;

        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (!(event.getSource() instanceof ServerConnection backend)) return;

        Player player = backend.getPlayer();
        if (player == null) return;

        try {
            // 解密訊息
            byte[] decrypted;
            try {
                decrypted = plugin.getMessageCipher().decrypt(event.getData());
            } catch (SecurityException e) {
                plugin.getLogger().warn("Received invalid/unauthorized message from {}: {}", player.getUsername(), e.getMessage());
                return;
            }

            ByteArrayDataInput in = ByteStreams.newDataInput(decrypted);
            String subChannel = in.readUTF();

            switch (subChannel) {
                case TransferProtocol.UPLOAD_BEGIN -> handleUploadBegin(player, in);
                case TransferProtocol.UPLOAD_CHUNK -> handleUploadChunk(player, in);
                case TransferProtocol.DOWNLOAD_REQUEST -> handleDownloadRequest(player, in);
                case TransferProtocol.CANCEL -> handleCancel(player, in);
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error handling plugin message", e);
        }
    }

    /**
     * 處理上傳開始訊息：建立上傳會話。
     */
    private void handleUploadBegin(Player player, ByteArrayDataInput in) {
        String uuid = in.readUTF();
        String sessionId = in.readUTF();
        int totalChunks = in.readInt();
        int totalBytes = in.readInt();
        String hash = in.readUTF();

        if (totalBytes > Constants.MAX_CLIPBOARD_SIZE) {
            plugin.getLogger().warn("Upload too large from {}: {} bytes", player.getUsername(), totalBytes);
            return;
        }

        clipboardManager.createUploadSession(sessionId, UUID.fromString(uuid), totalChunks, totalBytes, hash);
        plugin.getLogger().info("Upload session started: {} from {} ({} bytes, {} chunks)",
                sessionId, player.getUsername(), totalBytes, totalChunks);
    }

    /**
     * 處理上傳 chunk：添加至會話，完成時組裝並儲存。
     */
    private void handleUploadChunk(Player player, ByteArrayDataInput in) {
        String sessionId = in.readUTF();
        int chunkIndex = in.readInt();
        int length = in.readInt();

        if (length <= 0 || length > Constants.CHUNK_SIZE + 1024) {
            plugin.getLogger().warn("Invalid chunk size: {}", length);
            return;
        }

        byte[] chunkData = new byte[length];
        in.readFully(chunkData);

        TransferSession session = clipboardManager.getUploadSession(sessionId);
        if (session == null) {
            plugin.getLogger().warn("No active upload session: {}", sessionId);
            return;
        }

        session.addChunk(chunkIndex, chunkData);

        if (session.isComplete()) {
            completeUpload(session);
        }
    }

    /**
     * 完成上傳：組裝資料、計算 hash 並儲存。
     */
    private void completeUpload(TransferSession session) {
        byte[] fullData = session.assembleData();
        clipboardManager.removeUploadSession(session.getSessionId());

        if (fullData == null) {
            plugin.getLogger().warn("Failed to assemble upload data: {}", session.getSessionId());
            return;
        }

        String hash = ClipboardManager.computeHash(fullData);
        clipboardManager.storeClipboard(session.getPlayerUuid(), fullData, hash);
        plugin.getLogger().info("Upload complete, stored clipboard for {} ({} bytes)",
                session.getPlayerUuid(), fullData.length);
    }

    /**
     * 處理下載請求：將儲存的剪貼簿資料分塊發送至 Paper 端。
     */
    private void handleDownloadRequest(Player player, ByteArrayDataInput in) {
        String uuid = in.readUTF();
        ClipboardData clipboardData = clipboardManager.getClipboard(UUID.fromString(uuid));

        if (clipboardData == null || clipboardData.getData() == null) {
            player.getCurrentServer().ifPresent(server ->
                    server.sendPluginMessage(CHANNEL_ID,
                            plugin.getMessageCipher().encrypt(TransferProtocol.createNoData(uuid))));
            return;
        }

        sendClipboardToPlayer(player, UUID.fromString(uuid), clipboardData);
    }

    /**
     * 分塊發送剪貼簿資料至 Paper 端。
     */
    private void sendClipboardToPlayer(Player player, UUID playerUuid, ClipboardData data) {
        byte[] clipboardBytes = data.getData();
        int totalChunks = (int) Math.ceil((double) clipboardBytes.length / Constants.CHUNK_SIZE);
        String sessionId = UUID.randomUUID().toString();

        // 發送下載開始訊息
        player.getCurrentServer().ifPresent(server ->
                server.sendPluginMessage(CHANNEL_ID,
                        plugin.getMessageCipher().encrypt(
                                TransferProtocol.createDownloadBegin(
                                        playerUuid.toString(), sessionId, totalChunks, clipboardBytes.length, data.getHash()))));

        // 非同步發送所有 chunk
        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            try {
                String initialServer = player.getCurrentServer()
                        .map(s -> s.getServerInfo().getName()).orElse(null);

                for (int i = 0; i < totalChunks; i++) {
                    if (!player.isActive()) break;

                    // 檢查玩家是否仍在同一伺服器
                    String currentServer = player.getCurrentServer()
                            .map(s -> s.getServerInfo().getName()).orElse(null);
                    if (initialServer == null || !initialServer.equals(currentServer)) break;

                    int offset = i * Constants.CHUNK_SIZE;
                    int length = Math.min(Constants.CHUNK_SIZE, clipboardBytes.length - offset);
                    byte[] chunk = new byte[length];
                    System.arraycopy(clipboardBytes, offset, chunk, 0, length);

                    byte[] chunkMessage = plugin.getMessageCipher().encrypt(
                            TransferProtocol.createDownloadChunk(sessionId, i, chunk));
                    player.getCurrentServer().ifPresent(server ->
                            server.sendPluginMessage(CHANNEL_ID, chunkMessage));

                    if (i < totalChunks - 1) {
                        Thread.sleep(Constants.CHUNK_SEND_DELAY_MS);
                    }
                }
                plugin.getLogger().info("Download sent to {} (session: {})", player.getUsername(), sessionId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                plugin.getLogger().error("Error sending download to {}", player.getUsername(), e);
            }
        }).schedule();
    }

    /**
     * 處理取消傳輸。
     */
    private void handleCancel(Player player, ByteArrayDataInput in) {
        String uuid = in.readUTF();
        clipboardManager.setPlayerTransferring(UUID.fromString(uuid), false);
        plugin.getLogger().info("Transfer cancelled for {}", player.getUsername());
    }
}
