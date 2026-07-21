package dev.twme.worldeditsync.paper.message;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import com.sk89q.worldedit.extent.clipboard.Clipboard;

import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.config.TransferConfig;
import dev.twme.worldeditsync.common.crypto.MessageCipher;
import dev.twme.worldeditsync.common.model.SyncState;
import dev.twme.worldeditsync.common.protocol.InboundMessageLimiter;
import dev.twme.worldeditsync.common.protocol.PluginMessageCodec;
import dev.twme.worldeditsync.common.protocol.ProtocolCodec;
import dev.twme.worldeditsync.common.protocol.ProtocolCodec.ParsedMessage;
import dev.twme.worldeditsync.common.protocol.ProtocolValidation;
import dev.twme.worldeditsync.common.protocol.TransferSession;
import dev.twme.worldeditsync.common.util.HashUtil;
import dev.twme.worldeditsync.paper.clipboard.ClipboardManager;
import dev.twme.worldeditsync.paper.clipboard.ClipboardSerializer;
import dev.twme.worldeditsync.paper.sync.UploadSessionListener;
import dev.twme.worldeditsync.paper.ui.ActionBarProgress;
import dev.twme.worldeditsync.paper.ui.ActionBarProgress.Operation;
import dev.twme.worldeditsync.paper.ui.ActionBarProgress.ProgressHandle;
import dev.twme.worldeditsync.paper.util.SchedulerUtil;

/**
 * Handles incoming plugin messages from the proxy in Proxy mode.
 */
public class PluginMessageHandler implements PluginMessageListener {

    private final JavaPlugin plugin;
    private final ClipboardManager clipboardManager;
    private final ClipboardSerializer clipboardSerializer;
    private final MessageCipher cipher;
    private final PluginMessageCodec pluginMessageCodec;
    private final TransferConfig transferConfig;
    private final UploadSessionListener uploadSessionListener;
    private final ActionBarProgress actionBarProgress;
    private final Logger logger;
    private final InboundMessageLimiter inboundMessageLimiter = new InboundMessageLimiter();
    private final ConcurrentHashMap<UUID, Long> invalidMessageWarnings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ProgressHandle> downloadProgress = new ConcurrentHashMap<>();

