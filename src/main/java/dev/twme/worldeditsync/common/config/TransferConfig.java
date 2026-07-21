package dev.twme.worldeditsync.common.config;

import dev.twme.worldeditsync.common.Constants;

public class TransferConfig {

    private int chunkSize = Constants.DEFAULT_CHUNK_SIZE;
    private int maxClipboardSize = Constants.DEFAULT_MAX_CLIPBOARD_SIZE;
    private long sessionTimeoutMs = Constants.DEFAULT_SESSION_TIMEOUT_MS;
    private long chunkSendDelayMs = Constants.DEFAULT_CHUNK_SEND_DELAY_MS;
    private int watcherIntervalTicks = Constants.DEFAULT_WATCHER_INTERVAL_TICKS;
    private int watcherInitialDelayTicks = Constants.DEFAULT_WATCHER_INITIAL_DELAY_TICKS;
    private long clipboardTtlMinutes = Constants.DEFAULT_CLIPBOARD_TTL_MINUTES;

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = Math.max(1, Math.min(Constants.MAX_CHUNK_SIZE, chunkSize));
    }

    public int getMaxClipboardSize() {
        return maxClipboardSize;
    }

    public void setMaxClipboardSize(int maxClipboardSize) {
        this.maxClipboardSize = Math.max(1, Math.min(
                Constants.ABSOLUTE_MAX_CLIPBOARD_SIZE, maxClipboardSize));
    }

    public long getSessionTimeoutMs() {
        return sessionTimeoutMs;
    }

    public void setSessionTimeoutMs(long sessionTimeoutMs) {
        this.sessionTimeoutMs = Math.max(5_000L, sessionTimeoutMs);
    }

    public long getChunkSendDelayMs() {
        return chunkSendDelayMs;
    }

    public void setChunkSendDelayMs(long chunkSendDelayMs) {
        this.chunkSendDelayMs = Math.max(0L, Math.min(1_000L, chunkSendDelayMs));
    }

    public int getWatcherIntervalTicks() {
        return watcherIntervalTicks;
    }

    public void setWatcherIntervalTicks(int watcherIntervalTicks) {
        this.watcherIntervalTicks = Math.max(1, watcherIntervalTicks);
    }

    public int getWatcherInitialDelayTicks() {
        return watcherInitialDelayTicks;
    }

    public void setWatcherInitialDelayTicks(int watcherInitialDelayTicks) {
        this.watcherInitialDelayTicks = Math.max(1, watcherInitialDelayTicks);
    }

    public long getClipboardTtlMinutes() {
        return clipboardTtlMinutes;
    }

    public void setClipboardTtlMinutes(long clipboardTtlMinutes) {
        this.clipboardTtlMinutes = Math.max(0L, clipboardTtlMinutes);
    }
}
