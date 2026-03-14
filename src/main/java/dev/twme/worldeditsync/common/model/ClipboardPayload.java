package dev.twme.worldeditsync.common.model;

/**
 * Immutable representation of a clipboard payload stored on the Proxy or S3.
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
        long age = System.currentTimeMillis() - timestamp;
        return age > ttlMinutes * 60_000L;
    }
}