    public PluginMessageHandler(JavaPlugin plugin, ClipboardManager clipboardManager,
                                ClipboardSerializer clipboardSerializer, MessageCipher cipher,
                                PluginMessageCodec pluginMessageCodec,
                                TransferConfig transferConfig,
                                UploadSessionListener uploadSessionListener,
                                ActionBarProgress actionBarProgress) {
        this.plugin = plugin;
        this.clipboardManager = clipboardManager;
        this.clipboardSerializer = clipboardSerializer;
        this.cipher = cipher;
        this.pluginMessageCodec = pluginMessageCodec;
        this.transferConfig = transferConfig;
        this.uploadSessionListener = uploadSessionListener;
        this.actionBarProgress = actionBarProgress;
        this.logger = plugin.getLogger();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] data) {
        if (!Constants.CHANNEL.equals(channel)) return;

        int messageLength = data == null ? -1 : data.length;
        if (!inboundMessageLimiter.tryAcquire(player.getUniqueId(), messageLength)) {
            warnInvalidMessage(player, "Rejected rate-limited protocol message");
            return;
        }

        ParsedMessage msg = pluginMessageCodec.decode(data);
        if (msg == null) {
            warnInvalidMessage(player, "Received invalid or unauthenticated protocol message");
            return;
        }

        try {
            switch (msg.type()) {
                case SYNC_HASH -> handleSyncHash(player, msg);
                case SYNC_NO_DATA -> handleSyncNoData(player, msg);
                case UPLOAD_READY -> handleUploadReady(player, msg);
                case UPLOAD_ACK -> handleUploadAck(player, msg);
                case DOWNLOAD_BEGIN -> handleDownloadBegin(player, msg);
                case DOWNLOAD_CHUNK -> handleDownloadChunk(player, msg);
                case CANCEL -> handleCancel(player, msg);
                default -> logger.warning("Unexpected message type from proxy: " + msg.type());
            }
        } catch (Exception e) {
            logger.severe("Error handling message " + msg.type() + " for " + player.getName() + ": " + e.getMessage());
        }
    }

    private void handleSyncHash(Player player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String requestId = in.readUTF();
        String remoteHash = in.readUTF();
        if (!ProtocolValidation.isSessionId(requestId)
                || !ProtocolValidation.isSha256(remoteHash)
                || !ProtocolValidation.exhausted(in)) {
            warnInvalidMessage(player, "Rejected malformed SYNC_HASH");
            return;
        }

        var playerId = player.getUniqueId();
        if (!requestId.equals(clipboardManager.getActiveSessionId(playerId))
                || !clipboardManager.compareAndSetState(
                        playerId, SyncState.PENDING_SYNC, SyncState.CHECKING)) {
            logger.fine("Ignoring stale SYNC_HASH for " + player.getName());
            return;
        }
        clipboardManager.clearActiveSession(playerId);

        String localHash = clipboardManager.getLocalHash(playerId);

        if (remoteHash.equals(localHash)) {
            clipboardManager.forceSetState(playerId, SyncState.IDLE);
            logger.fine("Hash match for " + player.getName() + ", no download needed.");
            return;
        }

        // Hash differs: request download
        if (!clipboardManager.compareAndSetState(playerId, SyncState.CHECKING, SyncState.DOWNLOADING)) {
            clipboardManager.forceSetState(playerId, SyncState.IDLE);
            return;
        }

        String downloadRequestId = UUID.randomUUID().toString();
        clipboardManager.setActiveSessionId(playerId, downloadRequestId);
        byte[] requestMsg = pluginMessageCodec.encode(
                ProtocolCodec.encodeDownloadRequest(downloadRequestId));
        player.sendPluginMessage(plugin, Constants.CHANNEL, requestMsg);
        SchedulerUtil.runDelayedAsync(plugin,
                () -> timeoutDownloadRequest(player, downloadRequestId),
                transferConfig.getSessionTimeoutMs());
        logger.fine("Hash mismatch for " + player.getName() + ", requesting download.");
    }

    private void handleSyncNoData(Player player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String requestId = in.readUTF();
        if (!ProtocolValidation.isSessionId(requestId) || !ProtocolValidation.exhausted(in)) {
            warnInvalidMessage(player, "Rejected malformed SYNC_NO_DATA");
            return;
        }

        UUID playerId = player.getUniqueId();
        if (!requestId.equals(clipboardManager.getActiveSessionId(playerId))
                || !clipboardManager.compareAndSetState(
                        playerId, SyncState.PENDING_SYNC, SyncState.IDLE)) {
            logger.fine("Ignoring stale SYNC_NO_DATA for " + player.getName());
            return;
        }
        clipboardManager.clearActiveSession(playerId);
        logger.fine("Proxy has no clipboard data for " + player.getName());
    }

    private void handleUploadReady(Player player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String sessionId = in.readUTF();
        if (ProtocolValidation.isSessionId(sessionId) && ProtocolValidation.exhausted(in)) {
            uploadSessionListener.onUploadReady(player, sessionId);
        } else {
            warnInvalidMessage(player, "Rejected malformed UPLOAD_READY");
        }
    }

    private void handleUploadAck(Player player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String sessionId = in.readUTF();
        if (ProtocolValidation.isSessionId(sessionId) && ProtocolValidation.exhausted(in)) {
            uploadSessionListener.onUploadAcknowledged(player, sessionId);
        } else {
            warnInvalidMessage(player, "Rejected malformed UPLOAD_ACK");
        }
    }

    private void handleDownloadBegin(Player player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String requestId = in.readUTF();
        String sessionId = in.readUTF();
        int totalBytes = in.readInt();
        int totalChunks = in.readInt();
        String hash = in.readUTF();

        var playerId = player.getUniqueId();
        long maxPayloadSize = (long) transferConfig.getMaxClipboardSize()
                + MessageCipher.ENCRYPTION_OVERHEAD_BYTES;
        if (!ProtocolValidation.isSessionId(requestId)
                || !ProtocolValidation.isSessionId(sessionId)
                || !ProtocolValidation.isSha256(hash)
                || !ProtocolValidation.exhausted(in)
                || totalBytes > maxPayloadSize
                || !TransferSession.isValidLayout(totalBytes, totalChunks, transferConfig.getChunkSize())) {
            warnInvalidMessage(player, "Rejected malformed DOWNLOAD_BEGIN");
            if (ProtocolValidation.isSessionId(sessionId)) {
                sendOnEntityThread(player, ProtocolCodec.encodeCancel(sessionId, "invalid_download"));
            }
            return;
        }

        if (clipboardManager.getState(playerId) != SyncState.DOWNLOADING
                || !requestId.equals(clipboardManager.getActiveSessionId(playerId))) {
            warnInvalidMessage(player, "Rejected unsolicited DOWNLOAD_BEGIN");
            sendOnEntityThread(player, ProtocolCodec.encodeCancel(sessionId, "unexpected_download"));
            return;
        }

        TransferSession session = new TransferSession(sessionId, totalChunks, totalBytes, hash);
        clipboardManager.addDownloadSession(sessionId, session);
        clipboardManager.setActiveSessionId(playerId, sessionId);
        ProgressHandle progress = actionBarProgress.begin(player, Operation.DOWNLOAD);
        ProgressHandle previous = downloadProgress.put(sessionId, progress);
        if (previous != null) {
            previous.cancel();
        }

        SchedulerUtil.runDelayedAsync(
                plugin,
                () -> timeoutDownload(player, sessionId),
                transferConfig.getSessionTimeoutMs());

        logger.fine("Download begin for " + player.getName() + ": " + totalBytes + " bytes, " + totalChunks + " chunks");
    }

    private void handleDownloadChunk(Player player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String sessionId = in.readUTF();
        int chunkIndex = in.readInt();
        int chunkLength = in.readInt();
        if (!ProtocolValidation.isSessionId(sessionId)
                || chunkIndex < 0
                || chunkLength <= 0
                || chunkLength > transferConfig.getChunkSize()) {
            warnInvalidMessage(player, "Rejected malformed DOWNLOAD_CHUNK");
            if (!ProtocolValidation.isSessionId(sessionId)) {
                return;
            }
            rejectDownload(player, sessionId, "invalid_chunk_length");
            return;
        }
        byte[] chunkData = in.readNBytes(chunkLength);
        if (chunkData.length != chunkLength || !ProtocolValidation.exhausted(in)) {
            rejectDownload(player, sessionId, "truncated_chunk");
            return;
        }

        TransferSession session = clipboardManager.getDownloadSession(sessionId);
        if (session == null) {
            logger.warning("Received chunk for unknown session: " + sessionId);
            return;
        }
        if (!sessionId.equals(clipboardManager.getActiveSessionId(player.getUniqueId()))) {
            logger.warning("Received chunk for inactive session: " + sessionId);
            return;
        }
        if (chunkIndex >= session.getTotalChunks()) {
            rejectDownload(player, sessionId, "invalid_chunk_index");
            return;
        }
        int expectedLength = Math.min(
                transferConfig.getChunkSize(),
                session.getTotalBytes() - chunkIndex * transferConfig.getChunkSize());
        if (chunkLength != expectedLength) {
            rejectDownload(player, sessionId, "invalid_chunk_layout");
            return;
        }

        boolean added;
        try {
            added = session.addChunk(chunkIndex, chunkData);
        } catch (IllegalArgumentException e) {
            logger.warning("Rejected invalid download chunk for " + player.getName()
                    + ": " + e.getMessage());
            rejectDownload(player, sessionId, "invalid_chunk");
            return;
        }

        if (!added) {
            return;
        }

        if (session.tryClaimCompletion()) {
            completeDownload(player, session);
        } else {
            ProgressHandle progress = downloadProgress.get(sessionId);
            if (progress != null) {
                progress.update((double) session.getReceivedBytes() / session.getTotalBytes());
            }
        }
    }

    private void completeDownload(Player player, TransferSession session) {
        var playerId = player.getUniqueId();
        String sessionId = session.getSessionId();
        String playerName = player.getName();

        SchedulerUtil.runAsync(plugin, () -> {
            try {
                byte[] assembled = session.assemble();
                byte[] decrypted = cipher.decrypt(assembled);
                String actualHash = HashUtil.sha256Hex(decrypted);

                if (!actualHash.equalsIgnoreCase(session.getExpectedHash())) {
                    logger.warning("Hash mismatch after download for " + playerName
                            + ": expected " + session.getExpectedHash() + ", got " + actualHash);
                    rejectDownload(player, sessionId, "hash_mismatch");
                    return;
                }

                Clipboard clipboard = clipboardSerializer.deserialize(decrypted);
                SchedulerUtil.runOnEntityThread(plugin, player,
                        () -> applyDownloadedClipboard(player, sessionId, clipboard, actualHash));
            } catch (Exception e) {
                logger.severe("Failed to complete download for " + playerName + ": " + e.getMessage());
                rejectDownload(player, sessionId, "download_failed");
            }
        });
    }

    private void applyDownloadedClipboard(Player player, String sessionId,
                                          Clipboard clipboard, String actualHash) {
        UUID playerId = player.getUniqueId();
        if (!player.isOnline()
                || clipboardManager.getState(playerId) != SyncState.DOWNLOADING
                || !sessionId.equals(clipboardManager.getActiveSessionId(playerId))) {
            clipboardManager.removeDownloadSession(sessionId);
            cancelDownloadProgress(sessionId);
            if (clipboardManager.isTracked(playerId)
                    && sessionId.equals(clipboardManager.getActiveSessionId(playerId))) {
                clipboardManager.clearActiveSession(playerId);
                clipboardManager.forceSetState(playerId, SyncState.PENDING_SYNC);
            }
            return;
        }

        try {
            clipboardSerializer.setPlayerClipboard(player, clipboard);
            clipboardManager.setLocalHash(playerId, actualHash);
            clipboardManager.removeDownloadSession(sessionId);
            clipboardManager.clearActiveSession(playerId);
            clipboardManager.forceSetState(playerId, SyncState.IDLE);
            player.sendPluginMessage(plugin, Constants.CHANNEL,
                    pluginMessageCodec.encode(ProtocolCodec.encodeDownloadAck(sessionId)));
            completeDownloadProgress(sessionId);
            logger.info("Clipboard synced for " + player.getName());
        } catch (Exception e) {
            logger.severe("Failed to apply clipboard for " + player.getName() + ": " + e.getMessage());
            rejectDownload(player, sessionId, "clipboard_apply_failed");
        }
    }

    private void timeoutDownload(Player player, String sessionId) {
        TransferSession session = clipboardManager.getDownloadSession(sessionId);
        if (session == null) {
            return;
        }
        if (!session.isExpired(transferConfig.getSessionTimeoutMs())) {
            SchedulerUtil.runDelayedAsync(
                    plugin,
                    () -> timeoutDownload(player, sessionId),
                    transferConfig.getSessionTimeoutMs());
            return;
        }

        logger.warning("Clipboard download timed out for " + player.getName()
                + " (session: " + sessionId + ")");
        rejectDownload(player, sessionId, "download_timeout");
    }

    private void timeoutDownloadRequest(Player player, String requestId) {
        UUID playerId = player.getUniqueId();
        if (clipboardManager.getState(playerId) == SyncState.DOWNLOADING
                && requestId.equals(clipboardManager.getActiveSessionId(playerId))) {
            clipboardManager.clearActiveSession(playerId);
            uploadSessionListener.onDownloadFailed(player, "download_request_timeout");
        }
    }

    private void rejectDownload(Player player, String sessionId, String reason) {
        var playerId = player.getUniqueId();
        clipboardManager.removeDownloadSession(sessionId);
        failDownloadProgress(sessionId);
        if (!sessionId.equals(clipboardManager.getActiveSessionId(playerId))) {
            return;
        }

        clipboardManager.clearActiveSession(playerId);
        SchedulerUtil.runOnEntityThread(plugin, player, () -> {
            if (player.isOnline()) {
                player.sendPluginMessage(plugin, Constants.CHANNEL,
                        pluginMessageCodec.encode(ProtocolCodec.encodeCancel(sessionId, reason)));
            }
        });
        uploadSessionListener.onDownloadFailed(player, reason);
    }

    private void handleCancel(Player player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String sessionId = in.readUTF();
        String reason = in.readUTF();
        if (!ProtocolValidation.isSessionId(sessionId)
                || !ProtocolValidation.isReason(reason)
                || !ProtocolValidation.exhausted(in)) {
            warnInvalidMessage(player, "Rejected malformed CANCEL");
            return;
        }

        var playerId = player.getUniqueId();
        logger.fine("Transfer cancelled for " + player.getName() + ": " + reason);
        failDownloadProgress(sessionId);

        uploadSessionListener.onUploadCancelled(player, sessionId, reason);

        if (clipboardManager.getState(playerId) == SyncState.DOWNLOADING
                && sessionId.equals(clipboardManager.getActiveSessionId(playerId))) {
            clipboardManager.removeDownloadSession(sessionId);
            clipboardManager.clearActiveSession(playerId);
            if ("clipboard_not_found".equals(reason)) {
                clipboardManager.forceSetState(playerId, SyncState.IDLE);
            } else {
                uploadSessionListener.onDownloadFailed(player, reason);
            }
            return;
        }

        if (clipboardManager.getDownloadSession(sessionId) != null) {
            clipboardManager.removeDownloadSession(sessionId);
            if (sessionId.equals(clipboardManager.getActiveSessionId(playerId))) {
                clipboardManager.clearActiveSession(playerId);
                clipboardManager.forceSetState(playerId, SyncState.IDLE);
            }
        }
    }

    private void sendOnEntityThread(Player player, byte[] protocolMessage) {
        SchedulerUtil.runOnEntityThread(plugin, player, () -> {
            if (player.isOnline()) {
                player.sendPluginMessage(plugin, Constants.CHANNEL,
                        pluginMessageCodec.encode(protocolMessage));
            }
        });
    }

    private void warnInvalidMessage(Player player, String message) {
        long now = System.currentTimeMillis();
        Long previous = invalidMessageWarnings.put(player.getUniqueId(), now);
        if (previous == null || now - previous >= 5_000L) {
            logger.warning(message + " for " + player.getName());
        }
    }

    public void removePlayer(UUID playerId) {
        invalidMessageWarnings.remove(playerId);
        inboundMessageLimiter.remove(playerId);
        downloadProgress.forEach((sessionId, progress) -> {
            if (progress.playerId().equals(playerId)
                    && downloadProgress.remove(sessionId, progress)) {
                progress.cancel();
            }
        });
        actionBarProgress.removePlayer(playerId);
    }

    private void completeDownloadProgress(String sessionId) {
        ProgressHandle progress = downloadProgress.remove(sessionId);
        if (progress != null) {
            progress.complete();
        }
    }

    private void failDownloadProgress(String sessionId) {
        ProgressHandle progress = downloadProgress.remove(sessionId);
        if (progress != null) {
            progress.fail();
        }
    }

    private void cancelDownloadProgress(String sessionId) {
        ProgressHandle progress = downloadProgress.remove(sessionId);
        if (progress != null) {
            progress.cancel();
        }
    }
}
