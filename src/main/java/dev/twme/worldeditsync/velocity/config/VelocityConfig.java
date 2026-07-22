package dev.twme.worldeditsync.velocity.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.crypto.MessageCipher;

public class VelocityConfig {

    private String token = "";
    private long sessionTimeoutMs = 30_000;
    private long clipboardTtlMinutes = 60;
    private int chunkSize = 30_000;
    private long chunkSendDelayMs = 5;
    private int maxClipboardSize = 52_428_800;
    private long memoryLimitBytes = Constants.DEFAULT_TRANSFER_MEMORY_LIMIT_BYTES;

    public void load(Path dataDirectory, Logger logger) {
        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }

            Path configPath = dataDirectory.resolve("config.yml");
            if (!Files.exists(configPath)) {
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                    if (in != null) {
                        Files.copy(in, configPath);
                    }
                }
            }

            Yaml yaml = new Yaml();
            try (InputStream in = Files.newInputStream(configPath)) {
                Map<String, Object> root = yaml.load(in);
                if (root == null) return;

                token = getString(root, "token", token);

                Object transferObj = root.get("transfer");
                if (transferObj instanceof Map<?, ?> transfer) {
                    sessionTimeoutMs = getLong(transfer, "session-timeout-ms", sessionTimeoutMs);
                    clipboardTtlMinutes = getLong(transfer, "clipboard-ttl-minutes", clipboardTtlMinutes);
                    chunkSize = getInt(transfer, "chunk-size", chunkSize);
                    chunkSendDelayMs = getLong(transfer, "chunk-send-delay-ms", chunkSendDelayMs);
                    maxClipboardSize = getInt(transfer, "max-clipboard-size", maxClipboardSize);
                    memoryLimitBytes = getLong(
                            transfer, "memory-limit-bytes", memoryLimitBytes);
                }
                chunkSize = Math.max(Constants.MIN_CHUNK_SIZE,
                        Math.min(Constants.MAX_CHUNK_SIZE, chunkSize));
                maxClipboardSize = Math.max(1, Math.min(
                        Constants.ABSOLUTE_MAX_CLIPBOARD_SIZE, maxClipboardSize));
                memoryLimitBytes = Math.max(
                        (long) maxClipboardSize + MessageCipher.ENCRYPTION_OVERHEAD_BYTES,
                        Math.max(Constants.MIN_TRANSFER_MEMORY_LIMIT_BYTES,
                                Math.min(Constants.MAX_TRANSFER_MEMORY_LIMIT_BYTES,
                                        memoryLimitBytes)));
                chunkSendDelayMs = Math.max(0L, Math.min(1_000L, chunkSendDelayMs));
                sessionTimeoutMs = Math.max(5_000L, sessionTimeoutMs);
                clipboardTtlMinutes = Math.max(0L, clipboardTtlMinutes);
            }
        } catch (IOException e) {
            logger.error("Failed to load config: " + e.getMessage());
        }
    }

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private int getInt(Map<?, ?> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number n) return n.intValue();
        return defaultValue;
    }

    private long getLong(Map<?, ?> map, String key, long defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number n) return n.longValue();
        return defaultValue;
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

    public long getMemoryLimitBytes() {
        return memoryLimitBytes;
    }
}
