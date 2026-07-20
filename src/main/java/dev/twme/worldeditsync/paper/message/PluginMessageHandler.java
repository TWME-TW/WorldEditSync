package dev.twme.worldeditsync.paper.message;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import com.sk89q.worldedit.extent.clipboard.Clipboard;

import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.config.TransferConfig;
import dev.twme.worldeditsync.common.crypto.MessageCipher;
import dev.twme.worldeditsync.common.model.SyncState;
import dev.twme.worldeditsync.common.protocol.ProtocolCodec;
import dev.twme.worldeditsync.common.protocol.ProtocolCodec.ParsedMessage;
import dev.twme.worldeditsync.common.protocol.TransferSession;
import dev.twme.worldeditsync.common.util.HashUtil;
import dev.twme.worldeditsync.paper.clipboard.ClipboardManager;
import dev.twme.worldeditsync.paper.clipboard.ClipboardSerializer;
import dev.twme.worldeditsync.paper.sync.UploadSessionListener;
import dev.twme.worldeditsync.paper.util.SchedulerUtil;

/**
 * Handles incoming plugin messages from the proxy in Proxy mode.
 */
public class PluginMessageHandler implements PluginMessageListener {

    private final JavaPlugin plugin;
    private final ClipboardManager clipboardManager;
    private final ClipboardSerializer clipboardSerializer;
    private final MessageCipher cipher;
    private final TransferConfig transferConfig;
    private final UploadSessionListener uploadSessionListener;
    private final Logger logger;

