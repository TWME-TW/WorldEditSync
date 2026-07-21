package dev.twme.worldeditsync.paper.listener;

import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final Semaphore serializationSlots = new Semaphore(2);
    private final AtomicBoolean scanPending = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
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
        running.set(true);
        watcherTask = SchedulerUtil.runAtFixedRateAsync(
                plugin, this::run, initialDelayTicks, periodTicks);
    }

    public void cancel() {
        running.set(false);
        SchedulerUtil.cancelTask(watcherTask);
    }

    public void run() {
        if (!running.get() || !scanPending.compareAndSet(false, true)) {
            return;
        }
        SchedulerUtil.runOnGlobalThread(plugin, () -> {
            try {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    SchedulerUtil.runOnEntityThread(plugin, player, () -> detectAndUpload(player));
                }
            } finally {
                scanPending.set(false);
            }
        });
    }

    private void detectAndUpload(Player player) {
        UUID playerId = player.getUniqueId();
        if (!player.isOnline()
                || !player.hasPermission("worldeditsync.sync")
                || !clipboardManager.isTracked(playerId)) {
            return;
        }

        if (!clipboardManager.compareAndSetState(playerId, SyncState.IDLE, SyncState.CHECKING)) {
            return;
        }
        Object playerToken = clipboardManager.getPlayerToken(playerId);

        try {
            Clipboard clipboard = clipboardSerializer.getPlayerClipboard(player);
            if (clipboard == null) {
                clipboardManager.forceSetState(playerId, SyncState.IDLE);
                return;
            }
            String playerName = player.getName();
            Object task = SchedulerUtil.runDelayedOnEntityThread(
                    plugin,
                    player,
                    () -> beginSerializationIfStable(
                            player, playerId, playerToken, playerName, clipboard),
                    1L);
            if (task == null) {
                resetCheck(playerId, playerToken);
            }
        } catch (Exception e) {
            logger.warning("Clipboard detection error for " + player.getName() + ": " + e.getMessage());
            clipboardManager.forceSetState(playerId, SyncState.IDLE);
        }
    }

    private void beginSerializationIfStable(Player player, UUID playerId, Object playerToken,
                                            String playerName, Clipboard expectedClipboard) {
        if (!isActiveCheck(player, playerId, playerToken)
                || clipboardSerializer.getPlayerClipboard(player) != expectedClipboard) {
            resetCheck(playerId, playerToken);
            return;
        }
        if (!serializationSlots.tryAcquire()) {
            resetCheck(playerId, playerToken);
            return;
        }

        try {
            SchedulerUtil.runAsync(plugin,
                    () -> serializeClipboard(
                            player, playerId, playerToken, playerName, expectedClipboard));
        } catch (RuntimeException e) {
            serializationSlots.release();
            resetCheck(playerId, playerToken);
            throw e;
        }
    }

    private void serializeClipboard(Player player, UUID playerId, Object playerToken,
                                    String playerName, Clipboard clipboard) {
        try {
            byte[] serialized = clipboardSerializer.serialize(clipboard);
            String hash = HashUtil.sha256Hex(serialized);
            scheduleEntityContinuation(player, playerId, playerToken,
                    () -> publishIfCurrent(
                            player, playerId, playerToken, clipboard, serialized, hash));
        } catch (Exception e) {
            scheduleEntityContinuation(player, playerId, playerToken,
                    () -> handleSerializationFailure(
                            player, playerId, playerToken, playerName, clipboard, e));
        } finally {
            serializationSlots.release();
        }
    }

    private void publishIfCurrent(Player player, UUID playerId, Object playerToken,
                                  Clipboard expectedClipboard, byte[] serialized, String hash) {
        if (!isActiveCheck(player, playerId, playerToken)
                || clipboardSerializer.getPlayerClipboard(player) != expectedClipboard) {
            resetCheck(playerId, playerToken);
            return;
        }
        if (hash.equals(clipboardManager.getLocalHash(playerId))) {
            resetCheck(playerId, playerToken);
            return;
        }

        try {
            // Encryption and upload preparation can be proportional to clipboard size.
            SchedulerUtil.runAsync(plugin, () -> syncEngine.uploadClipboard(player, serialized, hash));
        } catch (RuntimeException e) {
            resetCheck(playerId, playerToken);
            throw e;
        }
    }

    private void handleSerializationFailure(Player player, UUID playerId, Object playerToken,
                                            String playerName, Clipboard expectedClipboard,
                                            Exception exception) {
        boolean clipboardWasReplaced = clipboardSerializer.getPlayerClipboard(player)
                != expectedClipboard;
        if (!clipboardWasReplaced && clipboardManager.isCurrentPlayerToken(playerId, playerToken)) {
            logger.warning("Clipboard detection error for " + playerName + ": "
                    + exception.getMessage());
        }
        resetCheck(playerId, playerToken);
    }

    private boolean isActiveCheck(Player player, UUID playerId, Object playerToken) {
        return running.get()
                && player.isOnline()
                && clipboardManager.isCurrentPlayerToken(playerId, playerToken)
                && clipboardManager.getState(playerId) == SyncState.CHECKING;
    }

    private void resetCheck(UUID playerId, Object playerToken) {
        if (clipboardManager.isCurrentPlayerToken(playerId, playerToken)
                && clipboardManager.getState(playerId) == SyncState.CHECKING) {
            clipboardManager.forceSetState(playerId, SyncState.IDLE);
        }
    }

    private void scheduleEntityContinuation(Player player, UUID playerId, Object playerToken,
                                            Runnable continuation) {
        try {
            if (SchedulerUtil.runOnEntityThread(plugin, player, continuation) == null) {
                resetCheck(playerId, playerToken);
            }
        } catch (RuntimeException e) {
            resetCheck(playerId, playerToken);
        }
    }
}
