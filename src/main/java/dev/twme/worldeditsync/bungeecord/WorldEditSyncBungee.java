package dev.twme.worldeditsync.bungeecord;

import dev.twme.worldeditsync.bungeecord.clipboard.ClipboardManager;
import dev.twme.worldeditsync.bungeecord.listener.MessageListener;
import dev.twme.worldeditsync.bungeecord.listener.PlayerListener;
import dev.twme.worldeditsync.common.Constants;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

import java.util.concurrent.TimeUnit;

public class WorldEditSyncBungee extends Plugin implements Listener {
    private ClipboardManager clipboardManager;
    private MessageListener messageListener;

    @Override
    public void onEnable() {
        this.clipboardManager = new ClipboardManager();
        this.messageListener = new MessageListener(this);

        // 註冊訊息通道
        getProxy().registerChannel(Constants.CHANNEL);

        // 註冊監聽器
        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getPluginManager().registerListener(this, messageListener);
        getProxy().getPluginManager().registerListener(this, new PlayerListener(this));

        // 定期清理過期的上傳會話
        getProxy().getScheduler().schedule(this,
                clipboardManager::cleanupExpiredSessions, 2L, 2L, TimeUnit.MINUTES);

        getLogger().info("WorldEditSync (BungeeCord) enabled!");
    }

    @Override
    public void onDisable() {
        clipboardManager.cleanup();
        getLogger().info("WorldEditSync (BungeeCord) disabled!");
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getTag().equals(Constants.CHANNEL)) return;
        if (!(event.getReceiver() instanceof ProxiedPlayer player)) return;

        messageListener.onPluginMessageReceived(player, event.getData());
    }

    public ClipboardManager getClipboardManager() {
        return clipboardManager;
    }
}
