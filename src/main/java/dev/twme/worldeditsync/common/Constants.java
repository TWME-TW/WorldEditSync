package dev.twme.worldeditsync.common;

public class Constants {

    public static final String CHANNEL = "worldedit-sync:main";

    /** 每個 chunk 的大小 (~30KB，安全低於 32KB plugin message 限制) */
    public static final int CHUNK_SIZE = 30000;

    /** 傳輸會話超時時間 (30 秒) */
    public static final long SESSION_TIMEOUT_MS = 30_000L;

    /** 剪貼簿最大大小限制 (50MB) */
    public static final int MAX_CLIPBOARD_SIZE = 50 * 1024 * 1024;

    /** chunk 之間的發送延遲 (毫秒) */
    public static final long CHUNK_SEND_DELAY_MS = 5L;

    /** 剪貼簿偵測器的週期 (ticks) */
    public static final long WATCHER_INTERVAL_TICKS = 20L;

    /** 剪貼簿偵測器的初始延遲 (ticks) */
    public static final long WATCHER_INITIAL_DELAY_TICKS = 40L;
}
