package dev.twme.worldeditsync.paper;

import org.bukkit.plugin.java.JavaPlugin;

import dev.twme.worldeditsync.common.crypto.MessageCipher;
import dev.twme.worldeditsync.paper.clipboard.ClipboardManager;
import dev.twme.worldeditsync.paper.clipboard.ClipboardSerializer;
import dev.twme.worldeditsync.paper.config.PaperConfig;
import dev.twme.worldeditsync.paper.listener.ClipboardWatcher;
import dev.twme.worldeditsync.paper.listener.PlayerListener;
import dev.twme.worldeditsync.paper.s3.S3StorageManager;
import dev.twme.worldeditsync.paper.sync.ProxySyncEngine;
import dev.twme.worldeditsync.paper.sync.S3SyncEngine;
import dev.twme.worldeditsync.paper.sync.SyncEngine;
import dev.twme.worldeditsync.paper.update.UpdateChecker;

public class WorldEditSyncPaper extends JavaPlugin {

    private PaperConfig paperConfig;
    private ClipboardManager clipboardManager;
    private ClipboardSerializer clipboardSerializer;
    private SyncEngine syncEngine;
    private ClipboardWatcher clipboardWatcher;

    @Override
    public void onEnable() {
        // Load configuration
        paperConfig = new PaperConfig();
        paperConfig.load(this);

        // Core components
        clipboardManager = new ClipboardManager();
        clipboardSerializer = new ClipboardSerializer();
        MessageCipher cipher = new MessageCipher(paperConfig.getToken());

        if (cipher.isEnabled()) {
            getLogger().info("Encryption enabled (AES-256-GCM).");
        } else {
            getLogger().warning("Encryption disabled! Set 'token' in config.yml for secure transfers.");
        }

        // Initialize sync engine based on mode
        if (paperConfig.isS3Mode()) {
            initS3Mode(cipher);
        } else {
            initProxyMode(cipher);
        }

        if (syncEngine == null) {
            getLogger().severe("Failed to initialize sync engine! Plugin will be disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        syncEngine.start();

        // Register player events
        getServer().getPluginManager().registerEvents(new PlayerListener(syncEngine), this);

        // Start clipboard watcher for proxy mode (S3 mode has its own polling)
        if (paperConfig.isProxyMode()) {
            clipboardWatcher = new ClipboardWatcher(this, clipboardManager, clipboardSerializer,
                    syncEngine);
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
        getLogger().info("WorldEditSync disabled.");
    }

    private void initProxyMode(MessageCipher cipher) {
        syncEngine = new ProxySyncEngine(this, clipboardManager, clipboardSerializer,
                cipher, paperConfig.getTransferConfig());
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
                getLogger());

        syncEngine = new S3SyncEngine(this, clipboardManager, clipboardSerializer,
                s3, paperConfig.getTransferConfig(), paperConfig.getS3CheckIntervalTicks());
        getLogger().info("Initializing S3 sync mode.");
    }
}