    public PluginMessageHandler(JavaPlugin plugin, ClipboardManager clipboardManager,
                                ClipboardSerializer clipboardSerializer, MessageCipher cipher,
                                TransferConfig transferConfig,
                                UploadSessionListener uploadSessionListener) {
        this.plugin = plugin;
        this.clipboardManager = clipboardManager;
        this.clipboardSerializer = clipboardSerializer;
        this.cipher = cipher;
        this.transferConfig = transferConfig;
        this.uploadSessionListener = uploadSessionListener;
        this.logger = plugin.getLogger();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] data) {
        if (!Constants.CHANNEL.equals(channel)) return;

        ParsedMessage msg = ProtocolCodec.decode(data);
        if (msg == null) {
            logger.warning("Received invalid protocol message from proxy for " + player.getName());
            return;
        }

        try {
            switch (msg.type()) {
                case SYNC_HASH -> handleSyncHash(player, msg);
                case SYNC_NO_DATA -> handleSyncNoData(player);
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
        String remoteHash = in.readUTF();

        var playerId = player.getUniqueId();

        // Accept transition from PENDING_SYNC (initial join gate) or IDLE (server switch)
        if (!clipboardManager.transitionFromPendingOrIdle(playerId, SyncState.CHECKING)) {
            logger.fine("Ignoring SYNC_HASH for " + player.getName() + ": not in PENDING_SYNC or IDLE state");
            return;
        }

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

        byte[] requestMsg = ProtocolCodec.encodeDownloadRequest();
        player.sendPluginMessage(plugin, Constants.CHANNEL, requestMsg);
        logger.fine("Hash mismatch for " + player.getName() + ", requesting download.");
    }

    private void handleSyncNoData(Player player) {
        // Proxy has no data: release PENDING_SYNC or IDLE gate unconditionally so the watcher can upload
        clipboardManager.forceSetState(player.getUniqueId(), SyncState.IDLE);
        logger.fine("Proxy has no clipboard data for " + player.getName());
    }

    private void handleUploadReady(Player player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        uploadSessionListener.onUploadReady(player, in.readUTF());
    }

    private void handleUploadAck(Player player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        uploadSessionListener.onUploadAcknowledged(player, in.readUTF());
    }

    private void handleDownloadBegin(Player player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String sessionId = in.readUTF();
        int totalBytes = in.readInt();
        int totalChunks = in.readInt();
        String hash = in.readUTF();

        var playerId = player.getUniqueId();
        long maxPayloadSize = (long) transferConfig.getMaxClipboardSize()
                + MessageCipher.ENCRYPTION_OVERHEAD_BYTES;
        if (sessionId.isBlank() || hash.isBlank() || totalBytes > maxPayloadSize
                || !TransferSession.isValidLayout(totalBytes, totalChunks, transferConfig.getChunkSize())) {
            logger.warning("Rejected invalid download session " + sessionId + " for " + player.getName());
            clipboardManager.forceSetState(playerId, SyncState.IDLE);
            player.sendPluginMessage(plugin, Constants.CHANNEL,
                    ProtocolCodec.encodeCancel(sessionId, "invalid_download"));
            return;
        }

        TransferSession session = new TransferSession(sessionId, totalChunks, totalBytes, hash);
        clipboardManager.addDownloadSession(sessionId, session);
        clipboardManager.setActiveSessionId(playerId, sessionId);

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
        if (chunkLength <= 0 || chunkLength > transferConfig.getChunkSize()) {
            rejectDownload(player, sessionId, "invalid_chunk_length");
            return;
        }
        byte[] chunkData = in.readNBytes(chunkLength);
        if (chunkData.length != chunkLength) {
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

        try {
            session.addChunk(chunkIndex, chunkData);
        } catch (IllegalArgumentException e) {
            logger.warning("Rejected invalid download chunk for " + player.getName()
                    + ": " + e.getMessage());
            rejectDownload(player, sessionId, "invalid_chunk");
            return;
        }

        if (session.tryClaimCompletion()) {
            completeDownload(player, session);
        }
    }

    private void completeDownload(Player player, TransferSession session) {
        var playerId = player.getUniqueId();
        String sessionId = session.getSessionId();

        try {
            byte[] assembled = session.assemble();

            // The advertised hash covers the original unencrypted schematic.
            byte[] decrypted = cipher.decrypt(assembled);
            String actualHash = HashUtil.sha256Hex(decrypted);

            if (!actualHash.equals(session.getExpectedHash())) {
                logger.warning("Hash mismatch after download for " + player.getName()
                        + ": expected " + session.getExpectedHash() + ", got " + actualHash);
                rejectDownload(player, sessionId, "hash_mismatch");
                return;
            }

            // Deserialize and apply clipboard on entity thread
            Clipboard clipboard = clipboardSerializer.deserialize(decrypted);

            SchedulerUtil.runOnEntityThread(plugin, player, () -> {
                if (player.isOnline()) {
                    clipboardSerializer.setPlayerClipboard(player, clipboard);
                    clipboardManager.setLocalHash(playerId, actualHash);
                    logger.info("Clipboard synced for " + player.getName());
                }
                clipboardManager.removeDownloadSession(sessionId);
                clipboardManager.clearActiveSession(playerId);
                clipboardManager.forceSetState(playerId, SyncState.IDLE);

                // Send ACK to proxy
                byte[] ackMsg = ProtocolCodec.encodeDownloadAck(sessionId);
                player.sendPluginMessage(plugin, Constants.CHANNEL, ackMsg);
            });
        } catch (Exception e) {
            logger.severe("Failed to complete download for " + player.getName() + ": " + e.getMessage());
            rejectDownload(player, sessionId, "download_failed");
        }
    }

    private void timeoutDownload(Player player, String sessionId) {
        TransferSession session = clipboardManager.getDownloadSession(sessionId);
        if (session == null || !session.isExpired(transferConfig.getSessionTimeoutMs())) {
            return;
        }

        logger.warning("Clipboard download timed out for " + player.getName()
                + " (session: " + sessionId + ")");
        rejectDownload(player, sessionId, "download_timeout");
    }

    private void rejectDownload(Player player, String sessionId, String reason) {
        var playerId = player.getUniqueId();
        clipboardManager.removeDownloadSession(sessionId);
        if (!sessionId.equals(clipboardManager.getActiveSessionId(playerId))) {
            return;
        }

        clipboardManager.clearActiveSession(playerId);
        clipboardManager.forceSetState(playerId, SyncState.IDLE);
        if (player.isOnline()) {
            SchedulerUtil.runOnEntityThread(plugin, player, () -> {
                if (player.isOnline()) {
                    player.sendPluginMessage(plugin, Constants.CHANNEL,
                            ProtocolCodec.encodeCancel(sessionId, reason));
                }
            });
        }
    }

    private void handleCancel(Player player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String sessionId = in.readUTF();
        String reason = in.readUTF();

        var playerId = player.getUniqueId();
        logger.fine("Transfer cancelled for " + player.getName() + ": " + reason);

        uploadSessionListener.onUploadCancelled(player, sessionId, reason);

        if (clipboardManager.getDownloadSession(sessionId) != null) {
            clipboardManager.removeDownloadSession(sessionId);
            if (sessionId.equals(clipboardManager.getActiveSessionId(playerId))) {
                clipboardManager.clearActiveSession(playerId);
                clipboardManager.forceSetState(playerId, SyncState.IDLE);
            }
        }
    }
}
