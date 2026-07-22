package dev.twme.worldeditsync.common;

public final class Constants {

    private Constants() {
    }

    public static final String CHANNEL = "worldeditsync:main";

    public static final byte PROTOCOL_VERSION = 3;

    /** Conservative limit supported by Bukkit's plugin messaging transport. */
    public static final int MAX_PLUGIN_MESSAGE_SIZE = 32_766;
    public static final int MIN_CHUNK_SIZE = 1_024;
    public static final int MAX_CHUNK_SIZE = 30_000;
    public static final int MAX_CANCEL_REASON_LENGTH = 96;

    /** Caps the amount of plugin-message work performed for one player in one tick. */
    public static final int MAX_CHUNKS_PER_TICK = 8;

    /** Per-player inbound budget, enforced before authentication/decryption. */
    public static final int MAX_INBOUND_MESSAGES_PER_SECOND = 200;
    public static final int MAX_INBOUND_BYTES_PER_SECOND = 8 * 1024 * 1024;
    public static final int MAX_INVALID_MESSAGES_PER_SECOND = 1_024;
    public static final long INVALID_MESSAGE_PLAYER_COOLDOWN_MS = 1_000L;
    public static final long INVALID_MESSAGE_GLOBAL_COOLDOWN_MS = 250L;

    public static final int DEFAULT_CHUNK_SIZE = 30_000;
    public static final int DEFAULT_MAX_CLIPBOARD_SIZE = 50 * 1024 * 1024; // 50 MB
    public static final int ABSOLUTE_MAX_CLIPBOARD_SIZE = 256 * 1024 * 1024;
    public static final long DEFAULT_MAX_CLIPBOARD_BLOCKS = 16_777_216L;
    public static final long ABSOLUTE_MAX_CLIPBOARD_BLOCKS = 67_108_864L;
    public static final long MAX_EXPANDED_SCHEMATIC_SIZE = 512L * 1024 * 1024;
    public static final int MAX_TRANSFER_CHUNKS =
            (ABSOLUTE_MAX_CLIPBOARD_SIZE + MIN_CHUNK_SIZE - 1) / MIN_CHUNK_SIZE + 1;
    public static final long DEFAULT_TRANSFER_MEMORY_LIMIT_BYTES = 256L * 1024 * 1024;
    public static final long MIN_TRANSFER_MEMORY_LIMIT_BYTES = 16L * 1024 * 1024;
    public static final long MAX_TRANSFER_MEMORY_LIMIT_BYTES = 8L * 1024 * 1024 * 1024;
    public static final long DEFAULT_SESSION_TIMEOUT_MS = 30_000;
    public static final long DEFAULT_CHUNK_SEND_DELAY_MS = 5;
    public static final int DEFAULT_WATCHER_INTERVAL_TICKS = 60; // 3 seconds
    public static final int DEFAULT_WATCHER_INITIAL_DELAY_TICKS = 40;
    public static final long UNCHANGED_CLIPBOARD_RECHECK_MS = 60_000L;
    public static final long DEFAULT_CLIPBOARD_TTL_MINUTES = 60;
    public static final int INITIAL_SYNC_MAX_ATTEMPTS = 5;
    public static final long INITIAL_SYNC_RETRY_MS = 1_000;
}
