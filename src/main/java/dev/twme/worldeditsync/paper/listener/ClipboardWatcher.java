package dev.twme.worldeditsync.paper.listener;

import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import com.sk89q.worldedit.extent.clipboard.Clipboard;

import dev.twme.worldeditsync.common.config.TransferConfig;
import dev.twme.worldeditsync.common.util.HashUtil;
import dev.twme.worldeditsync.paper.clipboard.ClipboardManager;
import dev.twme.worldeditsync.paper.clipboard.ClipboardSerializer;
import dev.twme.worldeditsync.paper.sync.SyncEngine;

/**
 * Periodically polls online players' WorldEdit clipboards to detect changes.
 * Acts as a fallback detection mechanism in Proxy mode.
 * In S3 mode, S3SyncEngine handles its own polling.
 */
public class ClipboardWatcher extends BukkitRunnable {

    private final JavaPlugin plugin;
    private final ClipboardManager clipboardManager;
    private final ClipboardSerializer clipboardSerializer;
    private final SyncEngine syncEngine;
    private final TransferConfig transferConfig;
    private final Logger logger;

    public ClipboardWatcher(JavaPlugin plugin, ClipboardManager clipboardManager,
                            ClipboardSerializer clipboardSerializer, SyncEngine syncEngine,
                            TransferConfig transferConfig) {
        this.plugin = plugin;
        this.clipboardManager = clipboardManager;
        this.clipboardSerializer = clipboardSerializer;
        this.syncEngine = syncEngine;
        this.transferConfig = transferConfig;
        this.logger = plugin.getLogger();
    }

    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.hasPermission("worldeditsync.sync")) continue;
            if (!clipboardManager.isTracked(player.getUniqueId())) continue;
            if (!clipboardManager.isIdle(player.getUniqueId())) continue;

            detectAndUpload(player);
        }
    }

    private void detectAndUpload(Player player) {
        UUID playerId = player.getUniqueId();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Clipboard clipboard = clipboardSerializer.getPlayerClipboard(player);
                if (clipboard == null) return;

                byte[] serialized = clipboardSerializer.serialize(clipboard);
                String hash = HashUtil.sha256Hex(serialized);
                String localHash = clipboardManager.getLocalHash(playerId);

                if (hash.equals(localHash)) return;

                // Clipboard changed
                clipboardManager.setLocalHash(playerId, hash);
                syncEngine.uploadClipboard(player, serialized, hash);
            } catch (Exception e) {
                logger.warning("Clipboard detection error for " + player.getName() + ": " + e.getMessage());
            }
        });
    }
}
