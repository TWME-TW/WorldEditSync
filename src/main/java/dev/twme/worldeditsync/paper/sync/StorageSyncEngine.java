package dev.twme.worldeditsync.paper.sync;

import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import dev.twme.worldeditsync.common.storage.ClipboardStorage;
import dev.twme.worldeditsync.common.storage.StoredClipboard;
import dev.twme.worldeditsync.paper.ui.ActionBarProgress;
import dev.twme.worldeditsync.paper.ui.ActionBarProgress.Operation;
import dev.twme.worldeditsync.paper.ui.ActionBarProgress.ProgressHandle;
import dev.twme.worldeditsync.paper.util.SchedulerUtil;

/** Synchronizes clipboards through shared storage without network I/O on server threads. */
public class StorageSyncEngine implements SyncEngine {

    private static final long INITIALIZATION_RETRY_MS = 30_000L;
    private static final long ERROR_LOG_INTERVAL_MS = 10_000L;

    private final JavaPlugin plugin;
    private final ClipboardManager clipboardManager;
    private final ClipboardSerializer clipboardSerializer;
    private final ClipboardStorage storage;
    private final TransferConfig transferConfig;
    private final int checkIntervalTicks;
    private final ActionBarProgress actionBarProgress;
    private final Logger logger;
    private final Object lifecycleLock = new Object();
    private final Semaphore workerSlots = new Semaphore(2);
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicBoolean scanPending = new AtomicBoolean();
    private final Set<UUID> inspectionsPending = ConcurrentHashMap.newKeySet();
    private final AtomicLong lastErrorLog = new AtomicLong();

    private boolean initializationInProgress;
    private volatile Object watcherTask;
    private volatile Object initializationTask;

