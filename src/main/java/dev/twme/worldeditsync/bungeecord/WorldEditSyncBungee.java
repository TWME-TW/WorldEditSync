package dev.twme.worldeditsync.bungeecord;

import dev.twme.worldeditsync.bungeecord.clipboard.ClipboardManager;
import dev.twme.worldeditsync.bungeecord.listener.MessageListener;
import dev.twme.worldeditsync.bungeecord.listener.PlayerListener;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.crypto.MessageCipher;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import net.md_5.bungee.event.EventHandler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

public class WorldEditSyncBungee extends Plugin implements Listener {
    private ClipboardManager clipboardManager;
    private MessageListener messageListener;
    private MessageCipher messageCipher;

    @Override
    public void onEnable() {
        // 載入設定
        loadConfig();

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

    private void loadConfig() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            try (InputStream in = getResourceAsStream("config.yml")) {
                if (in != null) {
                    Files.copy(in, configFile.toPath());
                }
            } catch (IOException e) {
                getLogger().warning("Failed to save default config: " + e.getMessage());
            }
        }

        try {
            Configuration config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(configFile);
            String token = config.getString("token", "");
            this.messageCipher = new MessageCipher(token);
            if (messageCipher.isEnabled()) {
                getLogger().info("Message encryption is ENABLED.");
            } else {
                getLogger().warning("Message encryption is DISABLED. Set 'token' in config.yml for security.");
            }
        } catch (IOException e) {
            getLogger().warning("Failed to load config: " + e.getMessage());
            this.messageCipher = new MessageCipher("");
        }
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

    public MessageCipher getMessageCipher() {
        return messageCipher;
    }
}
