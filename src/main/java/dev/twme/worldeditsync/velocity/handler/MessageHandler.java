package dev.twme.worldeditsync.velocity.handler;

import java.io.DataInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;

import dev.twme.worldeditsync.common.model.ClipboardPayload;
import dev.twme.worldeditsync.common.protocol.ProtocolCodec;
import dev.twme.worldeditsync.common.protocol.ProtocolCodec.ParsedMessage;
import dev.twme.worldeditsync.common.protocol.TransferSession;
import dev.twme.worldeditsync.velocity.storage.ClipboardStore;

/**
 * Handles plugin messages from Paper servers on Velocity.
 */
public class MessageHandler {

    private final Object plugin;
    private final ProxyServer server;
    private final ClipboardStore store;
    private final ChannelIdentifier channelId;
    private final int chunkSize;
    private final int maxClipboardSize;
    private final long chunkSendDelayMs;
    private final Logger logger;

    public MessageHandler(Object plugin, ProxyServer server, ClipboardStore store, ChannelIdentifier channelId,
                          int chunkSize, int maxClipboardSize, long chunkSendDelayMs, Logger logger) {
        this.plugin = plugin;
        this.server = server;
        this.store = store;
        this.channelId = channelId;
        this.chunkSize = chunkSize;
        this.maxClipboardSize = maxClipboardSize;
        this.chunkSendDelayMs = chunkSendDelayMs;
        this.logger = logger;
    }

    public void handleMessage(Player player, byte[] data) {
        ParsedMessage msg = ProtocolCodec.decode(data);
        if (msg == null) {
            logger.warn("Invalid protocol message from " + player.getUsername());
            return;
        }

        try {
            switch (msg.type()) {
                case UPLOAD_BEGIN -> handleUploadBegin(player, msg);
                case UPLOAD_CHUNK -> handleUploadChunk(player, msg);
                case DOWNLOAD_REQUEST -> handleDownloadRequest(player);
                case DOWNLOAD_ACK -> handleDownloadAck(player, msg);
                case CANCEL -> handleCancel(player, msg);
                default -> logger.warn("Unexpected message type from Paper: " + msg.type());
            }
        } catch (IOException e) {
            logger.error("Error handling message " + msg.type() + " from " + player.getUsername() + ": " + e.getMessage());
        }
    }

    private void handleUploadBegin(Player player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String sessionId = in.readUTF();
        int totalBytes = in.readInt();
        int totalChunks = in.readInt();
        String hash = in.readUTF();

        if (totalBytes <= 0 || totalBytes > maxClipboardSize) {
            logger.warn("Upload rejected from " + player.getUsername() + ": totalBytes=" + totalBytes + " exceeds limit=" + maxClipboardSize);
            byte[] cancelMsg = ProtocolCodec.encodeCancel(sessionId, "Clipboard too large");
            sendToPlayer(player, cancelMsg);
            return;
        }

        TransferSession session = new TransferSession(sessionId, totalChunks, totalBytes, hash);
        store.addUploadSession(sessionId, player.getUniqueId(), session);

            logger.debug("Upload begin from " + player.getUsername() + ": " + totalBytes + " bytes, " + totalChunks + " chunks");
    }

    private void handleUploadChunk(Player player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String sessionId = in.readUTF();
        int chunkIndex = in.readInt();
        int chunkLength = in.readInt();
        if (chunkLength <= 0 || chunkLength > chunkSize) {
            logger.warn("Chunk rejected from " + player.getUsername() + ": chunkLength=" + chunkLength + " exceeds chunkSize=" + chunkSize);
            byte[] cancelMsg = ProtocolCodec.encodeCancel(sessionId, "Invalid chunk length");
            sendToPlayer(player, cancelMsg);
            store.removeUploadSession(sessionId);
            return;
        }
        byte[] chunkData = in.readNBytes(chunkLength);

        TransferSession session = store.getUploadSession(sessionId);
        if (session == null) {
            logger.warn("Chunk for unknown upload session: " + sessionId);
            return;
        }

        session.addChunk(chunkIndex, chunkData);

        if (session.isComplete()) {
            completeUpload(player, session, sessionId);
        }
    }

    private void completeUpload(Player player, TransferSession session, String sessionId) {
        try {
            byte[] assembled = session.assemble();
            UUID playerId = store.getSessionOwner(sessionId);
            if (playerId == null) playerId = player.getUniqueId();

            store.storeClipboard(playerId, assembled, session.getExpectedHash());
            store.removeUploadSession(sessionId);

            byte[] ackMsg = ProtocolCodec.encodeUploadAck(sessionId);
            sendToPlayer(player, ackMsg);

            logger.debug("Upload complete for " + player.getUsername() + ", hash: " + session.getExpectedHash());
        } catch (Exception e) {
            logger.error("Failed to complete upload for " + player.getUsername() + ": " + e.getMessage());
            store.removeUploadSession(sessionId);
        }
    }

    private void handleDownloadRequest(Player player) {
        UUID playerId = player.getUniqueId();
        ClipboardPayload payload = store.getClipboard(playerId);

        if (payload == null) {
            byte[] noDataMsg = ProtocolCodec.encodeSyncNoData();
            sendToPlayer(player, noDataMsg);
            return;
        }

        sendClipboardToPlayer(player, payload);
    }

    private void sendClipboardToPlayer(Player player, ClipboardPayload payload) {
        byte[] data = payload.getData();
        int totalChunks = (int) Math.ceil((double) data.length / chunkSize);
        String sessionId = UUID.randomUUID().toString();

        byte[] beginMsg = ProtocolCodec.encodeDownloadBegin(sessionId, data.length, totalChunks, payload.getHash());
        sendToPlayer(player, beginMsg);

        for (int i = 0; i < totalChunks; i++) {
            final int chunkIndex = i;
            long delayMs = (long) (i + 1) * chunkSendDelayMs;

            server.getScheduler().buildTask(plugin, () -> {
                if (!player.isActive()) return;

                int offset = chunkIndex * chunkSize;
                int length = Math.min(chunkSize, data.length - offset);
                byte[] chunk = new byte[length];
                System.arraycopy(data, offset, chunk, 0, length);

                byte[] chunkMsg = ProtocolCodec.encodeDownloadChunk(sessionId, chunkIndex, chunk);
                sendToPlayer(player, chunkMsg);
            }).delay(Duration.ofMillis(delayMs)).schedule();
        }
    }

    private void handleDownloadAck(Player player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String sessionId = in.readUTF();
        logger.debug("Download acknowledged by " + player.getUsername() + " session: " + sessionId);
    }

    private void handleCancel(Player player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String sessionId = in.readUTF();
        String reason = in.readUTF();

        store.removeUploadSession(sessionId);
        logger.debug("Transfer cancelled by " + player.getUsername() + ": " + reason);
    }

    private void sendToPlayer(Player player, byte[] data) {
        player.getCurrentServer().ifPresent(serverConnection ->
                serverConnection.sendPluginMessage(channelId, data));
    }
}
