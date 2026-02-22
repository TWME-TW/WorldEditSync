package dev.twme.worldeditsync.paper.pluginmessage;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.transfer.SyncState;
import dev.twme.worldeditsync.common.transfer.TransferProtocol;
import dev.twme.worldeditsync.common.transfer.TransferSession;
import dev.twme.worldeditsync.paper.WorldEditSyncPaper;
import dev.twme.worldeditsync.paper.clipboard.ClipboardManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

/**
 * 處理從 Proxy 接收的 Plugin Message。
 *
 * 支援的訊息類型：
 * - Sync:HashCheck  → 比對 hash，決定是否需要下載
 * - Sync:NoData     → Proxy 無資料，啟用監控
 * - Download:Begin  → 開始接收下載
 * - Download:Chunk  → 接收下載的 chunk
 * - Sync:Cancel     → 取消當前傳輸
 */
public class PluginMessageHandler implements PluginMessageListener {
    private final WorldEditSyncPaper plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public PluginMessageHandler(WorldEditSyncPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (!channel.equals(Constants.CHANNEL)) return;

        try {
            // 解密訊息
            byte[] decrypted;
            try {
                decrypted = plugin.getMessageCipher().decrypt(message);
            } catch (SecurityException e) {
                plugin.getLogger().warning("Received invalid/unauthorized message from " + player.getName() + ": " + e.getMessage());
                return;
            }

            ByteArrayDataInput in = ByteStreams.newDataInput(decrypted);
            String subChannel = in.readUTF();

            switch (subChannel) {
                case TransferProtocol.HASH_CHECK -> handleHashCheck(player, in);
                case TransferProtocol.NO_DATA -> handleNoData(player, in);
                case TransferProtocol.DOWNLOAD_BEGIN -> handleDownloadBegin(player, in);
                case TransferProtocol.DOWNLOAD_CHUNK -> handleDownloadChunk(player, in);
                case TransferProtocol.CANCEL -> handleCancel(player, in);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error handling plugin message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 處理 Proxy 傳來的 hash 檢查。
     * 比對本地 hash，若不同則請求下載。
     */
    private void handleHashCheck(Player player, ByteArrayDataInput in) {
        String uuid = in.readUTF();
        if (!uuid.equals(player.getUniqueId().toString())) return;

        String remoteHash = in.readUTF();
        String localHash = plugin.getClipboardManager().getLocalHash(player.getUniqueId());

        if (remoteHash.equals(localHash) && !localHash.isEmpty()) {
            // Hash 相同，無需下載，啟用監控
            plugin.getClipboardManager().setState(player.getUniqueId(), SyncState.IDLE);
            plugin.getLogger().info("Hash match for " + player.getName() + ", no sync needed");
        } else {
            // Hash 不同或本地無資料，請求下載
            plugin.getLogger().info("Hash mismatch for " + player.getName() + ", requesting download");
            plugin.getClipboardManager().setState(player.getUniqueId(), SyncState.DOWNLOADING);
            player.sendPluginMessage(plugin, Constants.CHANNEL,
                    plugin.getMessageCipher().encrypt(TransferProtocol.createDownloadRequest(uuid)));
        }
    }

    /**
     * 處理 Proxy 回報無剪貼簿資料。
     * 啟用監控，讓本地變更可以上傳。
     */
    private void handleNoData(Player player, ByteArrayDataInput in) {
        String uuid = in.readUTF();
        if (!uuid.equals(player.getUniqueId().toString())) return;

        plugin.getClipboardManager().setState(player.getUniqueId(), SyncState.IDLE);
        plugin.getLogger().info("No clipboard data on proxy for " + player.getName());
    }

    /**
     * 處理下載開始訊息，建立下載會話。
     */
    private void handleDownloadBegin(Player player, ByteArrayDataInput in) {
        String uuid = in.readUTF();
        if (!uuid.equals(player.getUniqueId().toString())) return;

        String sessionId = in.readUTF();
        int totalChunks = in.readInt();
        int totalBytes = in.readInt();
        String hash = in.readUTF();

        plugin.getClipboardManager().setState(player.getUniqueId(), SyncState.DOWNLOADING);
        plugin.getClipboardManager().createDownloadSession(sessionId, player.getUniqueId(), totalChunks, totalBytes, hash);

        plugin.getLogger().info("Download started for " + player.getName()
                + " (session: " + sessionId + ", chunks: " + totalChunks + ", bytes: " + totalBytes + ")");
    }

    /**
     * 處理接收到的下載 chunk，完成時組裝並套用剪貼簿。
     */
    private void handleDownloadChunk(Player player, ByteArrayDataInput in) {
        String sessionId = in.readUTF();
        int chunkIndex = in.readInt();
        int length = in.readInt();

        if (length <= 0 || length > Constants.CHUNK_SIZE + 1024) {
            plugin.getLogger().warning("Invalid chunk size: " + length);
            return;
        }

        byte[] chunkData = new byte[length];
        in.readFully(chunkData);

        ClipboardManager cm = plugin.getClipboardManager();
        TransferSession session = cm.getDownloadSession(sessionId);

        if (session == null) {
            plugin.getLogger().warning("No active download session: " + sessionId);
            return;
        }

        if (!session.getPlayerUuid().equals(player.getUniqueId())) return;

        session.addChunk(chunkIndex, chunkData);

        // 進度回饋（每 10% 顯示一次）
        int received = session.getReceivedChunks();
        int total = session.getTotalChunks();
        if (total > 10 && received % Math.max(1, total / 10) == 0) {
            player.sendActionBar(mm.deserialize(
                    "<blue>Downloading clipboard... <gray>(" + received + "/" + total + ")</gray></blue>"));
        }

        // 檢查是否完成
        if (session.isComplete()) {
            completeDownload(player, session);
        }
    }

    /**
     * 完成下載：組裝資料、驗證 hash、反序列化並套用至 WorldEdit。
     */
    private void completeDownload(Player player, TransferSession session) {
        ClipboardManager cm = plugin.getClipboardManager();

        try {
            byte[] fullData = session.assembleData();
            cm.removeDownloadSession(session.getSessionId());

            if (fullData == null) {
                plugin.getLogger().warning("Failed to assemble download data for session: " + session.getSessionId());
                cm.setState(player.getUniqueId(), SyncState.IDLE);
                return;
            }

            // 驗證 hash
            String computedHash = ClipboardManager.computeHash(fullData);
            if (!computedHash.equals(session.getExpectedHash())) {
                plugin.getLogger().warning("Hash mismatch after download for " + player.getName()
                        + ". Expected: " + session.getExpectedHash() + ", Got: " + computedHash);
            }

            // 反序列化並套用
            Clipboard clipboard = plugin.getWorldEditHelper().deserializeClipboard(fullData);
            if (clipboard == null) {
                plugin.getLogger().warning("Failed to deserialize clipboard for " + player.getName());
                cm.setState(player.getUniqueId(), SyncState.IDLE);
                return;
            }

            plugin.getWorldEditHelper().setPlayerClipboard(player, clipboard);
            cm.updateLocalCache(player.getUniqueId(), fullData, computedHash);
            cm.setState(player.getUniqueId(), SyncState.IDLE);

            player.sendActionBar(mm.deserialize("<green>Clipboard synchronized!</green>"));
            plugin.getLogger().info("Download complete for " + player.getName() + " (" + fullData.length + " bytes)");

        } catch (Exception e) {
            plugin.getLogger().severe("Error completing download for " + player.getName() + ": " + e.getMessage());
            cm.setState(player.getUniqueId(), SyncState.IDLE);
        }
    }

    /**
     * 處理取消傳輸。
     */
    private void handleCancel(Player player, ByteArrayDataInput in) {
        String uuid = in.readUTF();
        if (!uuid.equals(player.getUniqueId().toString())) return;

        plugin.getClipboardManager().setState(player.getUniqueId(), SyncState.IDLE);
        plugin.getLogger().info("Transfer cancelled for " + player.getName());
    }
}
