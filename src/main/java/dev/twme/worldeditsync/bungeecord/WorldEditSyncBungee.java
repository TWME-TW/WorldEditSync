package dev.twme.worldeditsync.bungeecord;

import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.bungeecord.clipboard.ClipboardManager;
import dev.twme.worldeditsync.bungeecord.listener.MessageListener;
import dev.twme.worldeditsync.bungeecord.listener.PlayerListener;
import net.md_5.bungee.event.EventHandler;

import java.util.concurrent.TimeUnit;

public class WorldEditSyncBungee extends Plugin implements Listener {
    private ClipboardManager clipboardManager;
    private MessageListener messageListener;
    private PlayerListener playerListener;

    @Override
    public void onEnable() {
        // 初始化組件
        this.clipboardManager = new ClipboardManager(this);
        this.messageListener = new MessageListener(this);
        this.playerListener = new PlayerListener(this);

        // 註冊訊息通道
        getProxy().registerChannel(Constants.CHANNEL);

        // 註冊監聽器
        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getPluginManager().registerListener(this, messageListener);
        getProxy().getPluginManager().registerListener(this, playerListener);

        // 啟動清理任務
        startCleanupTask();

        getLogger().info("WorldEditSync (BungeeCord) enabled!");
    }

    @Override
    public void onDisable() {
        // 清理資源
        clipboardManager.cleanup();
        getLogger().info("WorldEditSync (BungeeCord) disabled!");
    }

    private void startCleanupTask() {
        getProxy().getScheduler().schedule(this, () -> clipboardManager.cleanupExpiredSessions(), 0L, 2L, TimeUnit.MINUTES);
    }

    @EventHandler
    public void onPluginMessageReceived(PluginMessageEvent event) {

        if (!event.getTag().equals(Constants.CHANNEL)) {
            return;
        }
        messageListener.onPluginMessageReceived((ProxiedPlayer) event.getReceiver(), event.getData());
    }


    public ClipboardManager getClipboardManager() {
        return clipboardManager;
    }
}
