package dev.twme.worldeditsync.paper.config;

import dev.twme.worldeditsync.common.config.TransferConfig;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public class PaperConfig {

    private String syncMode = "proxy";
    private String token = "";

    // S3 settings
    private String s3Endpoint = "http://localhost:9000";
    private String s3AccessKey = "minioadmin";
    private String s3SecretKey = "minioadmin";
    private String s3Bucket = "worldeditsync";
    private String s3Region = "";
    private int s3CheckIntervalTicks = 40;

    private final TransferConfig transferConfig = new TransferConfig();

    public void load(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        String configuredMode = config.getString("sync-mode", "proxy");
        syncMode = configuredMode == null ? "" : configuredMode.trim();
        String configuredToken = config.getString("token", "");
        token = configuredToken == null ? "" : configuredToken;

        s3Endpoint = config.getString("s3.endpoint", s3Endpoint);
        s3AccessKey = config.getString("s3.access-key", s3AccessKey);
        s3SecretKey = config.getString("s3.secret-key", s3SecretKey);
        s3Bucket = config.getString("s3.bucket", s3Bucket);
        s3Region = config.getString("s3.region", s3Region);
        s3CheckIntervalTicks = Math.max(1, config.getInt("s3.check-interval", s3CheckIntervalTicks));

        transferConfig.setChunkSize(config.getInt("transfer.chunk-size", transferConfig.getChunkSize()));
        transferConfig.setMaxClipboardSize(config.getInt("transfer.max-clipboard-size", transferConfig.getMaxClipboardSize()));
        transferConfig.setSessionTimeoutMs(config.getLong("transfer.session-timeout-ms", transferConfig.getSessionTimeoutMs()));
        transferConfig.setChunkSendDelayMs(config.getLong("transfer.chunk-send-delay-ms", transferConfig.getChunkSendDelayMs()));
        transferConfig.setWatcherIntervalTicks(config.getInt("transfer.watcher-interval-ticks", transferConfig.getWatcherIntervalTicks()));
        transferConfig.setWatcherInitialDelayTicks(config.getInt("transfer.watcher-initial-delay-ticks", transferConfig.getWatcherInitialDelayTicks()));
        transferConfig.setClipboardTtlMinutes(config.getLong("transfer.clipboard-ttl-minutes", transferConfig.getClipboardTtlMinutes()));
    }

    public boolean isProxyMode() {
        return "proxy".equalsIgnoreCase(syncMode);
    }

    public boolean isS3Mode() {
        return "s3".equalsIgnoreCase(syncMode);
    }

    public boolean isSupportedMode() {
        return isProxyMode() || isS3Mode();
    }

    public String getSyncMode() {
        return syncMode;
    }

    public String getToken() {
        return token;
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

    public TransferConfig getTransferConfig() {
        return transferConfig;
    }
}
