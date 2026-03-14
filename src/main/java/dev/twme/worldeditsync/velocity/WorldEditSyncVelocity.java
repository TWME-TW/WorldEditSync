package dev.twme.worldeditsync.velocity;

import java.nio.file.Path;
import java.time.Duration;
import java.util.logging.Logger;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
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
import dev.twme.worldeditsync.velocity.config.VelocityConfig;
import dev.twme.worldeditsync.velocity.handler.MessageHandler;
import dev.twme.worldeditsync.velocity.listener.PlayerListener;
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
        config = new VelocityConfig();
        config.load(dataDirectory, logger);

        store = new ClipboardStore();

        MessageCipher cipher = new MessageCipher(config.getToken());
        if (cipher.isEnabled()) {
            logger.info("Encryption enabled (AES-256-GCM).");
        } else {
            logger.warning("Encryption disabled! Set 'token' in config.yml for secure transfers.");
        }

        // Register channel
        String[] parts = Constants.CHANNEL.split(":");
        channelId = MinecraftChannelIdentifier.create(parts[0], parts[1]);
        server.getChannelRegistrar().register(channelId);

        // Initialize handler
        messageHandler = new MessageHandler(server, store, channelId,
                config.getChunkSize(), config.getChunkSendDelayMs(), logger);

        // Register event listeners
        server.getEventManager().register(this, new PlayerListener(server, store, channelId, logger));

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
        if (!channelId.equals(event.getIdentifier())) return;

        // Only handle messages from backend servers
        if (!(event.getSource() instanceof ServerConnection serverConnection)) return;

        Player player = serverConnection.getPlayer();
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        messageHandler.handleMessage(player, event.getData());
    }
}
