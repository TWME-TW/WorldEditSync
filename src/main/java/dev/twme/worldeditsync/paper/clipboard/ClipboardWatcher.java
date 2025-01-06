package dev.twme.worldeditsync.paper.clipboard;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import dev.twme.worldeditsync.paper.WorldEditSyncPaper;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class ClipboardWatcher extends BukkitRunnable {
    private final WorldEditSyncPaper plugin;
    private final ClipboardManager clipboardManager;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public ClipboardWatcher(WorldEditSyncPaper plugin) {
        this.plugin = plugin;
        this.clipboardManager = plugin.getClipboardManager();
    }

    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {


            if (!(player.hasPermission("worldeditsync.sync"))) {
                continue;
            }
            if (!clipboardManager.isChecked(player.getUniqueId())) {
                continue;
            }

            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> checkAndUploadPlayerClipboard(player));
        }
    }

    private void checkAndUploadPlayerClipboard(Player player) {
        if (plugin.getClipboardManager().isPlayerTransferring(player.getUniqueId())) return;

        Clipboard clipboard = plugin.getWorldEditHelper().getPlayerClipboard(player);
        if (clipboard == null) {
            return;
        }

        if (!clipboardManager.hasClipboardChanged(player, clipboard)) {
            return;
        }
        plugin.getClipboardManager().setPlayerTransferring(player.getUniqueId(), true);

        player.sendActionBar(mm.deserialize("<green>Detect clipboard change, uploading...</green>"));
        // 序列化並上傳


        // if (plugin.getClipboardManager().isPlayerTransferring(player.getUniqueId())) return;

        byte[] serializedClipboard = plugin.getWorldEditHelper().serializeClipboard(clipboard);

        String hash = clipboardManager.calculateClipboardHash(clipboard);
        clipboardManager.setLocalClipboard(player.getUniqueId(), serializedClipboard, hash);
        clipboardManager.uploadClipboard(player, serializedClipboard);

    }
}