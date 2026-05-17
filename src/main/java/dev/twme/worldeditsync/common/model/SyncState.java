package dev.twme.worldeditsync.common.model;

/**
 * Sync state for a player's clipboard on a Paper server.
 * Used with AtomicReference + compareAndSet for thread-safe transitions.
 */
public enum SyncState {

    /**
     * Player just joined; waiting for the proxy to send SYNC_HASH or SYNC_NO_DATA.
     * ClipboardWatcher will not upload while in this state.
     */
    PENDING_SYNC,

    /** Ready to detect changes or receive sync messages. */
    IDLE,

    /** Currently uploading clipboard to proxy/S3. */
    UPLOADING,

    /** Received SYNC_HASH, checking whether download is needed. */
    CHECKING,

    /** Currently downloading clipboard from proxy/S3. */
    DOWNLOADING
}
