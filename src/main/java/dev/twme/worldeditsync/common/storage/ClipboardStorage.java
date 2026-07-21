package dev.twme.worldeditsync.common.storage;

import java.util.function.Consumer;

/**
 * Shared clipboard storage used by Paper servers in storage-backed sync modes.
 * Implementations must be safe to call from asynchronous worker threads.
 */
public interface ClipboardStorage extends AutoCloseable {

    /** Connect to the backend and create any required schema or container. */
    boolean initialize() throws Exception;

    /** Return immutable metadata for a clipboard without downloading its payload. */
    StoredClipboard inspect(String playerId) throws Exception;

    /** Atomically replace a player's clipboard and metadata. */
    void upload(String playerId, byte[] data, String hash, long updatedAt) throws Exception;

    /**
     * Download the exact version returned by {@link #inspect(String)}.
     * Implementations reject a concurrent replacement instead of returning mixed metadata/data.
     */
    byte[] download(String playerId, StoredClipboard expected) throws Exception;

    /** Human-readable backend name used in logs. */
    String description();

    /** Register an optional lightweight notification for remotely changed player IDs. */
    default void setUpdateListener(Consumer<String> listener) {
    }

    @Override
    default void close() throws Exception {
    }
}
