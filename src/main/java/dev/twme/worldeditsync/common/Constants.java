package dev.twme.worldeditsync.common;

public final class Constants {

    private Constants() {
    }

    public static final String CHANNEL = "worldeditsync:main";

    public static final byte PROTOCOL_VERSION = 1;

    public static final int DEFAULT_CHUNK_SIZE = 30_000;
    public static final int DEFAULT_MAX_CLIPBOARD_SIZE = 50 * 1024 * 1024; // 50 MB
    public static final long DEFAULT_SESSION_TIMEOUT_MS = 30_000;
    public static final long DEFAULT_CHUNK_SEND_DELAY_MS = 5;
    public static final int DEFAULT_WATCHER_INTERVAL_TICKS = 60; // 3 seconds
    public static final int DEFAULT_WATCHER_INITIAL_DELAY_TICKS = 40;
    public static final long DEFAULT_CLIPBOARD_TTL_MINUTES = 60;
}
