package dev.twme.worldeditsync.paper.clipboard;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.transfer.SyncState;
import dev.twme.worldeditsync.common.transfer.TransferProtocol;
import dev.twme.worldeditsync.paper.WorldEditSyncPaper;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.UUID;

/**
 * 剪貼簿變更偵測器。
 * 定期檢查所有線上玩家的 WorldEdit 剪貼簿是否有變更。
 *
 * 偵測邏輯：
 * 1. 只檢查狀態為 IDLE 且有 worldeditsync.sync 權限的玩家
 * 2. 序列化當前 WorldEdit 剪貼簿
 * 3. 計算 SHA-256 hash 並與快取比對
 * 4. 若不同，觸發上傳至 Proxy
 */
public class ClipboardWatcher extends BukkitRunnable {
    private final WorldEditSyncPaper plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public ClipboardWatcher(WorldEditSyncPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.hasPermission("worldeditsync.sync")) continue;
            if (!plugin.getClipboardManager().isIdle(player.getUniqueId())) continue;

            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> detectAndUpload(player));
        }
    }

    /**
     * 偵測剪貼簿變更並觸發上傳。
     */
    private void detectAndUpload(Player player) {
        UUID uuid = player.getUniqueId();
        ClipboardManager cm = plugin.getClipboardManager();

        // 雙重檢查狀態（排程後可能已改變）
        if (!cm.isIdle(uuid)) return;
        if (!player.isOnline()) return;

        // 取得當前 WorldEdit 剪貼簿
        Clipboard clipboard = plugin.getWorldEditHelper().getPlayerClipboard(player);
        if (clipboard == null) return;

        // 序列化
        byte[] serialized = plugin.getWorldEditHelper().serializeClipboard(clipboard);
        if (serialized == null) return;

        // 計算 SHA-256 hash 並比對
        String currentHash = ClipboardManager.computeHash(serialized);
        String storedHash = cm.getLocalHash(uuid);

        if (currentHash.equals(storedHash)) return; // 未變更

        // 剪貼簿已變更，開始上傳
        cm.setState(uuid, SyncState.UPLOADING);
        cm.updateLocalCache(uuid, serialized, currentHash);

        player.sendActionBar(mm.deserialize("<green>Clipboard change detected, syncing...</green>"));
        plugin.getLogger().info("Clipboard changed for " + player.getName() + ", uploading (" + serialized.length + " bytes)");

        uploadClipboard(player, serialized, currentHash);
    }

    /**
     * 將剪貼簿資料分塊上傳至 Proxy。
     */
    private void uploadClipboard(Player player, byte[] data, String hash) {
        UUID uuid = player.getUniqueId();
        ClipboardManager cm = plugin.getClipboardManager();

        // 檢查大小限制
        if (data.length > Constants.MAX_CLIPBOARD_SIZE) {
            plugin.getLogger().warning("Clipboard too large for " + player.getName() + ": " + data.length + " bytes");
            player.sendActionBar(mm.deserialize("<red>Clipboard too large to sync!</red>"));
            cm.setState(uuid, SyncState.IDLE);
            return;
        }

        String sessionId = UUID.randomUUID().toString();
        int totalChunks = (int) Math.ceil((double) data.length / Constants.CHUNK_SIZE);

        // 發送上傳開始訊息
        player.sendPluginMessage(plugin, Constants.CHANNEL,
                plugin.getMessageCipher().encrypt(
                        TransferProtocol.createUploadBegin(uuid.toString(), sessionId, totalChunks, data.length, hash)));

        // 非同步發送所有 chunk
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                for (int i = 0; i < totalChunks; i++) {
                    if (!player.isOnline()) break;
                    if (cm.getState(uuid) != SyncState.UPLOADING) break;

                    int offset = i * Constants.CHUNK_SIZE;
                    int length = Math.min(Constants.CHUNK_SIZE, data.length - offset);
                    byte[] chunk = new byte[length];
                    System.arraycopy(data, offset, chunk, 0, length);

                    player.sendPluginMessage(plugin, Constants.CHANNEL,
                            plugin.getMessageCipher().encrypt(
                                    TransferProtocol.createUploadChunk(sessionId, i, chunk)));

                    if (i < totalChunks - 1) {
                        Thread.sleep(Constants.CHUNK_SEND_DELAY_MS);
                    }
                }

                // 回到主執行緒更新狀態
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (cm.getState(uuid) == SyncState.UPLOADING) {
                        cm.setState(uuid, SyncState.IDLE);
                        player.sendActionBar(mm.deserialize("<green>Clipboard synced!</green>"));
                        plugin.getLogger().info("Upload complete for " + player.getName() + " (session: " + sessionId + ")");
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cm.setState(uuid, SyncState.IDLE);
            } catch (Exception e) {
                plugin.getLogger().severe("Error uploading clipboard for " + player.getName() + ": " + e.getMessage());
                cm.setState(uuid, SyncState.IDLE);
            }
        });
    }
}
