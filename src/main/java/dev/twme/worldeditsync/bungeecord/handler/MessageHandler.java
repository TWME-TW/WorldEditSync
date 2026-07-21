package dev.twme.worldeditsync.bungeecord.handler;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import dev.twme.worldeditsync.bungeecord.storage.ClipboardStore;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.crypto.MessageCipher;
import dev.twme.worldeditsync.common.model.ClipboardPayload;
import dev.twme.worldeditsync.common.protocol.InboundMessageLimiter;
import dev.twme.worldeditsync.common.protocol.PluginMessageCodec;
import dev.twme.worldeditsync.common.protocol.ProtocolCodec;
import dev.twme.worldeditsync.common.protocol.ProtocolCodec.ParsedMessage;
import dev.twme.worldeditsync.common.protocol.ProtocolValidation;
import dev.twme.worldeditsync.common.protocol.TransferSession;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;

/**
 * Handles plugin messages from Paper servers on BungeeCord.
 */
public class MessageHandler {

    private final Plugin plugin;
    private final ClipboardStore store;
    private final int chunkSize;
    private final int maxClipboardSize;
    private final long chunkSendDelayMs;
    private final long sessionTimeoutMs;
    private final PluginMessageCodec pluginMessageCodec;
    private final Logger logger;
    private final InboundMessageLimiter inboundMessageLimiter = new InboundMessageLimiter();
    private final ConcurrentHashMap<UUID, Long> invalidMessageWarnings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> pendingSyncRequests = new ConcurrentHashMap<>();

    public MessageHandler(Plugin plugin, ClipboardStore store, int chunkSize, int maxClipboardSize,
                          long chunkSendDelayMs, long sessionTimeoutMs,
                          PluginMessageCodec pluginMessageCodec) {
        this.plugin = plugin;
        this.store = store;
        this.chunkSize = chunkSize;
        this.maxClipboardSize = maxClipboardSize;
        this.chunkSendDelayMs = chunkSendDelayMs;
        this.sessionTimeoutMs = sessionTimeoutMs;
        this.pluginMessageCodec = pluginMessageCodec;
        this.logger = plugin.getLogger();
    }

    public void handleMessage(ProxiedPlayer player, byte[] data) {
        int messageLength = data == null ? -1 : data.length;
        if (!inboundMessageLimiter.tryAcquire(player.getUniqueId(), messageLength)) {
            warnInvalidMessage(player, "Rate-limited protocol messages from ");
            return;
        }
        ParsedMessage msg = pluginMessageCodec.decode(data);
        if (msg == null) {
            warnInvalidMessage(player, "Invalid protocol message from ");
            return;
        }

        try {
            switch (msg.type()) {
                case SYNC_REQUEST -> handleSyncRequest(player, msg);
                case UPLOAD_BEGIN -> handleUploadBegin(player, msg);
                case UPLOAD_CHUNK -> handleUploadChunk(player, msg);
                case DOWNLOAD_REQUEST -> handleDownloadRequest(player, msg);
                case DOWNLOAD_ACK -> handleDownloadAck(player, msg);
                case CANCEL -> handleCancel(player, msg);
                default -> logger.warning("Unexpected message type from Paper: " + msg.type());
            }
        } catch (Exception e) {
            logger.severe("Error handling message " + msg.type() + " from " + player.getName() + ": " + e.getMessage());
        }
    }

    private void handleUploadBegin(ProxiedPlayer player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String sessionId = in.readUTF();
        int totalBytes = in.readInt();
        int totalChunks = in.readInt();
        String hash = in.readUTF();

        long maxPayloadSize = (long) maxClipboardSize + MessageCipher.ENCRYPTION_OVERHEAD_BYTES;
        if (!ProtocolValidation.isSessionId(sessionId)
                || !ProtocolValidation.isSha256(hash)
                || !ProtocolValidation.exhausted(in)
                || totalBytes > maxPayloadSize
                || !TransferSession.isValidLayout(totalBytes, totalChunks, chunkSize)) {
            logger.warning("Upload rejected from " + player.getName()
                    + ": invalid transfer layout (" + totalBytes + " bytes, " + totalChunks + " chunks)");
            if (ProtocolValidation.isSessionId(sessionId)) {
                sendToPlayer(player, ProtocolCodec.encodeCancel(sessionId, "invalid_upload"));
            }
            return;
        }

        TransferSession session = new TransferSession(sessionId, totalChunks, totalBytes, hash);
        if (!store.addUploadSession(sessionId, player.getUniqueId(), session)) {
            sendToPlayer(player, ProtocolCodec.encodeCancel(sessionId, "duplicate_session"));
            return;
        }

        sendToPlayer(player, ProtocolCodec.encodeUploadReady(sessionId));
        logger.fine("Upload begin from " + player.getName() + ": " + totalBytes + " bytes, " + totalChunks + " chunks");
    }

