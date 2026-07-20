package dev.twme.worldeditsync.paper.listener;

import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.sk89q.worldedit.extent.clipboard.Clipboard;

import dev.twme.worldeditsync.common.model.SyncState;
import dev.twme.worldeditsync.common.util.HashUtil;
import dev.twme.worldeditsync.paper.clipboard.ClipboardManager;
import dev.twme.worldeditsync.paper.clipboard.ClipboardSerializer;
import dev.twme.worldeditsync.paper.sync.SyncEngine;
import dev.twme.worldeditsync.paper.util.SchedulerUtil;

/**
 * Periodically polls online players' WorldEdit clipboards to detect changes.
 * Acts as a fallback detection mechanism in Proxy mode.
 * In S3 mode, S3SyncEngine handles its own polling.
 */
public class ClipboardWatcher {

    private final JavaPlugin plugin;
    private final ClipboardManager clipboardManager;
    private final ClipboardSerializer clipboardSerializer;
    private final SyncEngine syncEngine;
    private final Logger logger;
    private Object watcherTask;

    public ClipboardWatcher(JavaPlugin plugin, ClipboardManager clipboardManager,
                            ClipboardSerializer clipboardSerializer, SyncEngine syncEngine) {
        this.plugin = plugin;
        this.clipboardManager = clipboardManager;
        this.clipboardSerializer = clipboardSerializer;
        this.syncEngine = syncEngine;
        this.logger = plugin.getLogger();
    }

    public void start(long initialDelayTicks, long periodTicks) {
        watcherTask = SchedulerUtil.runAtFixedRateAsync(
                plugin, this::run, initialDelayTicks, periodTicks);
    }

    public void cancel() {
        SchedulerUtil.cancelTask(watcherTask);
    }

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

        if (!clipboardManager.compareAndSetState(playerId, SyncState.IDLE, SyncState.CHECKING)) {
            return;
        }

        try {
            Clipboard clipboard = clipboardSerializer.getPlayerClipboard(player);
            if (clipboard == null) {
                return;
            }

            byte[] serialized = clipboardSerializer.serialize(clipboard);
            String hash = HashUtil.sha256Hex(serialized);

            if (hash.equals(clipboardManager.getLocalHash(playerId))) {
                return;
            }

            clipboardManager.setLocalHash(playerId, hash);
            syncEngine.uploadClipboard(player, serialized, hash);
        } catch (Exception e) {
            logger.warning("Clipboard detection error for " + player.getName() + ": " + e.getMessage());
        } finally {
            if (clipboardManager.getState(playerId) == SyncState.CHECKING) {
                clipboardManager.forceSetState(playerId, SyncState.IDLE);
            }
        }
    }
}
