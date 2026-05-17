package dev.twme.worldeditsync.bungeecord.handler;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import dev.twme.worldeditsync.bungeecord.storage.ClipboardStore;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.model.ClipboardPayload;
import dev.twme.worldeditsync.common.protocol.ProtocolCodec;
import dev.twme.worldeditsync.common.protocol.ProtocolCodec.ParsedMessage;
import dev.twme.worldeditsync.common.protocol.TransferSession;
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
    private final Logger logger;

    public MessageHandler(Plugin plugin, ClipboardStore store, int chunkSize, int maxClipboardSize, long chunkSendDelayMs) {
        this.plugin = plugin;
        this.store = store;
        this.chunkSize = chunkSize;
        this.maxClipboardSize = maxClipboardSize;
        this.chunkSendDelayMs = chunkSendDelayMs;
        this.logger = plugin.getLogger();
    }

    public void handleMessage(ProxiedPlayer player, byte[] data) {
        ParsedMessage msg = ProtocolCodec.decode(data);
        if (msg == null) {
            logger.warning("Invalid protocol message from " + player.getName());
            return;
        }

        try {
            switch (msg.type()) {
                case UPLOAD_BEGIN -> handleUploadBegin(player, msg);
                case UPLOAD_CHUNK -> handleUploadChunk(player, msg);
                case DOWNLOAD_REQUEST -> handleDownloadRequest(player);
                case DOWNLOAD_ACK -> handleDownloadAck(player, msg);
                case CANCEL -> handleCancel(player, msg);
                default -> logger.warning("Unexpected message type from Paper: " + msg.type());
            }
        } catch (IOException e) {
            logger.severe("Error handling message " + msg.type() + " from " + player.getName() + ": " + e.getMessage());
        }
    }

    private void handleUploadBegin(ProxiedPlayer player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String sessionId = in.readUTF();
        int totalBytes = in.readInt();
        int totalChunks = in.readInt();
        String hash = in.readUTF();

        if (totalBytes <= 0 || totalBytes > maxClipboardSize) {
            logger.warning("Upload rejected from " + player.getName() + ": totalBytes=" + totalBytes + " exceeds limit=" + maxClipboardSize);
            byte[] cancelMsg = ProtocolCodec.encodeCancel(sessionId, "Clipboard too large");
            sendToPlayer(player, cancelMsg);
            return;
        }

        TransferSession session = new TransferSession(sessionId, totalChunks, totalBytes, hash);
        store.addUploadSession(sessionId, player.getUniqueId(), session);

        logger.fine("Upload begin from " + player.getName() + ": " + totalBytes + " bytes, " + totalChunks + " chunks");
    }

    private void handleUploadChunk(ProxiedPlayer player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String sessionId = in.readUTF();
        int chunkIndex = in.readInt();
        int chunkLength = in.readInt();
        if (chunkLength <= 0 || chunkLength > chunkSize) {
            logger.warning("Chunk rejected from " + player.getName() + ": chunkLength=" + chunkLength + " exceeds chunkSize=" + chunkSize);
            byte[] cancelMsg = ProtocolCodec.encodeCancel(sessionId, "Invalid chunk length");
            sendToPlayer(player, cancelMsg);
            store.removeUploadSession(sessionId);
            return;
        }
        byte[] chunkData = in.readNBytes(chunkLength);

        TransferSession session = store.getUploadSession(sessionId);
        if (session == null) {
            logger.warning("Chunk for unknown upload session: " + sessionId);
            return;
        }

        session.addChunk(chunkIndex, chunkData);

        if (session.isComplete()) {
            completeUpload(player, session, sessionId);
        }
    }

    private void completeUpload(ProxiedPlayer player, TransferSession session, String sessionId) {
        try {
            byte[] assembled = session.assemble();
            UUID playerId = store.getSessionOwner(sessionId);
            if (playerId == null) playerId = player.getUniqueId();

            store.storeClipboard(playerId, assembled, session.getExpectedHash());
            store.removeUploadSession(sessionId);

            // Send ACK back to Paper
            byte[] ackMsg = ProtocolCodec.encodeUploadAck(sessionId);
            sendToPlayer(player, ackMsg);

            logger.fine("Upload complete for " + player.getName() + ", hash: " + session.getExpectedHash());
        } catch (Exception e) {
            logger.severe("Failed to complete upload for " + player.getName() + ": " + e.getMessage());
            store.removeUploadSession(sessionId);
        }
    }

    private void handleDownloadRequest(ProxiedPlayer player) {
        UUID playerId = player.getUniqueId();
        ClipboardPayload payload = store.getClipboard(playerId);

        if (payload == null) {
            byte[] noDataMsg = ProtocolCodec.encodeSyncNoData();
            sendToPlayer(player, noDataMsg);
            return;
        }

        sendClipboardToPlayer(player, payload);
    }

    private void sendClipboardToPlayer(ProxiedPlayer player, ClipboardPayload payload) {
        byte[] data = payload.getData();
        int totalChunks = (int) Math.ceil((double) data.length / chunkSize);
        String sessionId = UUID.randomUUID().toString();

        byte[] beginMsg = ProtocolCodec.encodeDownloadBegin(sessionId, data.length, totalChunks, payload.getHash());
        sendToPlayer(player, beginMsg);

        // Send chunks with delay
        for (int i = 0; i < totalChunks; i++) {
            final int chunkIndex = i;
            long delayMs = (long) (i + 1) * chunkSendDelayMs;

            plugin.getProxy().getScheduler().schedule(plugin, () -> {
                if (!player.isConnected()) return;

                int offset = chunkIndex * chunkSize;
                int length = Math.min(chunkSize, data.length - offset);
                byte[] chunk = new byte[length];
                System.arraycopy(data, offset, chunk, 0, length);

                byte[] chunkMsg = ProtocolCodec.encodeDownloadChunk(sessionId, chunkIndex, chunk);
                sendToPlayer(player, chunkMsg);
            }, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void handleDownloadAck(ProxiedPlayer player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String sessionId = in.readUTF();
        logger.fine("Download acknowledged by " + player.getName() + " session: " + sessionId);
    }

    private void handleCancel(ProxiedPlayer player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String sessionId = in.readUTF();
        String reason = in.readUTF();

        store.removeUploadSession(sessionId);
        logger.fine("Transfer cancelled by " + player.getName() + ": " + reason);
    }

    private void sendToPlayer(ProxiedPlayer player, byte[] data) {
        if (player.getServer() != null) {
            player.getServer().getInfo().sendData(Constants.CHANNEL, data);
        }
    }
}
