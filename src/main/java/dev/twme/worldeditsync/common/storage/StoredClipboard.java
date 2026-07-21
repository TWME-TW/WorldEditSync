package dev.twme.worldeditsync.common.storage;

/** Immutable metadata for one stored clipboard version. */
public record StoredClipboard(boolean exists, String hash, long storedSize, long updatedAt) {

    public static StoredClipboard missing() {
        return new StoredClipboard(false, "", 0L, 0L);
    }
}
