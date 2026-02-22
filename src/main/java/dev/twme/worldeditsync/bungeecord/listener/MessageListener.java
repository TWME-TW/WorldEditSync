package dev.twme.worldeditsync.bungeecord.listener;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import dev.twme.worldeditsync.bungeecord.WorldEditSyncBungee;
import dev.twme.worldeditsync.bungeecord.clipboard.ClipboardManager;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.clipboard.ClipboardData;
import dev.twme.worldeditsync.common.transfer.TransferProtocol;
import dev.twme.worldeditsync.common.transfer.TransferSession;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Listener;

import java.util.UUID;

/**
 * BungeeCord 端的訊息處理器。
 * 處理來自 Paper 後端伺服器的 Plugin Message。
 *
 * 支援的訊息類型：
 * - Upload:Begin     → 開始接收上傳
 * - Upload:Chunk     → 接收上傳的 chunk
 * - Download:Request → Paper 請求下載剪貼簿
 * - Sync:Cancel      → 取消傳輸
 */
public class MessageListener implements Listener {
    private final WorldEditSyncBungee plugin;
    private final ClipboardManager clipboardManager;

    public MessageListener(WorldEditSyncBungee plugin) {
        this.plugin = plugin;
        this.clipboardManager = plugin.getClipboardManager();
    }

    /**
     * 由 WorldEditSyncBungee 的 PluginMessageEvent 呼叫。
     */
    public void onPluginMessageReceived(ProxiedPlayer player, byte[] message) {
        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String subChannel = in.readUTF();

            switch (subChannel) {
                case TransferProtocol.UPLOAD_BEGIN -> handleUploadBegin(player, in);
                case TransferProtocol.UPLOAD_CHUNK -> handleUploadChunk(player, in);
                case TransferProtocol.DOWNLOAD_REQUEST -> handleDownloadRequest(player, in);
                case TransferProtocol.CANCEL -> handleCancel(player, in);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error handling plugin message: " + e.getMessage());
        }
    }

    /**
     * 處理上傳開始訊息：建立上傳會話。
     */
    private void handleUploadBegin(ProxiedPlayer player, ByteArrayDataInput in) {
        String uuid = in.readUTF();
        String sessionId = in.readUTF();
        int totalChunks = in.readInt();
        int totalBytes = in.readInt();
        String hash = in.readUTF();

        if (totalBytes > Constants.MAX_CLIPBOARD_SIZE) {
            plugin.getLogger().warning("Upload too large from " + player.getName() + ": " + totalBytes + " bytes");
            return;
        }

        clipboardManager.createUploadSession(sessionId, UUID.fromString(uuid), totalChunks, totalBytes, hash);
        plugin.getLogger().info("Upload session started: " + sessionId
                + " from " + player.getName() + " (" + totalBytes + " bytes, " + totalChunks + " chunks)");
    }

    /**
     * 處理上傳 chunk：添加至會話，完成時組裝並儲存。
     */
    private void handleUploadChunk(ProxiedPlayer player, ByteArrayDataInput in) {
        String sessionId = in.readUTF();
        int chunkIndex = in.readInt();
        int length = in.readInt();

        if (length <= 0 || length > Constants.CHUNK_SIZE + 1024) {
            plugin.getLogger().warning("Invalid chunk size: " + length);
            return;
        }

        byte[] chunkData = new byte[length];
        in.readFully(chunkData);

        TransferSession session = clipboardManager.getUploadSession(sessionId);
        if (session == null) {
            plugin.getLogger().warning("No active upload session: " + sessionId);
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
            plugin.getLogger().warning("Failed to assemble upload data: " + session.getSessionId());
            return;
        }

        String hash = ClipboardManager.computeHash(fullData);
        clipboardManager.storeClipboard(session.getPlayerUuid(), fullData, hash);
        plugin.getLogger().info("Upload complete, stored clipboard for "
                + session.getPlayerUuid() + " (" + fullData.length + " bytes)");
    }

    /**
     * 處理下載請求：將儲存的剪貼簿資料分塊發送至 Paper 端。
     */
    private void handleDownloadRequest(ProxiedPlayer player, ByteArrayDataInput in) {
        String uuid = in.readUTF();
        ClipboardData clipboardData = clipboardManager.getClipboard(UUID.fromString(uuid));

        if (clipboardData == null || clipboardData.getData() == null) {
            player.getServer().getInfo().sendData(Constants.CHANNEL,
                    TransferProtocol.createNoData(uuid));
            return;
        }

        sendClipboardToPlayer(player, UUID.fromString(uuid), clipboardData);
    }

    /**
     * 分塊發送剪貼簿資料至 Paper 端。
     */
    private void sendClipboardToPlayer(ProxiedPlayer player, UUID playerUuid, ClipboardData data) {
        byte[] clipboardBytes = data.getData();
        int totalChunks = (int) Math.ceil((double) clipboardBytes.length / Constants.CHUNK_SIZE);
        String sessionId = UUID.randomUUID().toString();

        // 發送下載開始訊息
        player.getServer().getInfo().sendData(Constants.CHANNEL,
                TransferProtocol.createDownloadBegin(
                        playerUuid.toString(), sessionId, totalChunks, clipboardBytes.length, data.getHash()));

        // 非同步發送所有 chunk
        plugin.getProxy().getScheduler().runAsync(plugin, () -> {
            try {
                for (int i = 0; i < totalChunks; i++) {
                    if (!player.isConnected()) break;

                    int offset = i * Constants.CHUNK_SIZE;
                    int length = Math.min(Constants.CHUNK_SIZE, clipboardBytes.length - offset);
                    byte[] chunk = new byte[length];
                    System.arraycopy(clipboardBytes, offset, chunk, 0, length);

                    player.getServer().getInfo().sendData(Constants.CHANNEL,
                            TransferProtocol.createDownloadChunk(sessionId, i, chunk));

                    if (i < totalChunks - 1) {
                        Thread.sleep(Constants.CHUNK_SEND_DELAY_MS);
                    }
                }
                plugin.getLogger().info("Download sent to " + player.getName() + " (session: " + sessionId + ")");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                plugin.getLogger().warning("Error sending download to " + player.getName() + ": " + e.getMessage());
            }
        });
    }

    /**
     * 處理取消傳輸。
     */
    private void handleCancel(ProxiedPlayer player, ByteArrayDataInput in) {
        String uuid = in.readUTF();
        clipboardManager.setPlayerTransferring(UUID.fromString(uuid), false);
        plugin.getLogger().info("Transfer cancelled for " + player.getName());
    }
}
