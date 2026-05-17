package dev.twme.worldeditsync.bungeecord;

import java.util.concurrent.TimeUnit;

import dev.twme.worldeditsync.bungeecord.config.BungeeConfig;
import dev.twme.worldeditsync.bungeecord.handler.MessageHandler;
import dev.twme.worldeditsync.bungeecord.listener.PlayerListener;
import dev.twme.worldeditsync.bungeecord.storage.ClipboardStore;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.crypto.MessageCipher;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

public class WorldEditSyncBungee extends Plugin implements Listener {

    private ClipboardStore store;
    private MessageHandler messageHandler;
    private BungeeConfig config;

    @Override
    public void onEnable() {
        config = new BungeeConfig();
        config.load(this);

        store = new ClipboardStore();

        MessageCipher cipher = new MessageCipher(config.getToken());
        if (cipher.isEnabled()) {
            getLogger().info("Encryption enabled (AES-256-GCM).");
        } else {
            getLogger().warning("Encryption disabled! Set 'token' in config.yml for secure transfers.");
        }

        messageHandler = new MessageHandler(this, store, config.getChunkSize(), config.getMaxClipboardSize(), config.getChunkSendDelayMs());

        // Register plugin message channel
        getProxy().registerChannel(Constants.CHANNEL);

        // Register listeners
        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getPluginManager().registerListener(this, new PlayerListener(this, store));

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

        // Only handle messages from backend servers (sent by players)
        if (!(event.getSender() instanceof net.md_5.bungee.api.connection.Server)) return;

        // Find the player who sent this message
        if (!(event.getReceiver() instanceof ProxiedPlayer player)) return;

        event.setCancelled(true);
        messageHandler.handleMessage(player, event.getData());
    }
}
