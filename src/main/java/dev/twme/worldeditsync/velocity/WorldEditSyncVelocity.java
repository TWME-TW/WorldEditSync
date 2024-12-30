package dev.twme.worldeditsync.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.velocity.clipboard.ClipboardManager;
import dev.twme.worldeditsync.velocity.listener.MessageListener;
import dev.twme.worldeditsync.velocity.listener.PlayerListener;
import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;

@Plugin(
        id = "worldeditsync",
        name = "WorldEditSync",
        version = "0.0.1",
        description = "Sync WorldEdit clipboard across servers",
        authors = {"TWME"}
)
public class WorldEditSyncVelocity {
    private final ProxyServer server;
    private final Logger logger;
    private ClipboardManager clipboardManager;
    private MessageListener messageListener;
    private PlayerListener playerListener;


    @Inject
    public WorldEditSyncVelocity(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        // 初始化組件
        this.clipboardManager = new ClipboardManager(this);
        this.messageListener = new MessageListener(this);
        this.playerListener = new PlayerListener(this);

        // 註冊訊息通道
        MinecraftChannelIdentifier channel =
                MinecraftChannelIdentifier.from(Constants.CHANNEL);
        server.getChannelRegistrar().register(channel);

        // 註冊監聽器
        server.getEventManager().register(this, messageListener);
        server.getEventManager().register(this, playerListener);

        // 啟動清理任務
        startCleanupTask();

        logger.info("WorldEditSync (Velocity) enabled!");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        // 清理資源
        clipboardManager.cleanup();
        logger.info("WorldEditSync (Velocity) disabled!");
    }

    private void startCleanupTask() {
        server.getScheduler()
                .buildTask(this, () -> clipboardManager.cleanupExpiredSessions())
                .repeat(30L, TimeUnit.SECONDS)
                .schedule();
    }

    public ProxyServer getServer() {
        return server;
    }

    public Logger getLogger() {
        return logger;
    }

    public ClipboardManager getClipboardManager() {
        return clipboardManager;
    }
}