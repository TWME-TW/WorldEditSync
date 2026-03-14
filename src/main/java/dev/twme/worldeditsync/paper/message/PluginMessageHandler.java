package dev.twme.worldeditsync.paper.message;

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
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Handles incoming plugin messages from the proxy in Proxy mode.
 */
public class PluginMessageHandler implements PluginMessageListener {

    private final JavaPlugin plugin;
    private final ClipboardManager clipboardManager;
    private final ClipboardSerializer clipboardSerializer;
    private final MessageCipher cipher;
    private final TransferConfig transferConfig;
    private final Logger logger;

    public PluginMessageHandler(JavaPlugin plugin, ClipboardManager clipboardManager,
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
                case UPLOAD_ACK -> handleUploadAck(player, msg);
                case DOWNLOAD_BEGIN -> handleDownloadBegin(player, msg);
                case DOWNLOAD_CHUNK -> handleDownloadChunk(player, msg);
                case CANCEL -> handleCancel(player, msg);
                default -> logger.warning("Unexpected message type from proxy: " + msg.type());
            }
        } catch (IOException e) {
            logger.severe("Error handling message " + msg.type() + " for " + player.getName() + ": " + e.getMessage());
        }
    }

    private void handleSyncHash(Player player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String remoteHash = in.readUTF();

        var playerId = player.getUniqueId();

        if (!clipboardManager.compareAndSetState(playerId, SyncState.IDLE, SyncState.CHECKING)) {
            logger.fine("Ignoring SYNC_HASH for " + player.getName() + ": not idle");
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
        clipboardManager.forceSetState(player.getUniqueId(), SyncState.IDLE);
        logger.fine("Proxy has no clipboard data for " + player.getName());
    }

    private void handleUploadAck(Player player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String sessionId = in.readUTF();

        var playerId = player.getUniqueId();
        String activeSession = clipboardManager.getActiveSessionId(playerId);

        if (sessionId.equals(activeSession)) {
            clipboardManager.clearActiveSession(playerId);
            clipboardManager.forceSetState(playerId, SyncState.IDLE);
            logger.fine("Upload acknowledged for " + player.getName() + " session: " + sessionId);
        }
    }

    private void handleDownloadBegin(Player player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String sessionId = in.readUTF();
        int totalBytes = in.readInt();
        int totalChunks = in.readInt();
        String hash = in.readUTF();

        var playerId = player.getUniqueId();

        TransferSession session = new TransferSession(sessionId, totalChunks, totalBytes, hash);
        clipboardManager.addDownloadSession(sessionId, session);
        clipboardManager.setActiveSessionId(playerId, sessionId);

        logger.fine("Download begin for " + player.getName() + ": " + totalBytes + " bytes, " + totalChunks + " chunks");
    }

    private void handleDownloadChunk(Player player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String sessionId = in.readUTF();
        int chunkIndex = in.readInt();
        int chunkLength = in.readInt();
        byte[] chunkData = in.readNBytes(chunkLength);

        TransferSession session = clipboardManager.getDownloadSession(sessionId);
        if (session == null) {
            logger.warning("Received chunk for unknown session: " + sessionId);
            return;
        }

        session.addChunk(chunkIndex, chunkData);

        if (session.isComplete()) {
            completeDownload(player, session);
        }
    }

    private void completeDownload(Player player, TransferSession session) {
        var playerId = player.getUniqueId();
        String sessionId = session.getSessionId();

        try {
            byte[] assembled = session.assemble();

            // Verify hash before decryption (hash is of the original unencrypted data)
            byte[] decrypted = cipher.decrypt(assembled);
            String actualHash = HashUtil.sha256Hex(decrypted);

            if (!actualHash.equals(session.getExpectedHash())) {
                logger.warning("Hash mismatch after download for " + player.getName()
                        + ": expected " + session.getExpectedHash() + ", got " + actualHash);
                clipboardManager.removeDownloadSession(sessionId);
                clipboardManager.clearActiveSession(playerId);
                clipboardManager.forceSetState(playerId, SyncState.IDLE);
                return;
            }

            // Deserialize and apply clipboard on main thread
            Clipboard clipboard = clipboardSerializer.deserialize(decrypted);

            plugin.getServer().getScheduler().runTask(plugin, () -> {
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
            clipboardManager.removeDownloadSession(sessionId);
            clipboardManager.clearActiveSession(playerId);
            clipboardManager.forceSetState(playerId, SyncState.IDLE);
        }
    }

    private void handleCancel(Player player, ParsedMessage msg) throws IOException {
        DataInputStream in = ProtocolCodec.payloadStream(msg);
        String sessionId = in.readUTF();
        String reason = in.readUTF();

        var playerId = player.getUniqueId();
        logger.fine("Transfer cancelled for " + player.getName() + ": " + reason);

        clipboardManager.removeDownloadSession(sessionId);
        clipboardManager.clearActiveSession(playerId);
        clipboardManager.forceSetState(playerId, SyncState.IDLE);
    }
}
