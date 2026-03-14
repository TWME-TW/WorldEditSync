package dev.twme.worldeditsync.paper.sync;

import java.util.UUID;
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

/**
 * Proxy-mode sync engine: uploads/downloads clipboards via BungeeCord/Velocity Plugin Messages.
 */
public class ProxySyncEngine implements SyncEngine {

    private final JavaPlugin plugin;
    private final ClipboardManager clipboardManager;
    private final ClipboardSerializer clipboardSerializer;
    private final MessageCipher cipher;
    private final TransferConfig transferConfig;
    private final Logger logger;

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
        messageHandler = new PluginMessageHandler(plugin, clipboardManager, clipboardSerializer, cipher, transferConfig);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, Constants.CHANNEL, messageHandler);
        logger.info("Proxy sync engine started on channel: " + Constants.CHANNEL);
    }

    @Override
    public void shutdown() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, Constants.CHANNEL);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, Constants.CHANNEL);
        logger.info("Proxy sync engine shut down.");
    }

    @Override
    public void uploadClipboard(Player player, byte[] data, String hash) {
        UUID playerId = player.getUniqueId();

        if (!clipboardManager.compareAndSetState(playerId, SyncState.IDLE, SyncState.UPLOADING)) {
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

        // Send UPLOAD_BEGIN
        byte[] beginMsg = ProtocolCodec.encodeUploadBegin(sessionId, payload.length, totalChunks, hash);
        player.sendPluginMessage(plugin, Constants.CHANNEL, beginMsg);

        // Send chunks asynchronously
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                for (int i = 0; i < totalChunks; i++) {
                    if (!player.isOnline()) {
                        logger.fine("Player " + player.getName() + " went offline during upload, cancelling.");
                        clipboardManager.clearActiveSession(playerId);
                        clipboardManager.forceSetState(playerId, SyncState.IDLE);
                        return;
                    }

                    int offset = i * chunkSize;
                    int length = Math.min(chunkSize, payload.length - offset);
                    byte[] chunk = new byte[length];
                    System.arraycopy(payload, offset, chunk, 0, length);

                    byte[] chunkMsg = ProtocolCodec.encodeUploadChunk(sessionId, i, chunk);

                    final int chunkIndex = i;
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            player.sendPluginMessage(plugin, Constants.CHANNEL, chunkMsg));

                    if (transferConfig.getChunkSendDelayMs() > 0 && i < totalChunks - 1) {
                        Thread.sleep(transferConfig.getChunkSendDelayMs());
                    }
                }
                // State will transition to IDLE when UPLOAD_ACK is received from proxy
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                clipboardManager.forceSetState(playerId, SyncState.IDLE);
                clipboardManager.clearActiveSession(playerId);
            }
        });
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
}
