package dev.twme.worldeditsync.paper;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.crypto.MessageCipher;
import dev.twme.worldeditsync.paper.clipboard.ClipboardManager;
import dev.twme.worldeditsync.paper.clipboard.ClipboardWatcher;
import dev.twme.worldeditsync.paper.listener.PlayerListener;
import dev.twme.worldeditsync.paper.pluginmessage.PluginMessageHandler;
import dev.twme.worldeditsync.paper.s3.S3ClipboardWatcher;
import dev.twme.worldeditsync.paper.s3.S3StorageManager;
import dev.twme.worldeditsync.paper.worldedit.WorldEditHelper;

public class WorldEditSyncPaper extends JavaPlugin {
    private ClipboardManager clipboardManager;
    private WorldEditHelper worldEditHelper;
    private PluginMessageHandler messageHandler;
    private BukkitRunnable clipboardWatcher;
    private MessageCipher messageCipher;
    private S3StorageManager s3StorageManager;

    /** 同步模式："proxy" 或 "s3" */
    private String syncMode;

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
        syncMode = getConfig().getString("sync-mode", "proxy").toLowerCase();

        // 載入可覆寫的傳輸常數
        loadTransferConstants();

        // 初始化加密（兩種模式都可用）
        String token = getConfig().getString("token", "");
        this.messageCipher = new MessageCipher(token);
        if (messageCipher.isEnabled()) {
            getLogger().info("Message encryption is ENABLED.");
        } else {
            getLogger().warning("Message encryption is DISABLED. Set 'token' in config.yml for security.");
        }

        // 初始化共用組件
        this.clipboardManager = new ClipboardManager(this);
        this.worldEditHelper = new WorldEditHelper(this);

        if ("s3".equals(syncMode)) {
            enableS3Mode();
        } else {
            enableProxyMode();
        }

        // 註冊事件監聽器
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        getLogger().info("WorldEditSync enabled! (sync-mode: " + syncMode + ")");
    }

    /**
     * 啟用 Proxy 模式：透過 BungeeCord/Velocity 的 Plugin Message 同步。
     */
    private void enableProxyMode() {
        this.messageHandler = new PluginMessageHandler(this);

        // 註冊 Plugin Message 通道
        getServer().getMessenger().registerOutgoingPluginChannel(this, Constants.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, Constants.CHANNEL, messageHandler);

        // 啟動剪貼簿偵測器
        ClipboardWatcher proxyWatcher = new ClipboardWatcher(this);
        this.clipboardWatcher = proxyWatcher;
        proxyWatcher.runTaskTimer(this, Constants.WATCHER_INITIAL_DELAY_TICKS, Constants.WATCHER_INTERVAL_TICKS);
    }

    /**
     * 啟用 S3 模式：透過 S3 相容儲存服務（如 MinIO）同步。
     */
    private void enableS3Mode() {
        String endpoint = getConfig().getString("s3.endpoint", "http://localhost:9000");
        String accessKey = getConfig().getString("s3.access-key", "minioadmin");
        String secretKey = getConfig().getString("s3.secret-key", "minioadmin");
        String bucket = getConfig().getString("s3.bucket", "worldeditsync");
        String region = getConfig().getString("s3.region", "");
        long checkInterval = getConfig().getLong("s3.check-interval", 40L);

        this.s3StorageManager = new S3StorageManager(this, endpoint, accessKey, secretKey, bucket, region);

        // 非同步初始化 S3 連線
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            if (!s3StorageManager.initialize()) {
                getLogger().severe("Failed to initialize S3 storage! S3 sync will not work.");
                return;
            }

            // 初始化成功後，在主執行緒啟動 Watcher
            getServer().getScheduler().runTask(this, () -> {
                S3ClipboardWatcher s3Watcher = new S3ClipboardWatcher(this, s3StorageManager);
                clipboardWatcher = s3Watcher;
                s3Watcher.runTaskTimer(this, Constants.WATCHER_INITIAL_DELAY_TICKS, checkInterval);
                getLogger().info("S3 clipboard watcher started (interval: " + checkInterval + " ticks)");
            });
        });
    }

    @Override
    public void onDisable() {
        if (isProxyMode()) {
            getServer().getMessenger().unregisterOutgoingPluginChannel(this);
            getServer().getMessenger().unregisterIncomingPluginChannel(this);
        }

        if (clipboardWatcher != null) {
            clipboardWatcher.cancel();
        }
        if (clipboardManager != null) {
            clipboardManager.cleanup();
        }

        getLogger().info("WorldEditSync disabled!");
    }

    public boolean isProxyMode() {
        return "proxy".equals(syncMode);
    }

    public boolean isS3Mode() {
        return "s3".equals(syncMode);
    }

    public String getSyncMode() {
        return syncMode;
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

    public S3StorageManager getS3StorageManager() {
        return s3StorageManager;
    }

    /**
     * 從 config.yml 載入可覆寫的傳輸常數。
     * 這些數值必須在所有伺服器與 Proxy 上保持一致。
     */
    private void loadTransferConstants() {
        Constants.CHUNK_SIZE = getConfig().getInt("transfer.chunk-size", 30000);
        Constants.SESSION_TIMEOUT_MS = getConfig().getLong("transfer.session-timeout-ms", 30000L);
        Constants.MAX_CLIPBOARD_SIZE = getConfig().getInt("transfer.max-clipboard-size", 50 * 1024 * 1024);
        Constants.CHUNK_SEND_DELAY_MS = getConfig().getLong("transfer.chunk-send-delay-ms", 5L);
        Constants.WATCHER_INTERVAL_TICKS = getConfig().getLong("transfer.watcher-interval-ticks", 20L);
        Constants.WATCHER_INITIAL_DELAY_TICKS = getConfig().getLong("transfer.watcher-initial-delay-ticks", 40L);
    }
}
