package dev.twme.worldeditsync.paper.sync;

import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.sk89q.worldedit.extent.clipboard.Clipboard;

import dev.twme.worldeditsync.common.config.TransferConfig;
import dev.twme.worldeditsync.common.model.SyncState;
import dev.twme.worldeditsync.common.protocol.ProtocolValidation;
import dev.twme.worldeditsync.common.util.HashUtil;
import dev.twme.worldeditsync.paper.clipboard.ClipboardManager;
import dev.twme.worldeditsync.paper.clipboard.ClipboardSerializer;
import dev.twme.worldeditsync.paper.s3.S3StorageManager;
import dev.twme.worldeditsync.paper.s3.S3StorageManager.RemoteObject;
import dev.twme.worldeditsync.paper.util.SchedulerUtil;
import io.minio.MinioClient;

/** Synchronizes clipboards through S3 without performing network I/O on server threads. */
public class S3SyncEngine implements SyncEngine {

    private static final long INITIALIZATION_RETRY_MS = 30_000L;
    private static final long ERROR_LOG_INTERVAL_MS = 10_000L;

    private final JavaPlugin plugin;
    private final ClipboardManager clipboardManager;
    private final ClipboardSerializer clipboardSerializer;
    private final S3StorageManager s3;
    private final TransferConfig transferConfig;
    private final int checkIntervalTicks;
    private final Logger logger;
    private final Object lifecycleLock = new Object();
    private final Semaphore workerSlots = new Semaphore(2);
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicBoolean scanPending = new AtomicBoolean();
    private final AtomicLong lastErrorLog = new AtomicLong();

    private volatile Object watcherTask;
    private volatile Object initializationTask;
    private volatile MinioClient s3Client;

