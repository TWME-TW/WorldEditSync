package dev.twme.worldeditsync.paper.sync;

import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.sk89q.worldedit.extent.clipboard.Clipboard;

import dev.twme.worldeditsync.common.config.TransferConfig;
import dev.twme.worldeditsync.common.model.SyncState;
import dev.twme.worldeditsync.common.util.HashUtil;
import dev.twme.worldeditsync.paper.clipboard.ClipboardManager;
import dev.twme.worldeditsync.paper.clipboard.ClipboardSerializer;
import dev.twme.worldeditsync.paper.s3.S3StorageManager;
import dev.twme.worldeditsync.paper.util.SchedulerUtil;

/**
 * S3-mode sync engine: uploads/downloads clipboards via S3-compatible storage.
 * Runs a periodic watcher to detect local changes and check for remote updates.
 */
public class S3SyncEngine implements SyncEngine {

    private final JavaPlugin plugin;
    private final ClipboardManager clipboardManager;
    private final ClipboardSerializer clipboardSerializer;
    private final S3StorageManager s3;
    private final TransferConfig transferConfig;
    private final int checkIntervalTicks;
    private final Logger logger;

    private Object watcherTask;

    public S3SyncEngine(JavaPlugin plugin, ClipboardManager clipboardManager,
                        ClipboardSerializer clipboardSerializer, S3StorageManager s3,
                        TransferConfig transferConfig, int checkIntervalTicks) {
        this.plugin = plugin;
        this.clipboardManager = clipboardManager;
        this.clipboardSerializer = clipboardSerializer;
        this.s3 = s3;
        this.transferConfig = transferConfig;
        this.checkIntervalTicks = checkIntervalTicks;
        this.logger = plugin.getLogger();
    }

    @Override
    public void start() {
        if (!s3.initialize()) {
            logger.severe("Failed to initialize S3. S3 sync engine will not start.");
            return;
        }
        watcherTask = SchedulerUtil.runAtFixedRateAsync(
                plugin,
                this::checkAllPlayers,
                transferConfig.getWatcherInitialDelayTicks(),
                checkIntervalTicks);
        logger.info("S3 sync engine started (check interval: " + checkIntervalTicks + " ticks)");
    }

    @Override
    public void shutdown() {
        SchedulerUtil.cancelTask(watcherTask);
        logger.info("S3 sync engine shut down.");
    }

    @Override
    public void uploadClipboard(Player player, byte[] data, String hash) {
        UUID playerId = player.getUniqueId();

        if (!clipboardManager.compareAndSetState(playerId, SyncState.IDLE, SyncState.UPLOADING)) {
            return;
        }

        if (data.length > transferConfig.getMaxClipboardSize()) {
            logger.warning("Clipboard too large for " + player.getName() + ": " + data.length + " bytes");
            clipboardManager.forceSetState(playerId, SyncState.IDLE);
            return;
        }

        SchedulerUtil.runAsync(plugin, () -> {
            try {
                boolean success = s3.uploadClipboard(playerId.toString(), data, hash);
                if (success) {
                    clipboardManager.setLocalHash(playerId, hash);
                    logger.fine("Uploaded clipboard to S3 for " + player.getName());
                }
            } catch (Exception e) {
                logger.severe("S3 upload failed for " + player.getName() + ": " + e.getMessage());
            } finally {
                clipboardManager.forceSetState(playerId, SyncState.IDLE);
            }
        });
    }

    @Override
    public void onPlayerJoinServer(Player player) {
        // S3 mode has no proxy to send SYNC_HASH, so we start IDLE immediately.
        // syncPlayer() will perform a remote-hash check before allowing uploads.
        clipboardManager.initPlayer(player.getUniqueId(), SyncState.IDLE);
    }

    @Override
    public void onPlayerQuit(Player player) {
        clipboardManager.removePlayer(player.getUniqueId());
    }

    private void checkAllPlayers() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.hasPermission("worldeditsync.sync")) continue;
            syncPlayer(player);
        }
    }

    private void syncPlayer(Player player) {
        UUID playerId = player.getUniqueId();

        if (!clipboardManager.isTracked(playerId)) {
            clipboardManager.initPlayer(playerId);
        }

        if (!clipboardManager.isIdle(playerId)) return;

        try {
            // Check local clipboard for changes
            Clipboard clipboard = clipboardSerializer.getPlayerClipboard(player);
            if (clipboard == null) {
                // No local clipboard: check if remote has data
                checkAndDownloadFromS3(player);
                return;
            }

            byte[] serialized = clipboardSerializer.serialize(clipboard);
            String hash = HashUtil.sha256Hex(serialized);
            String localHash = clipboardManager.getLocalHash(playerId);

            // On first observation, prefer an existing remote clipboard over stale
            // session data that WorldEdit may still hold during a server switch.
            if (localHash == null && !s3.getRemoteHash(playerId.toString()).isEmpty()) {
                checkAndDownloadFromS3(player);
            } else if (!hash.equals(localHash)) {
                uploadClipboard(player, serialized, hash);
            } else {
                // Local unchanged: check remote for updates
                checkAndDownloadFromS3(player);
            }
        } catch (Exception e) {
            logger.warning("S3 sync error for " + player.getName() + ": " + e.getMessage());
        }
    }

    private void checkAndDownloadFromS3(Player player) {
        UUID playerId = player.getUniqueId();
        String localHash = clipboardManager.getLocalHash(playerId);

        String remoteHash = s3.getRemoteHash(playerId.toString());
        if (remoteHash.isEmpty() || remoteHash.equals(localHash)) {
            return;
        }

        if (!clipboardManager.compareAndSetState(playerId, SyncState.IDLE, SyncState.DOWNLOADING)) {
            return;
        }

        try {
            byte[] data = s3.downloadClipboard(playerId.toString());
            if (data == null) {
                clipboardManager.forceSetState(playerId, SyncState.IDLE);
                return;
            }

            Clipboard clipboard = clipboardSerializer.deserialize(data);
            String actualHash = HashUtil.sha256Hex(data);

            SchedulerUtil.runOnEntityThread(plugin, player, () -> {
                if (player.isOnline()) {
                    clipboardSerializer.setPlayerClipboard(player, clipboard);
                    clipboardManager.setLocalHash(playerId, actualHash);
                    logger.info("Clipboard synced from S3 for " + player.getName());
                }
                clipboardManager.forceSetState(playerId, SyncState.IDLE);
            });
        } catch (Exception e) {
            logger.severe("S3 download failed for " + player.getName() + ": " + e.getMessage());
            clipboardManager.forceSetState(playerId, SyncState.IDLE);
        }
    }
}
