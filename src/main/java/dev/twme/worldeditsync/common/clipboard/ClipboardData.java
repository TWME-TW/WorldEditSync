package dev.twme.worldeditsync.common.clipboard;

/**
 * 存放剪貼簿的序列化資料與其 SHA-256 雜湊值。
 * 用於 Proxy 端儲存和 Paper 端快取。
 */
public class ClipboardData {
    private final byte[] data;
    private final String hash;
    private final long timestamp;

    public ClipboardData(byte[] data, String hash) {
        this.data = data;
        this.hash = hash;
        this.timestamp = System.currentTimeMillis();
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

    public int getSize() {
        return data != null ? data.length : 0;
    }
}