    public S3SyncEngine(JavaPlugin plugin, ClipboardManager clipboardManager,
                        ClipboardSerializer clipboardSerializer, S3StorageManager s3,
                        TransferConfig transferConfig, int checkIntervalTicks) {
        this.plugin = plugin;
        this.clipboardManager = clipboardManager;
        this.clipboardSerializer = clipboardSerializer;
        this.s3 = s3;
        this.transferConfig = transferConfig;
        this.checkIntervalTicks = Math.max(1, checkIntervalTicks);
        this.logger = plugin.getLogger();
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            initializeAsync();
            logger.info("S3 sync engine is initializing asynchronously.");
        }
    }

    private void initializeAsync() {
        synchronized (lifecycleLock) {
            if (!running.get()) {
                return;
            }
            initializationTask = null;
            initializationTask = SchedulerUtil.runAsync(plugin, this::initializeConnection);
        }
    }

    private void initializeConnection() {
        MinioClient initializedClient = s3.initialize();
        boolean started = false;
        boolean retrying = false;
        synchronized (lifecycleLock) {
            if (!running.get()) {
                return;
            }
            if (ready.get()) {
                return;
            }
            if (initializedClient != null) {
                s3Client = initializedClient;
                ready.set(true);
                startWatcherLocked();
                started = true;
            } else {
                initializationTask = SchedulerUtil.runDelayedAsync(
                        plugin, this::initializeAsync, INITIALIZATION_RETRY_MS);
                retrying = true;
            }
        }
        if (started) {
            logger.info("S3 sync engine started (check interval: "
                    + checkIntervalTicks + " ticks)");
        } else if (retrying) {
            logger.warning("S3 initialization will retry in 30 seconds.");
        }
    }

    /** Must be called while holding {@link #lifecycleLock}. */
    private void startWatcherLocked() {
        if (!running.get() || watcherTask != null) {
            return;
        }
        watcherTask = SchedulerUtil.runAtFixedRateAsync(
                plugin,
                this::checkAllPlayers,
                transferConfig.getWatcherInitialDelayTicks(),
                checkIntervalTicks);
    }

    @Override
    public void shutdown() {
        Object pendingInitialization;
        Object activeWatcher;
        synchronized (lifecycleLock) {
            running.set(false);
            ready.set(false);
            s3Client = null;
            pendingInitialization = initializationTask;
            activeWatcher = watcherTask;
            initializationTask = null;
            watcherTask = null;
        }
        SchedulerUtil.cancelTask(pendingInitialization);
        SchedulerUtil.cancelTask(activeWatcher);
        logger.info("S3 sync engine shut down.");
    }

    @Override
    public void uploadClipboard(Player player, byte[] data, String hash) {
        UUID playerId = player.getUniqueId();
        Object playerToken = clipboardManager.getPlayerToken(playerId);
        MinioClient client = s3Client;
        if (!running.get() || !ready.get() || client == null
                || data.length <= 0
                || data.length > transferConfig.getMaxClipboardSize()
                || !ProtocolValidation.isSha256(hash)) {
            clipboardManager.forceSetState(playerId, SyncState.IDLE);
            return;
        }
        if (!clipboardManager.compareAndSetState(playerId, SyncState.CHECKING, SyncState.UPLOADING)
                && !clipboardManager.compareAndSetState(playerId, SyncState.IDLE, SyncState.UPLOADING)) {
            return;
        }
        if (!workerSlots.tryAcquire()) {
            clipboardManager.forceSetState(playerId, SyncState.IDLE);
            return;
        }

        String playerName = player.getName();
        try {
            SchedulerUtil.runAsync(plugin,
                    () -> uploadSerializedClipboard(
                            client, playerId, playerToken, playerName, data, hash));
        } catch (RuntimeException e) {
            finishWorker(playerId, playerToken);
            throw e;
        }
    }

    @Override
    public void onPlayerJoinServer(Player player) {
        clipboardManager.initPlayer(player.getUniqueId(), SyncState.IDLE);
    }

    @Override
    public void onPlayerQuit(Player player) {
        clipboardManager.removePlayer(player.getUniqueId());
    }

    private void checkAllPlayers() {
        if (!ready.get() || !scanPending.compareAndSet(false, true)) {
            return;
        }
        SchedulerUtil.runOnGlobalThread(plugin, () -> {
            try {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    SchedulerUtil.runOnEntityThread(plugin, player, () -> inspectPlayer(player));
                }
            } finally {
                scanPending.set(false);
            }
        });
    }

    private void inspectPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        if (!player.isOnline() || !player.hasPermission("worldeditsync.sync")) {
            return;
        }
        if (!clipboardManager.isTracked(playerId)) {
            clipboardManager.initPlayer(playerId, SyncState.IDLE);
        }
        if (!clipboardManager.compareAndSetState(playerId, SyncState.IDLE, SyncState.CHECKING)) {
            return;
        }
        Object playerToken = clipboardManager.getPlayerToken(playerId);

        try {
            Clipboard clipboard = clipboardSerializer.getPlayerClipboard(player);
            String playerName = player.getName();
            Object task = SchedulerUtil.runDelayedOnEntityThread(
                    plugin,
                    player,
                    () -> beginSynchronizationIfStable(
                            player, playerId, playerToken, playerName, clipboard),
                    1L);
            if (task == null) {
                resetCheck(playerId, playerToken);
            }
        } catch (Exception e) {
            logger.warning("Could not inspect clipboard for " + player.getName() + ": " + e.getMessage());
            resetCheck(playerId, playerToken);
        }
    }

    private void beginSynchronizationIfStable(Player player, UUID playerId, Object playerToken,
                                              String playerName, Clipboard expectedClipboard) {
        if (!isActiveCheck(player, playerId, playerToken)
                || clipboardSerializer.getPlayerClipboard(player) != expectedClipboard) {
            resetCheck(playerId, playerToken);
            return;
        }
        if (!workerSlots.tryAcquire()) {
            resetCheck(playerId, playerToken);
            return;
        }

        try {
            SchedulerUtil.runAsync(plugin,
                    () -> synchronizePlayer(
                            player, playerId, playerToken, playerName, expectedClipboard));
        } catch (RuntimeException e) {
            workerSlots.release();
            resetCheck(playerId, playerToken);
            throw e;
        }
    }

    private void synchronizePlayer(Player player, UUID playerId, Object playerToken,
                                   String playerName,
                                   Clipboard clipboard) {
        boolean callbackScheduled = false;
        try {
            if (!running.get()
                    || !player.isOnline()
                    || !clipboardManager.isCurrentPlayerToken(playerId, playerToken)) {
                return;
            }
            MinioClient client = s3Client;
            if (!ready.get() || client == null || client != s3Client) {
                return;
            }
            RemoteObject remote = s3.getRemoteObject(client, playerId.toString());
            if (remote.exists() && !ProtocolValidation.isSha256(remote.hash())) {
                throw new SecurityException("S3 clipboard hash metadata is invalid");
            }

            String localHash = clipboardManager.getLocalHash(playerId);
            if (remote.exists()
                    && (localHash == null || !remote.hash().equalsIgnoreCase(localHash))) {
                callbackScheduled = downloadRemoteClipboard(
                        client, player, playerId, playerToken, playerName, clipboard, remote);
                return;
            }

            if (clipboard == null) {
                return;
            }

            byte[] serialized;
            try {
                serialized = clipboardSerializer.serialize(clipboard);
            } catch (Exception e) {
                callbackScheduled = scheduleEntityContinuation(
                        player, playerId, playerToken,
                        () -> handleSerializationFailure(
                                player, playerId, playerToken, playerName, clipboard, e));
                return;
            }
            if (serialized.length > transferConfig.getMaxClipboardSize()) {
                logger.warning("Clipboard too large for S3 sync for " + playerName
                        + ": " + serialized.length + " bytes");
                return;
            }
            String hash = HashUtil.sha256Hex(serialized);
            if (remote.exists() && hash.equalsIgnoreCase(localHash)) {
                return;
            }

            callbackScheduled = scheduleEntityContinuation(
                    player, playerId, playerToken,
                    () -> uploadIfCurrent(
                            client, player, playerId, playerToken, playerName,
                            clipboard, serialized, hash));
        } catch (Exception e) {
            logOperationalFailure("S3 sync failed for " + playerName, e);
        } finally {
            workerSlots.release();
            if (!callbackScheduled) {
                resetSynchronization(playerId, playerToken);
            }
        }
    }

    private void uploadIfCurrent(MinioClient client, Player player, UUID playerId,
                                 Object playerToken, String playerName,
                                 Clipboard expectedClipboard, byte[] serialized, String hash) {
        if (!isActiveCheck(player, playerId, playerToken)
                || client != s3Client
                || clipboardSerializer.getPlayerClipboard(player) != expectedClipboard) {
            resetCheck(playerId, playerToken);
            return;
        }
        if (!workerSlots.tryAcquire()) {
            resetCheck(playerId, playerToken);
            return;
        }
        if (!clipboardManager.compareAndSetState(
                playerId, SyncState.CHECKING, SyncState.UPLOADING)) {
            workerSlots.release();
            return;
        }

        try {
            SchedulerUtil.runAsync(plugin,
                    () -> uploadSerializedClipboard(
                            client, playerId, playerToken, playerName, serialized, hash));
        } catch (RuntimeException e) {
            finishWorker(playerId, playerToken);
            throw e;
        }
    }

    private void uploadSerializedClipboard(MinioClient client, UUID playerId, Object playerToken,
                                           String playerName, byte[] data, String hash) {
        try {
            if (!running.get() || !ready.get() || client != s3Client
                    || !clipboardManager.isCurrentPlayerToken(playerId, playerToken)) {
                return;
            }
            s3.uploadClipboard(client, playerId.toString(), data, hash);
            if (!running.get() || client != s3Client
                    || !clipboardManager.isCurrentPlayerToken(playerId, playerToken)) {
                return;
            }
            clipboardManager.setLocalHash(playerId, hash);
            logger.fine("Uploaded clipboard to S3 for " + playerName);
        } catch (Exception e) {
            logOperationalFailure("S3 upload failed for " + playerName, e);
        } finally {
            finishWorker(playerId, playerToken);
        }
    }

    private void handleSerializationFailure(Player player, UUID playerId, Object playerToken,
                                            String playerName, Clipboard expectedClipboard,
                                            Exception exception) {
        boolean clipboardWasReplaced = clipboardSerializer.getPlayerClipboard(player)
                != expectedClipboard;
        if (!clipboardWasReplaced && clipboardManager.isCurrentPlayerToken(playerId, playerToken)) {
            logOperationalFailure("S3 clipboard serialization failed for " + playerName, exception);
        }
        resetCheck(playerId, playerToken);
    }

    private boolean downloadRemoteClipboard(MinioClient client, Player player,
                                            UUID playerId, Object playerToken,
                                            String playerName, Clipboard expectedClipboard,
                                            RemoteObject remote) throws Exception {
        if (!clipboardManager.isCurrentPlayerToken(playerId, playerToken)
                || !player.isOnline()
                || !clipboardManager.compareAndSetState(
                playerId, SyncState.CHECKING, SyncState.DOWNLOADING)) {
            return false;
        }

        byte[] data = s3.downloadClipboard(client, playerId.toString(), remote.encryptedSize());
        String actualHash = HashUtil.sha256Hex(data);
        if (!actualHash.equalsIgnoreCase(remote.hash())) {
            throw new SecurityException("S3 clipboard hash mismatch");
        }
        Clipboard downloaded = clipboardSerializer.deserialize(data);
        return scheduleEntityContinuation(player, playerId, playerToken, () -> {
            if (!running.get()
                    || !player.isOnline()
                    || !clipboardManager.isCurrentPlayerToken(playerId, playerToken)
                    || clipboardManager.getState(playerId) != SyncState.DOWNLOADING
                    || clipboardSerializer.getPlayerClipboard(player) != expectedClipboard) {
                if (clipboardManager.isCurrentPlayerToken(playerId, playerToken)
                        && clipboardManager.getState(playerId) == SyncState.DOWNLOADING) {
                    clipboardManager.forceSetState(playerId, SyncState.IDLE);
                }
                return;
            }
            try {
                clipboardSerializer.setPlayerClipboard(player, downloaded);
                clipboardManager.setLocalHash(playerId, actualHash);
                logger.info("Clipboard synced from S3 for " + playerName);
            } catch (Exception e) {
                logger.severe("Failed to apply S3 clipboard for " + playerName + ": " + e.getMessage());
            } finally {
                clipboardManager.forceSetState(playerId, SyncState.IDLE);
            }
        });
    }

    private boolean isActiveCheck(Player player, UUID playerId, Object playerToken) {
        return running.get()
                && ready.get()
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

    private void resetSynchronization(UUID playerId, Object playerToken) {
        if (!clipboardManager.isCurrentPlayerToken(playerId, playerToken)) {
            return;
        }
        SyncState state = clipboardManager.getState(playerId);
        if (state == SyncState.CHECKING
                || state == SyncState.UPLOADING
                || state == SyncState.DOWNLOADING) {
            clipboardManager.forceSetState(playerId, SyncState.IDLE);
        }
    }

    private void finishWorker(UUID playerId, Object playerToken) {
        workerSlots.release();
        resetSynchronization(playerId, playerToken);
    }

    private boolean scheduleEntityContinuation(Player player, UUID playerId, Object playerToken,
                                               Runnable continuation) {
        try {
            return SchedulerUtil.runOnEntityThread(plugin, player, continuation) != null;
        } catch (RuntimeException e) {
            resetSynchronization(playerId, playerToken);
            return false;
        }
    }

    private void logOperationalFailure(String context, Exception exception) {
        long now = System.currentTimeMillis();
        long previous = lastErrorLog.get();
        if (now - previous >= ERROR_LOG_INTERVAL_MS && lastErrorLog.compareAndSet(previous, now)) {
            logger.warning(context + ": " + exception.getMessage());
        }
    }
}
