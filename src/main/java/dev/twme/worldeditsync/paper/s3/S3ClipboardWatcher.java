package dev.twme.worldeditsync.paper.s3;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.transfer.SyncState;
import dev.twme.worldeditsync.paper.WorldEditSyncPaper;
import dev.twme.worldeditsync.paper.clipboard.ClipboardManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.UUID;

/**
 * S3 模式的剪貼簿同步偵測器。
 * 定期檢查所有線上玩家的 WorldEdit 剪貼簿，當偵測到變更時上傳至 S3。
 * 同時也會檢查 S3 上是否有其他伺服器上傳的新版本。
 *
 * 偵測邏輯：
 * 1. 只檢查狀態為 IDLE 且有 worldeditsync.sync 權限的玩家
 * 2. 序列化當前 WorldEdit 剪貼簿
 * 3. 計算 SHA-256 hash 並與本地快取比對
 * 4. 若本地有變更 → 上傳至 S3
 * 5. 若本地無變更 → 檢查 S3 上的 hash，若不同則下載
 */
public class S3ClipboardWatcher extends BukkitRunnable {
    private final WorldEditSyncPaper plugin;
    private final S3StorageManager s3;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public S3ClipboardWatcher(WorldEditSyncPaper plugin, S3StorageManager s3) {
        this.plugin = plugin;
        this.s3 = s3;
    }

    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.hasPermission("worldeditsync.sync")) continue;
            if (!plugin.getClipboardManager().isIdle(player.getUniqueId())) continue;

            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> syncPlayer(player));
        }
    }

    /**
     * 對單一玩家執行同步：偵測本地變更 → 上傳，或偵測遠端變更 → 下載。
     */
    private void syncPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        ClipboardManager cm = plugin.getClipboardManager();

        // 雙重檢查狀態
        if (!cm.isIdle(uuid)) return;
        if (!player.isOnline()) return;

        // 取得當前 WorldEdit 剪貼簿
        Clipboard clipboard = plugin.getWorldEditHelper().getPlayerClipboard(player);

        // 序列化
        byte[] serialized = null;
        String currentHash = "";
        if (clipboard != null) {
            serialized = plugin.getWorldEditHelper().serializeClipboard(clipboard);
            if (serialized != null) {
                currentHash = ClipboardManager.computeHash(serialized);
            }
        }

        String storedHash = cm.getLocalHash(uuid);

        // 本地剪貼簿有變更 → 上傳至 S3
        if (serialized != null && !currentHash.isEmpty() && !currentHash.equals(storedHash)) {
            uploadToS3(player, serialized, currentHash);
            return;
        }

        // 本地無變更 → 檢查 S3 上是否有新版本
        checkAndDownloadFromS3(player, currentHash);
    }

    /**
     * 上傳剪貼簿到 S3。
     */
    private void uploadToS3(Player player, byte[] data, String hash) {
        UUID uuid = player.getUniqueId();
        ClipboardManager cm = plugin.getClipboardManager();

        // 檢查大小限制
        if (data.length > Constants.MAX_CLIPBOARD_SIZE) {
            plugin.getLogger().warning("Clipboard too large for " + player.getName() + ": " + data.length + " bytes");
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    player.sendActionBar(mm.deserialize("<red>Clipboard too large to sync!</red>")));
            return;
        }

        cm.setState(uuid, SyncState.UPLOADING);
        cm.updateLocalCache(uuid, data, hash);

        plugin.getServer().getScheduler().runTask(plugin, () ->
                player.sendActionBar(mm.deserialize("<green>Clipboard change detected, syncing to S3...</green>")));
        plugin.getLogger().info("Clipboard changed for " + player.getName() + ", uploading to S3 (" + data.length + " bytes)");

        try {
            s3.uploadClipboard(uuid, data, hash);

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.sendActionBar(mm.deserialize("<green>Clipboard synced to S3!</green>"));
                }
            });
            plugin.getLogger().info("S3 upload complete for " + player.getName());
        } catch (Exception e) {
            plugin.getLogger().severe("Error uploading clipboard to S3 for " + player.getName() + ": " + e.getMessage());
        } finally {
            cm.setState(uuid, SyncState.IDLE);
        }
    }

    /**
     * 檢查 S3 上是否有新版本的剪貼簿，若有則下載並套用。
     */
    private void checkAndDownloadFromS3(Player player, String localHash) {
        UUID uuid = player.getUniqueId();
        ClipboardManager cm = plugin.getClipboardManager();

        try {
            String remoteHash = s3.getRemoteHash(uuid);

            // 無遠端資料或 hash 相同，不需下載
            if (remoteHash.isEmpty() || remoteHash.equals(localHash)) return;

            // 遠端有新版本，開始下載
            cm.setState(uuid, SyncState.DOWNLOADING);
            plugin.getLogger().info("S3 has newer clipboard for " + player.getName() + ", downloading...");

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.sendActionBar(mm.deserialize("<blue>Downloading clipboard from S3...</blue>"));
                }
            });

            byte[] data = s3.downloadClipboard(uuid);
            if (data == null) {
                plugin.getLogger().warning("Failed to download clipboard from S3 for " + player.getName());
                cm.setState(uuid, SyncState.IDLE);
                return;
            }

            // 驗證 hash
            String computedHash = ClipboardManager.computeHash(data);
            if (!computedHash.equals(remoteHash)) {
                plugin.getLogger().warning("Hash mismatch after S3 download for " + player.getName()
                        + ". Expected: " + remoteHash + ", Got: " + computedHash);
            }

            // 反序列化並套用
            Clipboard clipboard = plugin.getWorldEditHelper().deserializeClipboard(data);
            if (clipboard == null) {
                plugin.getLogger().warning("Failed to deserialize clipboard from S3 for " + player.getName());
                cm.setState(uuid, SyncState.IDLE);
                return;
            }

            // 回到主執行緒套用剪貼簿
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                plugin.getWorldEditHelper().setPlayerClipboard(player, clipboard);
                cm.updateLocalCache(uuid, data, computedHash);
                cm.setState(uuid, SyncState.IDLE);

                if (player.isOnline()) {
                    player.sendActionBar(mm.deserialize("<green>Clipboard synchronized from S3!</green>"));
                }
            });

            plugin.getLogger().info("S3 download complete for " + player.getName() + " (" + data.length + " bytes)");

        } catch (Exception e) {
            plugin.getLogger().warning("Error checking S3 for " + player.getName() + ": " + e.getMessage());
            cm.setState(uuid, SyncState.IDLE);
        }
    }
}
