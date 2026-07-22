package dev.twme.worldeditsync.paper.config;

import dev.twme.worldeditsync.common.config.TransferConfig;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public class PaperConfig {

    private String syncMode = "proxy";
    private String token = "";
    private boolean actionBarEnabled = true;

    // S3 settings
    private String s3Endpoint = "http://localhost:9000";
    private String s3AccessKey = "minioadmin";
    private String s3SecretKey = "minioadmin";
    private String s3Bucket = "worldeditsync";
    private String s3Region = "";
    private int s3CheckIntervalTicks = 40;

    // Database settings
    private DatabaseSettings databaseSettings = new DatabaseSettings(
            StorageType.SQLITE, "", "127.0.0.1", 0, "worldeditsync", "", "",
            "worldeditsync_clipboards", "worldeditsync", 4, 10_000L, 40, 60L);

    private final TransferConfig transferConfig = new TransferConfig();

    public void load(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        String configuredMode = config.getString("sync-mode", "proxy");
        syncMode = configuredMode == null ? "" : configuredMode.trim();
        String configuredToken = config.getString("token", "");
        token = configuredToken == null ? "" : configuredToken;
        actionBarEnabled = config.getBoolean("action-bar.enabled", actionBarEnabled);

        s3Endpoint = config.getString("s3.endpoint", s3Endpoint);
        s3AccessKey = config.getString("s3.access-key", s3AccessKey);
        s3SecretKey = config.getString("s3.secret-key", s3SecretKey);
        s3Bucket = config.getString("s3.bucket", s3Bucket);
        s3Region = config.getString("s3.region", s3Region);
        s3CheckIntervalTicks = Math.max(1, config.getInt("s3.check-interval", s3CheckIntervalTicks));

        StorageType databaseType = StorageType.parse(config.getString("database.type", "sqlite"));
        databaseSettings = new DatabaseSettings(
                databaseType,
                value(config.getString("database.url", "")),
                value(config.getString("database.host", "127.0.0.1")),
                config.getInt("database.port", 0),
                value(config.getString("database.name", "worldeditsync")),
                value(config.getString("database.username", "")),
                value(config.getString("database.password", "")),
                value(config.getString("database.table", "worldeditsync_clipboards")),
                value(config.getString("database.key-prefix", "worldeditsync")),
                clamp(config.getInt("database.pool-size", 4), 1, 16),
                Math.max(1_000L, config.getLong("database.connection-timeout-ms", 10_000L)),
                Math.max(1, config.getInt("database.check-interval", 40)),
                Math.max(0L, config.getLong("database.ttl-minutes", 60L)));

        transferConfig.setChunkSize(config.getInt("transfer.chunk-size", transferConfig.getChunkSize()));
        transferConfig.setMaxClipboardSize(config.getInt("transfer.max-clipboard-size", transferConfig.getMaxClipboardSize()));
        transferConfig.setMaxClipboardBlocks(config.getLong(
                "transfer.max-clipboard-blocks", transferConfig.getMaxClipboardBlocks()));
        transferConfig.setSessionTimeoutMs(config.getLong("transfer.session-timeout-ms", transferConfig.getSessionTimeoutMs()));
        transferConfig.setChunkSendDelayMs(config.getLong("transfer.chunk-send-delay-ms", transferConfig.getChunkSendDelayMs()));
        transferConfig.setWatcherIntervalTicks(config.getInt("transfer.watcher-interval-ticks", transferConfig.getWatcherIntervalTicks()));
        transferConfig.setWatcherInitialDelayTicks(config.getInt("transfer.watcher-initial-delay-ticks", transferConfig.getWatcherInitialDelayTicks()));
        transferConfig.setClipboardTtlMinutes(config.getLong("transfer.clipboard-ttl-minutes", transferConfig.getClipboardTtlMinutes()));
        transferConfig.setMemoryLimitBytes(config.getLong(
                "transfer.memory-limit-bytes", transferConfig.getMemoryLimitBytes()));
    }

    public boolean isProxyMode() {
        return "proxy".equalsIgnoreCase(syncMode);
    }

    public boolean isS3Mode() {
        return "s3".equalsIgnoreCase(syncMode);
    }

    public boolean isDatabaseMode() {
        return "database".equalsIgnoreCase(syncMode);
    }

    public boolean isSupportedMode() {
        return isProxyMode() || isS3Mode() || isDatabaseMode();
    }

    public String getSyncMode() {
        return syncMode;
    }

    public String getToken() {
        return token;
    }

    public boolean isActionBarEnabled() {
        return actionBarEnabled;
    }

    public String getS3Endpoint() {
        return s3Endpoint;
    }

    public String getS3AccessKey() {
        return s3AccessKey;
    }

    public String getS3SecretKey() {
        return s3SecretKey;
    }

    public String getS3Bucket() {
        return s3Bucket;
    }

    public String getS3Region() {
        return s3Region;
    }

    public int getS3CheckIntervalTicks() {
        return s3CheckIntervalTicks;
    }

    public DatabaseSettings getDatabaseSettings() {
        return databaseSettings;
    }

    public TransferConfig getTransferConfig() {
        return transferConfig;
    }

    private String value(String configured) {
        return configured == null ? "" : configured;
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