    public StorageSyncEngine(JavaPlugin plugin, ClipboardManager clipboardManager,
                             ClipboardSerializer clipboardSerializer, ClipboardStorage storage,
                             TransferConfig transferConfig, int checkIntervalTicks,
                             ActionBarProgress actionBarProgress) {
        this.plugin = plugin;
        this.clipboardManager = clipboardManager;
        this.clipboardSerializer = clipboardSerializer;
        this.storage = storage;
        this.transferConfig = transferConfig;
        this.checkIntervalTicks = Math.max(1, checkIntervalTicks);
        this.actionBarProgress = actionBarProgress;
        this.logger = plugin.getLogger();
        this.storage.setUpdateListener(this::onStorageUpdate);
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            initializeAsync();
            logger.info(storage.description() + " sync engine is initializing asynchronously.");
        }
    }

    private void initializeAsync() {
        synchronized (lifecycleLock) {
            if (!running.get() || ready.get() || initializationInProgress) {
                return;
            }
            initializationInProgress = true;
            try {
                initializationTask = SchedulerUtil.runAsync(plugin, this::initializeConnection);
                if (initializationTask == null) {
                    initializationInProgress = false;
                    logger.warning("Could not schedule " + storage.description()
                            + " initialization.");
                }
            } catch (RuntimeException e) {
                initializationInProgress = false;
                throw e;
            }
        }
    }

    private void initializeConnection() {
        boolean initialized = false;
        try {
            initialized = storage.initialize();
        } catch (Exception e) {
            logOperationalFailure(storage.description() + " initialization failed", e);
        }
        boolean started = false;
        boolean retrying = false;
        boolean closeStorage = false;
        synchronized (lifecycleLock) {
            initializationTask = null;
            initializationInProgress = false;
            if (!running.get()) {
                closeStorage = initialized;
            } else if (initialized) {
                ready.set(true);
                startWatcherLocked();
                started = true;
            } else {
                initializationTask = SchedulerUtil.runDelayedAsync(
                        plugin, this::initializeAsync, INITIALIZATION_RETRY_MS);
                retrying = initializationTask != null;
            }
        }
        if (closeStorage) {
            try {
                storage.close();
            } catch (Exception e) {
                logger.warning("Failed to close abandoned " + storage.description()
                        + " connection: " + e.getMessage());
            }
            return;
        }
        if (started) {
            logger.info(storage.description() + " sync engine started (check interval: "
                    + checkIntervalTicks + " ticks)");
        } else if (retrying) {
            logger.warning(storage.description() + " initialization will retry in 30 seconds.");
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
        if (watcherTask == null) {
            logger.warning("Could not schedule " + storage.description() + " clipboard watcher.");
        }
    }

    @Override
    public void shutdown() {
        Object pendingInitialization;
        Object activeWatcher;
        synchronized (lifecycleLock) {
            running.set(false);
            ready.set(false);
            pendingInitialization = initializationTask;
            activeWatcher = watcherTask;
            initializationTask = null;
            watcherTask = null;
        }
        SchedulerUtil.cancelTask(pendingInitialization);
        SchedulerUtil.cancelTask(activeWatcher);
        inspectionsPending.clear();
        try {
            storage.close();
        } catch (Exception e) {
            logger.warning("Failed to close " + storage.description() + " storage: " + e.getMessage());
        }
        logger.info(storage.description() + " sync engine shut down.");
    }

    @Override
    public void uploadClipboard(Player player, byte[] data, String hash) {
        UUID playerId = player.getUniqueId();
        Object playerToken = clipboardManager.getPlayerToken(playerId);
        if (!running.get() || !ready.get()
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
        ProgressHandle progress = actionBarProgress.begin(player, Operation.UPLOAD);
        try {
            if (SchedulerUtil.runAsync(plugin,
                    () -> uploadSerializedClipboard(
                            playerId, playerToken, playerName, data, hash, progress)) == null) {
                progress.fail();
                finishWorker(playerId, playerToken);
            }
        } catch (RuntimeException e) {
            progress.fail();
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
        inspectionsPending.remove(player.getUniqueId());
        actionBarProgress.removePlayer(player.getUniqueId());
        clipboardManager.removePlayer(player.getUniqueId());
    }

    private void checkAllPlayers() {
        if (!ready.get() || !scanPending.compareAndSet(false, true)) {
            return;
        }
        try {
            if (SchedulerUtil.runOnGlobalThread(plugin, () -> {
                try {
                    if (!running.get() || !ready.get()) {
                        return;
                    }
                    for (Player player : plugin.getServer().getOnlinePlayers()) {
                        queueInspection(player);
                    }
                } finally {
                    scanPending.set(false);
                }
            }) == null) {
                scanPending.set(false);
            }
        } catch (RuntimeException e) {
            scanPending.set(false);
        }
    }

    private void onStorageUpdate(String playerId) {
        if (!running.get() || !ready.get()) {
            return;
        }
        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(playerId);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        if (!inspectionsPending.add(playerUuid)) {
            return;
        }
        try {
            if (SchedulerUtil.runOnGlobalThread(plugin, () -> {
                if (!running.get() || !ready.get()) {
                    inspectionsPending.remove(playerUuid);
                    return;
                }
                Player player = plugin.getServer().getPlayer(playerUuid);
                if (player == null || !player.isOnline()) {
                    inspectionsPending.remove(playerUuid);
                    return;
                }
                scheduleReservedInspection(player);
            }) == null) {
                inspectionsPending.remove(playerUuid);
            }
        } catch (RuntimeException e) {
            inspectionsPending.remove(playerUuid);
        }
    }

    private void queueInspection(Player player) {
        if (!running.get() || !ready.get()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (inspectionsPending.add(playerId)) {
            scheduleReservedInspection(player);
        }
    }

    private void scheduleReservedInspection(Player player) {
        UUID playerId = player.getUniqueId();
        try {
            if (SchedulerUtil.runOnEntityThread(plugin, player, () -> {
                try {
                    inspectPlayer(player);
                } finally {
                    inspectionsPending.remove(playerId);
                }
            }) == null) {
                inspectionsPending.remove(playerId);
            }
        } catch (RuntimeException e) {
            inspectionsPending.remove(playerId);
        }
    }

    private void inspectPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        if (!running.get() || !ready.get()
                || !player.isOnline() || !player.hasPermission("worldeditsync.sync")) {
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
            if (SchedulerUtil.runAsync(plugin,
                    () -> synchronizePlayer(
                            player, playerId, playerToken, playerName, expectedClipboard)) == null) {
                workerSlots.release();
                resetCheck(playerId, playerToken);
            }
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
            if (!ready.get()) {
                return;
            }
            StoredClipboard remote = storage.inspect(playerId.toString());
            if (remote.exists() && !ProtocolValidation.isSha256(remote.hash())) {
                throw new SecurityException(storage.description() + " clipboard hash metadata is invalid");
            }

            String knownRemoteHash = clipboardManager.getRemoteHash(playerId);
            if (remote.exists()
                    && (knownRemoteHash == null
                    || !remote.hash().equalsIgnoreCase(knownRemoteHash))) {
                callbackScheduled = downloadRemoteClipboard(
                        player, playerId, playerToken, playerName, clipboard, remote);
                return;
            }

            if (clipboard == null) {
                return;
            }
            String localHash = clipboardManager.getLocalHash(playerId);
            if (remote.exists() && remote.hash().equalsIgnoreCase(knownRemoteHash)
                    && clipboardManager.isSerializedClipboard(playerId, clipboard)) {
                return;
            }

            byte[] serialized;
            try {
                serialized = clipboardSerializer.serialize(
                        clipboard, transferConfig.getMaxClipboardSize(),
                        transferConfig.getMaxClipboardBlocks());
            } catch (Exception e) {
                callbackScheduled = scheduleEntityContinuation(
                        player, playerId, playerToken,
                        () -> handleSerializationFailure(
                                player, playerId, playerToken, playerName, clipboard, e));
                return;
            }
            if (serialized.length > transferConfig.getMaxClipboardSize()) {
                logger.warning("Clipboard too large for " + storage.description() + " sync for " + playerName
                        + ": " + serialized.length + " bytes");
                return;
            }
            String hash = HashUtil.sha256Hex(serialized);
            clipboardManager.markSerializedClipboard(playerId, clipboard, hash);
            if (remote.exists() && hash.equalsIgnoreCase(localHash)) {
                return;
            }

            callbackScheduled = scheduleEntityContinuation(
                    player, playerId, playerToken,
                    () -> uploadIfCurrent(
                            player, playerId, playerToken, playerName,
                            clipboard, serialized, hash));
        } catch (Exception e) {
            logOperationalFailure(storage.description() + " sync failed for " + playerName, e);
        } finally {
            workerSlots.release();
            if (!callbackScheduled) {
                resetSynchronization(playerId, playerToken);
            }
        }
    }

    private void uploadIfCurrent(Player player, UUID playerId,
                                 Object playerToken, String playerName,
                                 Clipboard expectedClipboard, byte[] serialized, String hash) {
        if (!isActiveCheck(player, playerId, playerToken)
                || clipboardSerializer.getPlayerClipboard(player) != expectedClipboard) {
            clipboardManager.clearSerializedClipboard(playerId, expectedClipboard);
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

        ProgressHandle progress = actionBarProgress.begin(player, Operation.UPLOAD);
        try {
            if (SchedulerUtil.runAsync(plugin,
                    () -> uploadSerializedClipboard(
                            playerId, playerToken, playerName, serialized, hash, progress)) == null) {
                progress.fail();
                finishWorker(playerId, playerToken);
            }
        } catch (RuntimeException e) {
            progress.fail();
            finishWorker(playerId, playerToken);
            throw e;
        }
    }

    private void uploadSerializedClipboard(UUID playerId, Object playerToken,
                                           String playerName, byte[] data, String hash,
                                           ProgressHandle progress) {
        try {
            if (!running.get() || !ready.get()
                    || !clipboardManager.isCurrentPlayerToken(playerId, playerToken)) {
                progress.cancel();
                return;
            }
            storage.upload(playerId.toString(), data, hash, System.currentTimeMillis());
            if (!running.get()
                    || !clipboardManager.isCurrentPlayerToken(playerId, playerToken)) {
                progress.cancel();
                return;
            }
            clipboardManager.markUploadedClipboard(playerId, hash);
            progress.complete();
            logger.fine("Uploaded clipboard to " + storage.description() + " for " + playerName);
        } catch (Exception e) {
            progress.fail();
            logOperationalFailure(storage.description() + " upload failed for " + playerName, e);
        } finally {
            finishWorker(playerId, playerToken);
        }
    }

    private void handleSerializationFailure(Player player, UUID playerId, Object playerToken,
                                            String playerName, Clipboard expectedClipboard,
                                            Exception exception) {
        boolean clipboardWasReplaced = clipboardSerializer.getPlayerClipboard(player)
                != expectedClipboard;
        clipboardManager.clearSerializedClipboard(playerId, expectedClipboard);
        if (!clipboardWasReplaced && clipboardManager.isCurrentPlayerToken(playerId, playerToken)) {
            logOperationalFailure(storage.description() + " clipboard serialization failed for "
                    + playerName, exception);
        }
        resetCheck(playerId, playerToken);
    }

    private boolean downloadRemoteClipboard(Player player,
                                            UUID playerId, Object playerToken,
                                            String playerName, Clipboard expectedClipboard,
                                            StoredClipboard remote) throws Exception {
        if (!clipboardManager.isCurrentPlayerToken(playerId, playerToken)
                || !player.isOnline()
                || !clipboardManager.compareAndSetState(
                playerId, SyncState.CHECKING, SyncState.DOWNLOADING)) {
            return false;
        }

        ProgressHandle progress = actionBarProgress.begin(player, Operation.DOWNLOAD);
        try {
            byte[] data = storage.download(playerId.toString(), remote);
            String actualHash = HashUtil.sha256Hex(data);
            if (!actualHash.equalsIgnoreCase(remote.hash())) {
                throw new SecurityException(storage.description() + " clipboard hash mismatch");
            }
            Clipboard downloaded = clipboardSerializer.deserialize(
                    data, transferConfig.getMaxClipboardSize(),
                    transferConfig.getMaxClipboardBlocks());
            String localHash = HashUtil.sha256Hex(clipboardSerializer.serialize(
                    downloaded, transferConfig.getMaxClipboardSize(),
                    transferConfig.getMaxClipboardBlocks()));
            boolean scheduled = scheduleEntityContinuation(player, playerId, playerToken, () -> {
                if (!running.get()
                        || !player.isOnline()
                        || !clipboardManager.isCurrentPlayerToken(playerId, playerToken)
                        || clipboardManager.getState(playerId) != SyncState.DOWNLOADING
                        || clipboardSerializer.getPlayerClipboard(player) != expectedClipboard) {
                    progress.cancel();
                    if (clipboardManager.isCurrentPlayerToken(playerId, playerToken)
                            && clipboardManager.getState(playerId) == SyncState.DOWNLOADING) {
                        clipboardManager.forceSetState(playerId, SyncState.IDLE);
                    }
                    return;
                }
                try {
                    clipboardSerializer.setPlayerClipboard(player, downloaded);
                    clipboardManager.markDownloadedClipboard(
                            playerId, downloaded, actualHash, localHash);
                    progress.complete();
                    logger.info("Clipboard synced from " + storage.description() + " for " + playerName);
                } catch (Exception e) {
                    progress.fail();
                    logger.severe("Failed to apply " + storage.description() + " clipboard for "
                            + playerName + ": " + e.getMessage());
                } finally {
                    clipboardManager.forceSetState(playerId, SyncState.IDLE);
                }
            });
            if (!scheduled) {
                progress.cancel();
            }
            return scheduled;
        } catch (Exception e) {
            progress.fail();
            throw e;
        }
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
