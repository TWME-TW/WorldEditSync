package dev.twme.worldeditsync.paper;

import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.paper.clipboard.ClipboardManager;
import dev.twme.worldeditsync.paper.clipboard.ClipboardWatcher;
import dev.twme.worldeditsync.paper.listener.PlayerListener;
import dev.twme.worldeditsync.paper.message.MessageHandler;
import dev.twme.worldeditsync.paper.worldedit.WorldEditHelper;
import org.bukkit.plugin.java.JavaPlugin;

public class WorldEditSyncPaper extends JavaPlugin {
    private ClipboardManager clipboardManager;
    private WorldEditHelper worldEditHelper;
    private MessageHandler messageHandler;
    private ClipboardWatcher clipboardWatcher;
    private PlayerListener playerListener;

    @Override
    public void onEnable() {

        new UpdateChecker(this, 121682).getVersion(version -> {
            if (this.getPluginMeta().getVersion().equals(version)) {
                getLogger().info("There is not a new update available.");
            } else {
                getLogger().info("There is a new update available. Download it here: https://www.spigotmc.org/resources/121682/");
            }
        });
        // 初始化組件
        this.clipboardManager = new ClipboardManager(this);
        this.worldEditHelper = new WorldEditHelper(this);
        this.messageHandler = new MessageHandler(this);
        this.clipboardWatcher = new ClipboardWatcher(this);
        this.playerListener = new PlayerListener(this);

        // 註冊通道
        this.getServer().getMessenger().registerOutgoingPluginChannel(this, Constants.CHANNEL);
        this.getServer().getMessenger().registerIncomingPluginChannel(this, Constants.CHANNEL, messageHandler);
        this.getServer().getMessenger().registerIncomingPluginChannel(this, "BungeeCord" , messageHandler);
        // getLogger().info("註冊通道: " + Constants.CHANNEL);

        // 啟動剪貼簿監視器
        clipboardWatcher.runTaskTimerAsynchronously(this, 40L, 20L);

        // 註冊監聽器
        getServer().getPluginManager().registerEvents(playerListener, this);
        getLogger().info("WorldEditSync enabled!");
    }

    @Override
    public void onDisable() {
        // 取消註冊通道
        // this.getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        // this.getServer().getMessenger().unregisterIncomingPluginChannel(this);

        // 停止任務
        if (clipboardWatcher != null) {
            clipboardWatcher.cancel();
        }

        getLogger().info("WorldEditSync disabled!");
    }

    public ClipboardManager getClipboardManager() {
        return clipboardManager;
    }

    public WorldEditHelper getWorldEditHelper() {
        return worldEditHelper;
    }

    public MessageHandler getMessageHandler() {
        return messageHandler;
    }
}
