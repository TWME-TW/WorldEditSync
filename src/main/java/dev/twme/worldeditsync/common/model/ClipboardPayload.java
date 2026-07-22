package dev.twme.worldeditsync.common.model;

/**
 * Proxy-owned clipboard payload. Callers must treat the backing data as read-only.
 */
public class ClipboardPayload {

    private final byte[] data;
    private final String hash;
    private final long timestamp;

    public ClipboardPayload(byte[] data, String hash) {
        this.data = data;
        this.hash = hash;
        this.timestamp = System.currentTimeMillis();
    }

    public ClipboardPayload(byte[] data, String hash, long timestamp) {
        this.data = data;
        this.hash = hash;
        this.timestamp = timestamp;
    }

    public byte[] getData() {
        return data;
    }

    public String getHash() {
        return hash;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isExpired(long ttlMinutes) {
        if (ttlMinutes <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (timestamp > now) {
            return false;
        }
        long ttlMillis = ttlMinutes > Long.MAX_VALUE / 60_000L
                ? Long.MAX_VALUE : ttlMinutes * 60_000L;
        return now - timestamp >= ttlMillis;
    }
}
