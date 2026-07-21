package dev.twme.worldeditsync.bungeecord;

import java.util.concurrent.TimeUnit;

import dev.twme.worldeditsync.bungeecord.config.BungeeConfig;
import dev.twme.worldeditsync.bungeecord.handler.MessageHandler;
import dev.twme.worldeditsync.bungeecord.storage.ClipboardStore;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.crypto.MessageCipher;
import dev.twme.worldeditsync.common.protocol.PluginMessageCodec;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

public class WorldEditSyncBungee extends Plugin implements Listener {

    private ClipboardStore store;
    private MessageHandler messageHandler;
    private BungeeConfig config;

    @Override
    public void onEnable() {
        // Always consume the private channel, even when configuration is invalid.
        // This keeps proxy misconfiguration from turning it into a client relay.
        getProxy().registerChannel(Constants.CHANNEL);
        getProxy().getPluginManager().registerListener(this, this);

        config = new BungeeConfig();
        config.load(this);
        if (config.getToken().isBlank()) {
            getLogger().severe("A non-empty token is required for secure plugin messaging. "
                    + "WorldEditSync proxy support will not start.");
            return;
        }

        store = new ClipboardStore();

        MessageCipher cipher = new MessageCipher(config.getToken());
        if (cipher.isEnabled()) {
            getLogger().info("Encryption enabled (AES-256-GCM).");
        } else {
            getLogger().warning("Encryption disabled! Set 'token' in config.yml for secure transfers.");
        }

        PluginMessageCodec pluginMessageCodec = new PluginMessageCodec(config.getToken());
        messageHandler = new MessageHandler(this, store, config.getChunkSize(),
                config.getMaxClipboardSize(), config.getChunkSendDelayMs(),
                config.getSessionTimeoutMs(), pluginMessageCodec);

        // Schedule cleanup tasks
        getProxy().getScheduler().schedule(this, () -> {
            store.cleanupExpiredSessions(config.getSessionTimeoutMs());
            store.cleanupExpiredClipboards(config.getClipboardTtlMinutes());
        }, 2, 2, TimeUnit.MINUTES);

        getLogger().info("WorldEditSync BungeeCord proxy enabled.");
    }

    @Override
    public void onDisable() {
        getProxy().unregisterChannel(Constants.CHANNEL);
        if (store != null) {
            store.shutdown();
        }
        getLogger().info("WorldEditSync BungeeCord proxy disabled.");
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        if (!Constants.CHANNEL.equals(event.getTag())) return;
        // Consume this private backend channel in both directions. In particular,
        // never forward a modified client's message to Paper.
        event.setCancelled(true);

        if (!(event.getSender() instanceof Server backend)) return;

        if (!(event.getReceiver() instanceof ProxiedPlayer player)) return;
        if (player.getServer() != backend) return;
        if (messageHandler == null) return;

        messageHandler.handleMessage(player, event.getData());
    }

    @EventHandler
    public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        if (messageHandler != null) {
            messageHandler.removePlayer(event.getPlayer().getUniqueId());
        }
    }
}
