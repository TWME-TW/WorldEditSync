package dev.twme.worldeditsync.paper.clipboard;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import dev.twme.worldeditsync.paper.WorldEditSyncPaper;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class ClipboardWatcher extends BukkitRunnable {
    private final WorldEditSyncPaper plugin;
    private final ClipboardManager clipboardManager;

    public ClipboardWatcher(WorldEditSyncPaper plugin) {
        this.plugin = plugin;
        this.clipboardManager = plugin.getClipboardManager();
    }

    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {


            if (!(player.hasPermission("worldeditsync.sync"))) {
                plugin.getLogger().info("玩家 " + player.getName() + " 沒有權限");
                continue;
            }

            if (!plugin.getClipboardManager().isChecked(player.getUniqueId())) {
                continue;
            }

            checkAndUploadPlayerClipboard(player);
        }
    }

    private void checkAndUploadPlayerClipboard(Player player) {
        Clipboard clipboard = plugin.getWorldEditHelper().getPlayerClipboard(player);
        if (clipboard == null) {
            return;
        }

        // 使用新的判斷方法
        if (plugin.getClipboardManager().hasClipboardChanged(player, clipboard)) {
            plugin.getLogger().info("偵測到玩家 " + player.getName() + " 的剪貼簿已更新");

            // 序列化並上傳
            byte[] serializedClipboard = plugin.getWorldEditHelper().serializeClipboard(clipboard);
            if (serializedClipboard != null) {
                String hash = plugin.getClipboardManager().calculateClipboardHash(clipboard);
                plugin.getClipboardManager().setLocalClipboard(player.getUniqueId(), serializedClipboard, hash);
                plugin.getClipboardManager().uploadClipboard(player, serializedClipboard);
            }
        }
    }
}