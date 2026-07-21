package dev.twme.worldeditsync.bungeecord.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import dev.twme.worldeditsync.common.Constants;

public class BungeeConfig {

    private String token = "";
    private long sessionTimeoutMs = 30_000;
    private long clipboardTtlMinutes = 60;
    private int chunkSize = 30_000;
    private long chunkSendDelayMs = 5;
    private int maxClipboardSize = 52_428_800;

    public void load(Plugin plugin) {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        File configFile = new File(dataFolder, "config.yml");
        if (!configFile.exists()) {
            try (InputStream in = plugin.getResourceAsStream("config.yml")) {
                if (in != null) {
                    Files.copy(in, configFile.toPath());
                }
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to copy default config: " + e.getMessage());
            }
        }

        try {
            Configuration config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(configFile);
            token = config.getString("token", "");
            if (token == null) {
                token = "";
            }
            sessionTimeoutMs = config.getLong("transfer.session-timeout-ms", sessionTimeoutMs);
            clipboardTtlMinutes = config.getLong("transfer.clipboard-ttl-minutes", clipboardTtlMinutes);
            chunkSize = config.getInt("transfer.chunk-size", chunkSize);
            chunkSendDelayMs = config.getLong("transfer.chunk-send-delay-ms", chunkSendDelayMs);
            maxClipboardSize = config.getInt("transfer.max-clipboard-size", maxClipboardSize);
            chunkSize = Math.max(1, Math.min(Constants.MAX_CHUNK_SIZE, chunkSize));
            maxClipboardSize = Math.max(1, Math.min(
                    Constants.ABSOLUTE_MAX_CLIPBOARD_SIZE, maxClipboardSize));
            chunkSendDelayMs = Math.max(0L, Math.min(1_000L, chunkSendDelayMs));
            sessionTimeoutMs = Math.max(5_000L, sessionTimeoutMs);
            clipboardTtlMinutes = Math.max(0L, clipboardTtlMinutes);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to load config: " + e.getMessage());
        }
    }

    public String getToken() {
        return token;
    }

    public long getSessionTimeoutMs() {
        return sessionTimeoutMs;
    }

    public long getClipboardTtlMinutes() {
        return clipboardTtlMinutes;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public long getChunkSendDelayMs() {
        return chunkSendDelayMs;
    }

    public int getMaxClipboardSize() {
        return maxClipboardSize;
    }
}
