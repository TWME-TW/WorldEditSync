package dev.twme.worldeditsync.paper.sync;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.config.TransferConfig;
import dev.twme.worldeditsync.common.crypto.MessageCipher;
import dev.twme.worldeditsync.common.model.SyncState;
import dev.twme.worldeditsync.common.protocol.PluginMessageCodec;
import dev.twme.worldeditsync.common.protocol.ProtocolCodec;
import dev.twme.worldeditsync.common.protocol.ProtocolValidation;
import dev.twme.worldeditsync.paper.clipboard.ClipboardManager;
import dev.twme.worldeditsync.paper.clipboard.ClipboardSerializer;
import dev.twme.worldeditsync.paper.message.PluginMessageHandler;
import dev.twme.worldeditsync.paper.util.SchedulerUtil;

/**
 * Proxy-mode sync engine: uploads/downloads clipboards via BungeeCord/Velocity Plugin Messages.
 */
public class ProxySyncEngine implements SyncEngine, UploadSessionListener {

    private final JavaPlugin plugin;
    private final ClipboardManager clipboardManager;
    private final ClipboardSerializer clipboardSerializer;
    private final MessageCipher cipher;
    private final PluginMessageCodec pluginMessageCodec;
    private final TransferConfig transferConfig;
    private final Logger logger;
    private final ConcurrentHashMap<String, PendingUpload> pendingUploads = new ConcurrentHashMap<>();

    private PluginMessageHandler messageHandler;

    public ProxySyncEngine(JavaPlugin plugin, ClipboardManager clipboardManager,
                           ClipboardSerializer clipboardSerializer, MessageCipher cipher,
                           PluginMessageCodec pluginMessageCodec, TransferConfig transferConfig) {
        this.plugin = plugin;
        this.clipboardManager = clipboardManager;
        this.clipboardSerializer = clipboardSerializer;
        this.cipher = cipher;
        this.pluginMessageCodec = pluginMessageCodec;
        this.transferConfig = transferConfig;
        this.logger = plugin.getLogger();
    }

