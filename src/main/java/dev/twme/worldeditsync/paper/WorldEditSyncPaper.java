package dev.twme.worldeditsync.paper;

import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.crypto.MessageCipher;
import dev.twme.worldeditsync.paper.clipboard.ClipboardManager;
import dev.twme.worldeditsync.paper.clipboard.ClipboardWatcher;
import dev.twme.worldeditsync.paper.listener.PlayerListener;
import dev.twme.worldeditsync.paper.pluginmessage.PluginMessageHandler;
import dev.twme.worldeditsync.paper.worldedit.WorldEditHelper;
import org.bukkit.plugin.java.JavaPlugin;

public class WorldEditSyncPaper extends JavaPlugin {
    private ClipboardManager clipboardManager;
    private WorldEditHelper worldEditHelper;
    private PluginMessageHandler messageHandler;
    private ClipboardWatcher clipboardWatcher;
    private MessageCipher messageCipher;

    @Override
    public void onEnable() {
        new UpdateChecker(this, 121682).getVersion(version -> {
            if (this.getPluginMeta().getVersion().equals(version)) {
                getLogger().info("There is not a new update available.");
            } else {
                getLogger().info("There is a new update available. Download it here: https://www.spigotmc.org/resources/121682/");
            }
        });

        // 載入設定
        saveDefaultConfig();
        String token = getConfig().getString("token", "");
        this.messageCipher = new MessageCipher(token);
        if (messageCipher.isEnabled()) {
            getLogger().info("Message encryption is ENABLED.");
        } else {
            getLogger().warning("Message encryption is DISABLED. Set 'token' in config.yml for security.");
        }

        // 初始化組件
        this.clipboardManager = new ClipboardManager(this);
        this.worldEditHelper = new WorldEditHelper(this);
        this.messageHandler = new PluginMessageHandler(this);
        this.clipboardWatcher = new ClipboardWatcher(this);

        // 註冊 Plugin Message 通道
        getServer().getMessenger().registerOutgoingPluginChannel(this, Constants.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, Constants.CHANNEL, messageHandler);

        // 啟動剪貼簿偵測器
        clipboardWatcher.runTaskTimer(this, Constants.WATCHER_INITIAL_DELAY_TICKS, Constants.WATCHER_INTERVAL_TICKS);

        // 註冊事件監聽器
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        getLogger().info("WorldEditSync enabled!");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);

        if (clipboardWatcher != null) {
            clipboardWatcher.cancel();
        }
        if (clipboardManager != null) {
            clipboardManager.cleanup();
        }

        getLogger().info("WorldEditSync disabled!");
    }

    public ClipboardManager getClipboardManager() {
        return clipboardManager;
    }

    public WorldEditHelper getWorldEditHelper() {
        return worldEditHelper;
    }

    public MessageCipher getMessageCipher() {
        return messageCipher;
    }
}
