package dev.twme.worldeditsync.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.crypto.MessageCipher;
import dev.twme.worldeditsync.velocity.clipboard.ClipboardManager;
import dev.twme.worldeditsync.velocity.listener.MessageListener;
import dev.twme.worldeditsync.velocity.listener.PlayerListener;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "worldeditsync",
        name = "WorldEditSync",
        version = "0.0.7",
        description = "Sync WorldEdit clipboard across servers",
        authors = {"TWME"}
)
public class WorldEditSyncVelocity {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private ClipboardManager clipboardManager;
    private MessageCipher messageCipher;

    @Inject
    public WorldEditSyncVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        // 載入設定
        loadConfig();

        this.clipboardManager = new ClipboardManager();

        // 註冊訊息通道
        MinecraftChannelIdentifier channel = MinecraftChannelIdentifier.from(Constants.CHANNEL);
        server.getChannelRegistrar().register(channel);

        // 註冊監聽器
        server.getEventManager().register(this, new MessageListener(this));
        server.getEventManager().register(this, new PlayerListener(this));

        // 定期清理過期的上傳會話
        server.getScheduler().buildTask(this, clipboardManager::cleanupExpiredSessions)
                .repeat(2L, TimeUnit.MINUTES)
                .schedule();

        logger.info("WorldEditSync (Velocity) enabled!");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        clipboardManager.cleanup();
        logger.info("WorldEditSync (Velocity) disabled!");
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

    public MessageCipher getMessageCipher() {
        return messageCipher;
    }

    private void loadConfig() {
        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }

            Path configPath = dataDirectory.resolve("config.yml");
            if (!Files.exists(configPath)) {
                try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                    if (in != null) {
                        Files.copy(in, configPath);
                    }
                }
            }

            // 簡易 YAML 解析：讀取 token 行
            String token = "";
            for (String line : Files.readAllLines(configPath)) {
                line = line.trim();
                if (line.startsWith("token:")) {
                    token = line.substring("token:".length()).trim();
                    // 移除引號
                    if ((token.startsWith("\"") && token.endsWith("\"")) ||
                        (token.startsWith("'") && token.endsWith("'"))) {
                        token = token.substring(1, token.length() - 1);
                    }
                    break;
                }
            }

            this.messageCipher = new MessageCipher(token);
            if (messageCipher.isEnabled()) {
                logger.info("Message encryption is ENABLED.");
            } else {
                logger.warn("Message encryption is DISABLED. Set 'token' in config.yml for security.");
            }
        } catch (IOException e) {
            logger.error("Failed to load config", e);
            this.messageCipher = new MessageCipher("");
        }
    }
}