    @Override
    public void start() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, Constants.CHANNEL);
        messageHandler = new PluginMessageHandler(
                plugin, clipboardManager, clipboardSerializer, cipher, pluginMessageCodec,
                transferConfig, this);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, Constants.CHANNEL, messageHandler);
        logger.info("Proxy sync engine started on channel: " + Constants.CHANNEL);
    }

    @Override
    public void shutdown() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, Constants.CHANNEL);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, Constants.CHANNEL);
        pendingUploads.clear();
        logger.info("Proxy sync engine shut down.");
    }

    @Override
    public void uploadClipboard(Player player, byte[] data, String hash) {
        UUID playerId = player.getUniqueId();
        Object playerToken = clipboardManager.getPlayerToken(playerId);

        if (!player.isOnline()
                || !clipboardManager.isTracked(playerId)
                || !ProtocolValidation.isSha256(hash)) {
            return;
        }
        if (!clipboardManager.compareAndSetState(playerId, SyncState.CHECKING, SyncState.UPLOADING)) {
            return;
        }

        if (data.length <= 0 || data.length > transferConfig.getMaxClipboardSize()) {
            logger.warning("Clipboard too large for " + player.getName() + ": " + data.length + " bytes");
            clipboardManager.forceSetState(playerId, SyncState.IDLE);
            return;
        }

        byte[] payload;
        try {
            payload = cipher.encrypt(data);
        } catch (Exception e) {
            logger.warning("Clipboard encryption failed for " + player.getName() + ": " + e.getMessage());
            if (clipboardManager.isCurrentPlayerToken(playerId, playerToken)) {
                clipboardManager.forgetClipboard(playerId);
                clipboardManager.forceSetState(playerId, SyncState.IDLE);
            }
            return;
        }

        if (!player.isOnline()
                || !clipboardManager.isCurrentPlayerToken(playerId, playerToken)
                || clipboardManager.getState(playerId) != SyncState.UPLOADING) {
            return;
        }

        int chunkSize = transferConfig.getChunkSize();
        int totalChunks = (int) Math.ceil((double) payload.length / chunkSize);
        String sessionId = UUID.randomUUID().toString();

        clipboardManager.setActiveSessionId(playerId, sessionId);
        pendingUploads.put(sessionId, new PendingUpload(playerId, payload, totalChunks, hash));

        if (!player.isOnline()
                || !clipboardManager.isCurrentPlayerToken(playerId, playerToken)
                || clipboardManager.getState(playerId) != SyncState.UPLOADING) {
            pendingUploads.remove(sessionId);
            return;
        }

        byte[] beginMsg = pluginMessageCodec.encode(
                ProtocolCodec.encodeUploadBegin(sessionId, payload.length, totalChunks, hash));
        SchedulerUtil.runOnEntityThread(plugin, player, () -> {
            if (player.isOnline() && sessionId.equals(clipboardManager.getActiveSessionId(playerId))) {
                player.sendPluginMessage(plugin, Constants.CHANNEL, beginMsg);
            }
        });

        SchedulerUtil.runDelayedAsync(
                plugin,
                () -> timeoutUpload(player, sessionId),
                transferConfig.getSessionTimeoutMs());
        logger.info("Clipboard upload started for " + player.getName()
                + " (session: " + sessionId + ", " + data.length + " bytes)");
    }

    @Override
    public void onUploadReady(Player player, String sessionId) {
        UUID playerId = player.getUniqueId();
        PendingUpload upload = pendingUploads.get(sessionId);
        if (upload == null
                || !upload.playerId.equals(playerId)
                || !sessionId.equals(clipboardManager.getActiveSessionId(playerId))
                || !upload.started.compareAndSet(false, true)) {
            return;
        }

        upload.touch();
        sendChunks(player, sessionId, upload);
    }

    private void sendChunks(Player player, String sessionId, PendingUpload upload) {
        SchedulerUtil.runOnEntityThread(plugin, player, () -> pumpUpload(player, sessionId, upload));
    }

    private void pumpUpload(Player player, String sessionId, PendingUpload upload) {
        if (!player.isOnline()
                || !sessionId.equals(clipboardManager.getActiveSessionId(upload.playerId))) {
            failUpload(upload.playerId, sessionId);
            return;
        }

        try {
            int chunksPerTick = chunksPerPump();
            for (int sent = 0; sent < chunksPerTick && upload.nextChunkIndex < upload.totalChunks; sent++) {
                int chunkIndex = upload.nextChunkIndex++;
                int offset = chunkIndex * transferConfig.getChunkSize();
                int length = Math.min(transferConfig.getChunkSize(), upload.payload.length - offset);
                byte[] chunk = new byte[length];
                System.arraycopy(upload.payload, offset, chunk, 0, length);

                byte[] chunkMsg = pluginMessageCodec.encode(
                        ProtocolCodec.encodeUploadChunk(sessionId, chunkIndex, chunk));
                player.sendPluginMessage(plugin, Constants.CHANNEL, chunkMsg);
                upload.touch();
            }

            if (upload.nextChunkIndex < upload.totalChunks) {
                SchedulerUtil.runDelayedOnEntityThread(
                        plugin, player, () -> pumpUpload(player, sessionId, upload), pumpDelayTicks());
            }
        } catch (Exception e) {
            logger.warning("Clipboard upload failed for " + player.getName() + ": " + e.getMessage());
            failUpload(upload.playerId, sessionId);
        }
    }

    private int chunksPerPump() {
        long delayMs = transferConfig.getChunkSendDelayMs();
        if (delayMs <= 0) {
            return Constants.MAX_CHUNKS_PER_TICK;
        }
        return Math.max(1, Math.min(Constants.MAX_CHUNKS_PER_TICK, (int) (50L / delayMs)));
    }

    private long pumpDelayTicks() {
        return Math.max(1L, (transferConfig.getChunkSendDelayMs() + 49L) / 50L);
    }

    @Override
    public void onUploadAcknowledged(Player player, String sessionId) {
        UUID playerId = player.getUniqueId();
        PendingUpload upload = pendingUploads.get(sessionId);
        if (upload == null || !upload.playerId.equals(playerId)) {
            return;
        }

        pendingUploads.remove(sessionId, upload);
        if (sessionId.equals(clipboardManager.getActiveSessionId(playerId))) {
            clipboardManager.setLocalHash(playerId, upload.hash);
            clipboardManager.clearActiveSession(playerId);
            clipboardManager.forceSetState(playerId, SyncState.IDLE);
        }
        logger.info("Clipboard upload completed for " + player.getName()
                + " (session: " + sessionId + ")");
    }

    @Override
    public void onUploadCancelled(Player player, String sessionId, String reason) {
        PendingUpload upload = pendingUploads.get(sessionId);
        if (upload == null || !upload.playerId.equals(player.getUniqueId())) {
            return;
        }
        pendingUploads.remove(sessionId, upload);
        failUpload(upload.playerId, sessionId);
        logger.warning("Clipboard upload cancelled for " + player.getName() + ": " + reason);
    }

    @Override
    public void onDownloadFailed(Player player, String reason) {
        UUID playerId = player.getUniqueId();
        if (!clipboardManager.isTracked(playerId)) {
            return;
        }
        String requestId = UUID.randomUUID().toString();
        clipboardManager.clearActiveSession(playerId);
        clipboardManager.forceSetState(playerId, SyncState.PENDING_SYNC);
        clipboardManager.setActiveSessionId(playerId, requestId);
        logger.warning("Clipboard download failed for " + player.getName()
                + " (" + reason + "); restarting the protected sync handshake.");
        sendInitialSyncRequest(player, requestId, 1);
    }

    private void timeoutUpload(Player player, String sessionId) {
        PendingUpload upload = pendingUploads.get(sessionId);
        if (upload == null) {
            return;
        }
        if (!upload.isExpired(transferConfig.getSessionTimeoutMs())) {
            SchedulerUtil.runDelayedAsync(
                    plugin,
                    () -> timeoutUpload(player, sessionId),
                    transferConfig.getSessionTimeoutMs());
            return;
        }
        if (!pendingUploads.remove(sessionId, upload)) {
            return;
        }
        SchedulerUtil.runOnEntityThread(plugin, player, () -> {
            if (player.isOnline()) {
                player.sendPluginMessage(plugin, Constants.CHANNEL,
                        pluginMessageCodec.encode(ProtocolCodec.encodeCancel(sessionId, "upload_timeout")));
            }
        });
        failUpload(upload.playerId, sessionId);
        logger.warning("Clipboard upload timed out for " + player.getName()
                + " (session: " + sessionId + ")");
    }

    private void failUpload(UUID playerId, String sessionId) {
        pendingUploads.remove(sessionId);
        if (sessionId.equals(clipboardManager.getActiveSessionId(playerId))) {
            clipboardManager.clearActiveSession(playerId);
            clipboardManager.forgetClipboard(playerId);
            clipboardManager.forceSetState(playerId, SyncState.IDLE);
        }
    }

    @Override
    public void onPlayerJoinServer(Player player) {
        UUID playerId = player.getUniqueId();
        String requestId = UUID.randomUUID().toString();
        clipboardManager.initPlayer(playerId);
        clipboardManager.setActiveSessionId(playerId, requestId);
        sendInitialSyncRequest(player, requestId, 1);
    }

    private void sendInitialSyncRequest(Player player, String requestId, int attempt) {
        SchedulerUtil.runOnEntityThread(plugin, player, () -> {
            UUID playerId = player.getUniqueId();
            if (!player.isOnline()
                    || clipboardManager.getState(playerId) != SyncState.PENDING_SYNC
                    || !requestId.equals(clipboardManager.getActiveSessionId(playerId))) {
                return;
            }

            player.sendPluginMessage(plugin, Constants.CHANNEL,
                    pluginMessageCodec.encode(ProtocolCodec.encodeSyncRequest(requestId)));

            if (attempt < Constants.INITIAL_SYNC_MAX_ATTEMPTS) {
                SchedulerUtil.runDelayedAsync(plugin,
                        () -> sendInitialSyncRequest(player, requestId, attempt + 1),
                        Constants.INITIAL_SYNC_RETRY_MS);
            } else {
                SchedulerUtil.runDelayedAsync(plugin, () -> {
                    if (clipboardManager.getState(playerId) == SyncState.PENDING_SYNC
                            && requestId.equals(clipboardManager.getActiveSessionId(playerId))) {
                        logger.warning("Proxy did not answer the initial clipboard sync request for "
                                + player.getName() + "; uploads remain paused to avoid overwriting remote data.");
                    }
                }, Constants.INITIAL_SYNC_RETRY_MS);
            }
        });
    }

    @Override
    public void onPlayerQuit(Player player) {
        UUID playerId = player.getUniqueId();
        SyncState state = clipboardManager.getState(playerId);

        if (state == SyncState.UPLOADING || state == SyncState.DOWNLOADING) {
            String sessionId = clipboardManager.getActiveSessionId(playerId);
            if (sessionId != null) {
                pendingUploads.remove(sessionId);
                byte[] cancelMsg = pluginMessageCodec.encode(
                        ProtocolCodec.encodeCancel(sessionId, "player_quit"));
                try {
                    player.sendPluginMessage(plugin, Constants.CHANNEL, cancelMsg);
                } catch (Exception ignored) {
                    // Player might already be disconnected
                }
            }
        }

        if (messageHandler != null) {
            messageHandler.removePlayer(playerId);
        }
        clipboardManager.removePlayer(playerId);
    }

    public PluginMessageHandler getMessageHandler() {
        return messageHandler;
    }

    private static final class PendingUpload {
        private final UUID playerId;
        private final byte[] payload;
        private final int totalChunks;
        private final String hash;
        private final AtomicBoolean started = new AtomicBoolean();
        private volatile long lastActivityAt = System.currentTimeMillis();
        private int nextChunkIndex;

        private PendingUpload(UUID playerId, byte[] payload, int totalChunks, String hash) {
            this.playerId = playerId;
            this.payload = payload;
            this.totalChunks = totalChunks;
            this.hash = hash;
        }

        private void touch() {
            lastActivityAt = System.currentTimeMillis();
        }

        private boolean isExpired(long timeoutMs) {
            return System.currentTimeMillis() - lastActivityAt >= timeoutMs;
        }
    }
}
