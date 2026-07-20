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
import dev.twme.worldeditsync.common.protocol.ProtocolCodec;
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
    private final TransferConfig transferConfig;
    private final Logger logger;
    private final ConcurrentHashMap<String, PendingUpload> pendingUploads = new ConcurrentHashMap<>();

    private PluginMessageHandler messageHandler;

    public ProxySyncEngine(JavaPlugin plugin, ClipboardManager clipboardManager,
                           ClipboardSerializer clipboardSerializer, MessageCipher cipher,
                           TransferConfig transferConfig) {
        this.plugin = plugin;
        this.clipboardManager = clipboardManager;
        this.clipboardSerializer = clipboardSerializer;
        this.cipher = cipher;
        this.transferConfig = transferConfig;
        this.logger = plugin.getLogger();
    }

    @Override
    public void start() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, Constants.CHANNEL);
        messageHandler = new PluginMessageHandler(
                plugin, clipboardManager, clipboardSerializer, cipher, transferConfig, this);
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

        if (!clipboardManager.compareAndSetState(playerId, SyncState.CHECKING, SyncState.UPLOADING)) {
            return;
        }

        if (data.length > transferConfig.getMaxClipboardSize()) {
            logger.warning("Clipboard too large for " + player.getName() + ": " + data.length + " bytes");
            clipboardManager.forceSetState(playerId, SyncState.IDLE);
            return;
        }

        // Encrypt full payload before chunking
        byte[] payload = cipher.encrypt(data);

        int chunkSize = transferConfig.getChunkSize();
        int totalChunks = (int) Math.ceil((double) payload.length / chunkSize);
        String sessionId = UUID.randomUUID().toString();

        clipboardManager.setActiveSessionId(playerId, sessionId);
        pendingUploads.put(sessionId, new PendingUpload(playerId, payload, totalChunks));

        byte[] beginMsg = ProtocolCodec.encodeUploadBegin(sessionId, payload.length, totalChunks, hash);
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

        sendChunks(player, sessionId, upload);
    }

    private void sendChunks(Player player, String sessionId, PendingUpload upload) {
        SchedulerUtil.runAsync(plugin, () -> {
            try {
                for (int i = 0; i < upload.totalChunks; i++) {
                    if (!player.isOnline()) {
                        return;
                    }

                    int offset = i * transferConfig.getChunkSize();
                    int length = Math.min(transferConfig.getChunkSize(), upload.payload.length - offset);
                    byte[] chunk = new byte[length];
                    System.arraycopy(upload.payload, offset, chunk, 0, length);

                    byte[] chunkMsg = ProtocolCodec.encodeUploadChunk(sessionId, i, chunk);

                    SchedulerUtil.runOnEntityThread(plugin, player, () -> {
                        if (player.isOnline()
                                && sessionId.equals(clipboardManager.getActiveSessionId(upload.playerId))) {
                            player.sendPluginMessage(plugin, Constants.CHANNEL, chunkMsg);
                        }
                    });

                    if (transferConfig.getChunkSendDelayMs() > 0 && i < upload.totalChunks - 1) {
                        Thread.sleep(transferConfig.getChunkSendDelayMs());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failUpload(upload.playerId, sessionId);
            } catch (Exception e) {
                logger.warning("Clipboard upload failed for " + player.getName() + ": " + e.getMessage());
                failUpload(upload.playerId, sessionId);
            }
        });
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

    private void timeoutUpload(Player player, String sessionId) {
        PendingUpload upload = pendingUploads.remove(sessionId);
        if (upload == null) {
            return;
        }
        SchedulerUtil.runOnEntityThread(plugin, player, () -> {
            if (player.isOnline()) {
                player.sendPluginMessage(plugin, Constants.CHANNEL,
                        ProtocolCodec.encodeCancel(sessionId, "upload_timeout"));
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
        // No action needed here for proxy mode.
        // The proxy sends SYNC_HASH or SYNC_NO_DATA on server switch,
        // which is handled by PluginMessageHandler.
        clipboardManager.initPlayer(player.getUniqueId());
    }

    @Override
    public void onPlayerQuit(Player player) {
        UUID playerId = player.getUniqueId();
        SyncState state = clipboardManager.getState(playerId);

        if (state == SyncState.UPLOADING || state == SyncState.DOWNLOADING) {
            String sessionId = clipboardManager.getActiveSessionId(playerId);
            if (sessionId != null) {
                pendingUploads.remove(sessionId);
                byte[] cancelMsg = ProtocolCodec.encodeCancel(sessionId, "player_quit");
                try {
                    player.sendPluginMessage(plugin, Constants.CHANNEL, cancelMsg);
                } catch (Exception ignored) {
                    // Player might already be disconnected
                }
            }
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
        private final AtomicBoolean started = new AtomicBoolean();

        private PendingUpload(UUID playerId, byte[] payload, int totalChunks) {
            this.playerId = playerId;
            this.payload = payload;
            this.totalChunks = totalChunks;
        }
    }
}