    private void handleUploadChunk(ProxiedPlayer player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String sessionId = in.readUTF();
        int chunkIndex = in.readInt();
        int chunkLength = in.readInt();
        if (!ProtocolValidation.isSessionId(sessionId)
                || chunkIndex < 0
                || chunkLength <= 0
                || chunkLength > chunkSize) {
            logger.warning("Chunk rejected from " + player.getName() + ": chunkLength=" + chunkLength + " exceeds chunkSize=" + chunkSize);
            if (ProtocolValidation.isSessionId(sessionId)) {
                sendToPlayer(player, ProtocolCodec.encodeCancel(sessionId, "invalid_chunk_length"));
                store.removeUploadSession(sessionId, player.getUniqueId());
            }
            return;
        }
        byte[] chunkData = in.readNBytes(chunkLength);
        if (chunkData.length != chunkLength || !ProtocolValidation.exhausted(in)) {
            rejectUpload(player, sessionId, "Truncated upload chunk");
            return;
        }

        TransferSession session = store.getUploadSession(sessionId);
        if (session == null) {
            logger.warning("Chunk for unknown upload session: " + sessionId);
            return;
        }
        if (!player.getUniqueId().equals(store.getSessionOwner(sessionId))) {
            logger.warning("Chunk owner mismatch for upload session: " + sessionId);
            return;
        }
        if (chunkIndex >= session.getTotalChunks()) {
            rejectUpload(player, sessionId, "invalid_chunk_index");
            return;
        }
        int expectedLength = Math.min(chunkSize, session.getTotalBytes() - chunkIndex * chunkSize);
        if (chunkLength != expectedLength) {
            rejectUpload(player, sessionId, "invalid_chunk_layout");
            return;
        }

        try {
            session.addChunk(chunkIndex, chunkData);
        } catch (IllegalArgumentException e) {
            rejectUpload(player, sessionId, e.getMessage());
            return;
        }

        if (session.tryClaimCompletion()) {
            completeUpload(player, session, sessionId);
        }
    }

    private void completeUpload(ProxiedPlayer player, TransferSession session, String sessionId) {
        UUID owner = store.getSessionOwner(sessionId);
        UUID playerId = owner != null ? owner : player.getUniqueId();
        String playerName = player.getName();
        plugin.getProxy().getScheduler().runAsync(plugin, () -> {
            try {
                byte[] assembled = session.assemble();
                if (!store.completeUploadSession(
                        sessionId, playerId, session, assembled, session.getExpectedHash())) {
                    logger.fine("Ignoring stale completed upload for " + playerName
                            + " (session: " + sessionId + ")");
                    return;
                }
                sendToPlayer(player, ProtocolCodec.encodeUploadAck(sessionId));
                logger.fine("Upload complete for " + playerName + ", hash: " + session.getExpectedHash());
            } catch (Exception e) {
                logger.severe("Failed to complete upload for " + playerName + ": " + e.getMessage());
                store.removeUploadSession(sessionId, playerId, session);
                sendToPlayer(player, ProtocolCodec.encodeCancel(sessionId, "upload_failed"));
            }
        });
    }

    private void rejectUpload(ProxiedPlayer player, String sessionId, String reason) {
        store.removeUploadSession(sessionId, player.getUniqueId());
        sendToPlayer(player, ProtocolCodec.encodeCancel(sessionId, reason));
        logger.warning("Upload session rejected from " + player.getName()
                + " (session: " + sessionId + "): " + reason);
    }

    private void handleSyncRequest(ProxiedPlayer player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String requestId = in.readUTF();
        if (!ProtocolValidation.isSessionId(requestId) || !ProtocolValidation.exhausted(in)) {
            logger.warning("Malformed sync request from " + player.getName());
            return;
        }

        String previous = pendingSyncRequests.put(player.getUniqueId(), requestId);
        if (requestId.equals(previous)) {
            return;
        }
        respondToSyncRequest(player, requestId, System.currentTimeMillis() + sessionTimeoutMs);
    }

    private void respondToSyncRequest(ProxiedPlayer player, String requestId, long deadline) {
        UUID playerId = player.getUniqueId();
        if (!requestId.equals(pendingSyncRequests.get(playerId))) {
            return;
        }
        if (!player.isConnected()) {
            pendingSyncRequests.remove(playerId, requestId);
            return;
        }
        TransferSession activeUpload = store.getUploadSessionForOwner(playerId);
        if (activeUpload != null && !activeUpload.isComplete()) {
            pendingSyncRequests.remove(playerId, requestId);
            store.removeUploadSessionForOwner(playerId);
            respondWithStoredClipboard(player, requestId);
            logger.warning("Discarded an incomplete upload before answering initial sync for "
                    + player.getName());
            return;
        }
        if (activeUpload != null) {
            if (System.currentTimeMillis() < deadline) {
                plugin.getProxy().getScheduler().schedule(plugin,
                        () -> respondToSyncRequest(player, requestId, deadline),
                        50L, TimeUnit.MILLISECONDS);
            } else {
                pendingSyncRequests.remove(playerId, requestId);
                store.removeUploadSessionForOwner(playerId);
                respondWithStoredClipboard(player, requestId);
                logger.warning("Discarded a stalled upload before answering initial sync for "
                        + player.getName());
            }
            return;
        }
        if (!pendingSyncRequests.remove(playerId, requestId)) {
            return;
        }

        respondWithStoredClipboard(player, requestId);
    }

