package dev.twme.worldeditsync.paper;

import org.bukkit.plugin.java.JavaPlugin;

import dev.twme.worldeditsync.common.crypto.MessageCipher;
import dev.twme.worldeditsync.common.protocol.PluginMessageCodec;
import dev.twme.worldeditsync.common.protocol.TransferMemoryBudget;
import dev.twme.worldeditsync.paper.clipboard.ClipboardManager;
import dev.twme.worldeditsync.paper.clipboard.ClipboardSerializer;
import dev.twme.worldeditsync.paper.config.PaperConfig;
import dev.twme.worldeditsync.paper.config.DatabaseSettings;
import dev.twme.worldeditsync.paper.config.StorageType;
import dev.twme.worldeditsync.paper.listener.ClipboardWatcher;
import dev.twme.worldeditsync.paper.listener.PlayerListener;
import dev.twme.worldeditsync.paper.s3.S3StorageManager;
import dev.twme.worldeditsync.paper.storage.JdbcClipboardStorage;
import dev.twme.worldeditsync.paper.storage.RedisClipboardStorage;
import dev.twme.worldeditsync.paper.storage.S3ClipboardStorage;
import dev.twme.worldeditsync.paper.sync.ProxySyncEngine;
import dev.twme.worldeditsync.paper.sync.StorageSyncEngine;
import dev.twme.worldeditsync.paper.sync.SyncEngine;
import dev.twme.worldeditsync.paper.ui.ActionBarProgress;
import dev.twme.worldeditsync.paper.update.UpdateChecker;

public class WorldEditSyncPaper extends JavaPlugin {

    private PaperConfig paperConfig;
    private ClipboardManager clipboardManager;
    private ClipboardSerializer clipboardSerializer;
    private SyncEngine syncEngine;
    private ClipboardWatcher clipboardWatcher;
    private ActionBarProgress actionBarProgress;

    @Override
    public void onEnable() {
        // Load configuration
        paperConfig = new PaperConfig();
        paperConfig.load(this);
        if (!paperConfig.isSupportedMode()) {
            getLogger().severe("Unsupported sync-mode '" + paperConfig.getSyncMode()
                    + "'. Expected 'proxy', 's3', or 'database'. WorldEditSync will be disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (paperConfig.isProxyMode() && paperConfig.getToken().isBlank()) {
            getLogger().severe("Proxy mode requires a non-empty token so players cannot forge or read "
                    + "clipboard plugin messages. WorldEditSync will be disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Core components
        clipboardManager = new ClipboardManager();
        clipboardManager.setTransferMemoryBudget(new TransferMemoryBudget(
                paperConfig.getTransferConfig().getMemoryLimitBytes()));
        clipboardSerializer = new ClipboardSerializer();
        actionBarProgress = new ActionBarProgress(this, paperConfig.isActionBarEnabled());
        MessageCipher cipher = new MessageCipher(paperConfig.getToken());

        if (cipher.isEnabled()) {
            getLogger().info("Encryption enabled (AES-256-GCM).");
        } else {
            getLogger().warning("Encryption disabled! Set 'token' in config.yml for secure transfers.");
        }

        // Initialize sync engine based on mode
        if (paperConfig.isDatabaseMode()
                && !paperConfig.getDatabaseSettings().isSupported()) {
            getLogger().severe("Unsupported database.type. Expected redis, mysql, mariadb, "
                    + "postgresql, or sqlite. WorldEditSync will be disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (paperConfig.isS3Mode()) {
            initS3Mode(cipher);
        } else if (paperConfig.isDatabaseMode()) {
            initDatabaseMode(cipher);
        } else {
            initProxyMode(cipher, PluginMessageCodec.forPaper(paperConfig.getToken()));
        }

        if (syncEngine == null) {
            getLogger().severe("Failed to initialize sync engine! Plugin will be disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        syncEngine.start();

        // Register player events
        getServer().getPluginManager().registerEvents(new PlayerListener(syncEngine), this);

        // Storage-backed modes have their own polling; proxy mode needs this watcher.
        if (paperConfig.isProxyMode()) {
            clipboardWatcher = new ClipboardWatcher(this, clipboardManager, clipboardSerializer,
                    syncEngine, paperConfig.getTransferConfig());
            clipboardWatcher.start(
                    paperConfig.getTransferConfig().getWatcherInitialDelayTicks(),
                    paperConfig.getTransferConfig().getWatcherIntervalTicks());
        }

        // Check for updates
        new UpdateChecker(this).checkAsync();

        getLogger().info("WorldEditSync enabled (mode: " + paperConfig.getSyncMode() + ")");
    }

    @Override
    public void onDisable() {
        if (clipboardWatcher != null) {
            clipboardWatcher.cancel();
        }
        if (syncEngine != null) {
            syncEngine.shutdown();
        }
        if (clipboardManager != null) {
            clipboardManager.shutdown();
        }
        if (actionBarProgress != null) {
            actionBarProgress.shutdown();
        }
        getLogger().info("WorldEditSync disabled.");
    }

    private void initProxyMode(MessageCipher cipher, PluginMessageCodec pluginMessageCodec) {
        syncEngine = new ProxySyncEngine(this, clipboardManager, clipboardSerializer,
                cipher, pluginMessageCodec, paperConfig.getTransferConfig(), actionBarProgress);
        getLogger().info("Initializing Proxy sync mode.");
    }

    private void initS3Mode(MessageCipher cipher) {
        S3StorageManager s3 = new S3StorageManager(
                paperConfig.getS3Endpoint(),
                paperConfig.getS3AccessKey(),
                paperConfig.getS3SecretKey(),
                paperConfig.getS3Bucket(),
                paperConfig.getS3Region(),
                cipher,
                paperConfig.getTransferConfig().getMaxClipboardSize(),
                getLogger());

        syncEngine = new StorageSyncEngine(this, clipboardManager, clipboardSerializer,
                new S3ClipboardStorage(s3), paperConfig.getTransferConfig(),
                paperConfig.getS3CheckIntervalTicks(), actionBarProgress);
        getLogger().info("Initializing S3 sync mode.");
    }

    private void initDatabaseMode(MessageCipher cipher) {
        DatabaseSettings settings = paperConfig.getDatabaseSettings();
        String url = settings.resolveUrl(getDataFolder().toPath());
        try {
            dev.twme.worldeditsync.common.storage.ClipboardStorage storage;
            if (settings.type() == StorageType.REDIS) {
                storage = new RedisClipboardStorage(
                        url,
                        settings.keyPrefix(),
                        settings.poolSize(),
                        settings.connectionTimeoutMs(),
                        settings.ttlMinutes(),
                        cipher,
                        paperConfig.getTransferConfig().getMaxClipboardSize(),
                        getLogger());
            } else {
                storage = new JdbcClipboardStorage(
                        settings.type(),
                        url,
                        settings.username(),
                        settings.password(),
                        settings.table(),
                        settings.poolSize(),
                        settings.connectionTimeoutMs(),
                        settings.ttlMinutes(),
                        cipher,
                        paperConfig.getTransferConfig().getMaxClipboardSize());
            }
            syncEngine = new StorageSyncEngine(
                    this, clipboardManager, clipboardSerializer, storage,
                    paperConfig.getTransferConfig(), settings.checkIntervalTicks(),
                    actionBarProgress);
            getLogger().info("Initializing database sync mode (backend: "
                    + settings.type().name().toLowerCase(java.util.Locale.ROOT) + ").");
        } catch (IllegalArgumentException e) {
            getLogger().severe("Invalid database configuration: " + e.getMessage());
        }
    }
}
