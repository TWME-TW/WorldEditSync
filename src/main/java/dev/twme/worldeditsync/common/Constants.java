package dev.twme.worldeditsync.common;

/**
 * 全域常數。
 * 所有數值皆可於 config.yml 中覆寫，但 <b>必須在所有伺服器與 Proxy 上保持一致</b>。
 */
public class Constants {

    public static final String CHANNEL = "worldedit-sync:main";

    // ===== 以下為預設值，可透過 config.yml 覆寫 =====

    /** 每個 chunk 的大小 (~30KB，安全低於 32KB plugin message 限制) */
    public static int CHUNK_SIZE = 30_000;

    /** 傳輸會話超時時間 (毫秒) */
    public static long SESSION_TIMEOUT_MS = 30_000L;

    /** 剪貼簿最大大小限制 (bytes) */
    public static int MAX_CLIPBOARD_SIZE = 50 * 1024 * 1024;

    /** chunk 之間的發送延遲 (毫秒) */
    public static long CHUNK_SEND_DELAY_MS = 5L;

    /** 剪貼簿偵測器的週期 (ticks) */
    public static long WATCHER_INTERVAL_TICKS = 20L;

    /** 剪貼簿偵測器的初始延遲 (ticks) */
    public static long WATCHER_INITIAL_DELAY_TICKS = 40L;
}