    private void respondWithStoredClipboard(ProxiedPlayer player, String requestId) {
        ClipboardPayload payload = store.getClipboard(player.getUniqueId());
        if (payload != null) {
            sendToPlayer(player, ProtocolCodec.encodeSyncHash(requestId, payload.getHash()));
        } else {
            sendToPlayer(player, ProtocolCodec.encodeSyncNoData(requestId));
        }
    }

    private void handleDownloadRequest(ProxiedPlayer player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String requestId = in.readUTF();
        if (!ProtocolValidation.isSessionId(requestId) || !ProtocolValidation.exhausted(in)) {
            logger.warning("Malformed download request from " + player.getName());
            return;
        }
        UUID playerId = player.getUniqueId();
        ClipboardPayload payload = store.getClipboard(playerId);

        if (payload == null) {
            sendToPlayer(player, ProtocolCodec.encodeCancel(requestId, "clipboard_not_found"));
            return;
        }

        sendClipboardToPlayer(player, requestId, payload);
    }

    private void sendClipboardToPlayer(ProxiedPlayer player, String requestId,
                                       ClipboardPayload payload) {
        Server destination = player.getServer();
        if (destination == null) {
            return;
        }
        byte[] data = payload.getData();
        int totalChunks = (int) Math.ceil((double) data.length / chunkSize);
        String sessionId = UUID.randomUUID().toString();

        byte[] beginMsg = ProtocolCodec.encodeDownloadBegin(
                requestId, sessionId, data.length, totalChunks, payload.getHash());
        sendToServer(destination, beginMsg);
        scheduleDownloadPump(player, destination, data, sessionId, totalChunks, 0);
    }

    private void scheduleDownloadPump(ProxiedPlayer player, Server destination, byte[] data,
                                      String sessionId, int totalChunks, int nextChunk) {
        plugin.getProxy().getScheduler().schedule(plugin, () -> {
            if (!player.isConnected() || player.getServer() != destination) {
                return;
            }

            int chunkIndex = nextChunk;
            int limit = Math.min(totalChunks, chunkIndex + chunksPerPump());
            while (chunkIndex < limit) {
                int offset = chunkIndex * chunkSize;
                int length = Math.min(chunkSize, data.length - offset);
                byte[] chunk = new byte[length];
                System.arraycopy(data, offset, chunk, 0, length);
                sendToServer(destination,
                        ProtocolCodec.encodeDownloadChunk(sessionId, chunkIndex, chunk));
                chunkIndex++;
            }
            if (chunkIndex < totalChunks) {
                scheduleDownloadPump(player, destination, data, sessionId, totalChunks, chunkIndex);
            }
        }, pumpIntervalMs(), TimeUnit.MILLISECONDS);
    }

    private int chunksPerPump() {
        if (chunkSendDelayMs <= 0) {
            return Constants.MAX_CHUNKS_PER_TICK;
        }
        return Math.max(1, Math.min(Constants.MAX_CHUNKS_PER_TICK, (int) (50L / chunkSendDelayMs)));
    }

    private long pumpIntervalMs() {
        return Math.max(50L, Math.max(1L, chunkSendDelayMs) * chunksPerPump());
    }

    private void handleDownloadAck(ProxiedPlayer player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String sessionId = in.readUTF();
        if (!ProtocolValidation.isSessionId(sessionId) || !ProtocolValidation.exhausted(in)) {
            logger.warning("Malformed download acknowledgement from " + player.getName());
            return;
        }
        logger.fine("Download acknowledged by " + player.getName() + " session: " + sessionId);
    }

    private void handleCancel(ProxiedPlayer player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String sessionId = in.readUTF();
        String reason = in.readUTF();
        if (!ProtocolValidation.isSessionId(sessionId)
                || !ProtocolValidation.isReason(reason)
                || !ProtocolValidation.exhausted(in)) {
            logger.warning("Malformed cancellation from " + player.getName());
            return;
        }

        store.removeUploadSession(sessionId, player.getUniqueId());
        logger.fine("Transfer cancelled by " + player.getName() + ": " + reason);
    }

    private void sendToPlayer(ProxiedPlayer player, byte[] data) {
        if (player.isConnected() && player.getServer() != null) {
            sendToServer(player.getServer(), data);
        }
    }

    private void sendToServer(Server server, byte[] protocolMessage) {
        server.sendData(Constants.CHANNEL, pluginMessageCodec.encode(protocolMessage));
    }

    public void removePlayer(UUID playerId) {
        pendingSyncRequests.remove(playerId);
        inboundMessageLimiter.remove(playerId);
        invalidMessageWarnings.remove(playerId);
        store.removeIncompleteUploadSessionForOwner(playerId);
    }

    private void warnInvalidMessage(ProxiedPlayer player, String prefix) {
        long now = System.currentTimeMillis();
        Long previous = invalidMessageWarnings.put(player.getUniqueId(), now);
        if (previous == null || now - previous >= 5_000L) {
            logger.warning(prefix + player.getName());
        }
    }
}
