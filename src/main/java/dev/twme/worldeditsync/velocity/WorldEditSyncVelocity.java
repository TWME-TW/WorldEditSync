package dev.twme.worldeditsync.velocity;

import java.nio.file.Path;
import java.time.Duration;

import org.slf4j.Logger;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.crypto.MessageCipher;
import dev.twme.worldeditsync.common.protocol.PluginMessageCodec;
import dev.twme.worldeditsync.velocity.config.VelocityConfig;
import dev.twme.worldeditsync.velocity.handler.MessageHandler;
import dev.twme.worldeditsync.velocity.storage.ClipboardStore;

@Plugin(id = "worldeditsync", name = "WorldEditSync", version = "0.1.0",
        description = "Synchronize WorldEdit clipboards across servers",
        authors = {"TWME"})
public class WorldEditSyncVelocity {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private ClipboardStore store;
    private ChannelIdentifier channelId;
    private MessageHandler messageHandler;
    private VelocityConfig config;

    @Inject
    public WorldEditSyncVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        // Always consume the private channel, even when configuration is invalid.
        // This keeps proxy misconfiguration from turning it into a client relay.
        String[] parts = Constants.CHANNEL.split(":");
        channelId = MinecraftChannelIdentifier.create(parts[0], parts[1]);
        server.getChannelRegistrar().register(channelId);

        config = new VelocityConfig();
        config.load(dataDirectory, logger);
        if (config.getToken().isBlank()) {
            logger.error("A non-empty token is required for secure plugin messaging. "
                    + "WorldEditSync proxy support will not start.");
            return;
        }

        store = new ClipboardStore();

        MessageCipher cipher = new MessageCipher(config.getToken());
        if (cipher.isEnabled()) {
            logger.info("Encryption enabled (AES-256-GCM).");
        } else {
            logger.warn("Encryption disabled! Set 'token' in config.yml for secure transfers.");
        }

        // Initialize handler
        PluginMessageCodec pluginMessageCodec = new PluginMessageCodec(config.getToken());
        messageHandler = new MessageHandler(this, server, store, channelId,
                config.getChunkSize(), config.getMaxClipboardSize(), config.getChunkSendDelayMs(),
                config.getSessionTimeoutMs(), pluginMessageCodec, logger);

        // Schedule cleanup
        server.getScheduler().buildTask(this, () -> {
            store.cleanupExpiredSessions(config.getSessionTimeoutMs());
            store.cleanupExpiredClipboards(config.getClipboardTtlMinutes());
        }).repeat(Duration.ofMinutes(2)).schedule();

        logger.info("WorldEditSync Velocity proxy enabled.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (channelId != null) {
            server.getChannelRegistrar().unregister(channelId);
        }
        if (store != null) {
            store.shutdown();
        }
        logger.info("WorldEditSync Velocity proxy disabled.");
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (channelId == null || !channelId.equals(event.getIdentifier())) return;
        // Consume this private backend channel before checking direction so a
        // modified client's message can never be forwarded to Paper.
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (messageHandler == null) return;

        if (!(event.getSource() instanceof ServerConnection serverConnection)) return;

        Player player = serverConnection.getPlayer();
        if (player.getCurrentServer().filter(serverConnection::equals).isEmpty()) return;
        messageHandler.handleMessage(player, event.getData());
    }

    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        if (messageHandler != null) {
            messageHandler.removePlayer(event.getPlayer().getUniqueId());
        }
    }
}
