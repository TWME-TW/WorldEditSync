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
        this.chunkSize = chunkSize;
    }

    public int getMaxClipboardSize() {
        return maxClipboardSize;
    }

    public void setMaxClipboardSize(int maxClipboardSize) {
        this.maxClipboardSize = maxClipboardSize;
    }

    public long getSessionTimeoutMs() {
        return sessionTimeoutMs;
    }

    public void setSessionTimeoutMs(long sessionTimeoutMs) {
        this.sessionTimeoutMs = sessionTimeoutMs;
    }

    public long getChunkSendDelayMs() {
        return chunkSendDelayMs;
    }

    public void setChunkSendDelayMs(long chunkSendDelayMs) {
        this.chunkSendDelayMs = chunkSendDelayMs;
    }

    public int getWatcherIntervalTicks() {
        return watcherIntervalTicks;
    }

    public void setWatcherIntervalTicks(int watcherIntervalTicks) {
        this.watcherIntervalTicks = watcherIntervalTicks;
    }

    public int getWatcherInitialDelayTicks() {
        return watcherInitialDelayTicks;
    }

    public void setWatcherInitialDelayTicks(int watcherInitialDelayTicks) {
        this.watcherInitialDelayTicks = watcherInitialDelayTicks;
    }

    public long getClipboardTtlMinutes() {
        return clipboardTtlMinutes;
    }

    public void setClipboardTtlMinutes(long clipboardTtlMinutes) {
        this.clipboardTtlMinutes = clipboardTtlMinutes;
    }
}
