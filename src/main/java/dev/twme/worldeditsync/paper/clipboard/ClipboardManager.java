package dev.twme.worldeditsync.paper.clipboard;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import dev.twme.worldeditsync.common.model.SyncState;
import dev.twme.worldeditsync.common.protocol.TransferSession;

/**
 * Manages per-player sync state, local clipboard hash cache, and active transfer sessions.
 * All state transitions use AtomicReference.compareAndSet to avoid race conditions.
 */
public class ClipboardManager {

    private final ConcurrentHashMap<UUID, AtomicReference<SyncState>> playerStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> localHashes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> activeSessionIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TransferSession> downloadSessions = new ConcurrentHashMap<>();

    // ── State management ──

    public SyncState getState(UUID playerId) {
        AtomicReference<SyncState> ref = playerStates.get(playerId);
        return ref != null ? ref.get() : null;
    }

    /**
     * Initialises a player with PENDING_SYNC state (proxy mode).
     * The watcher will not upload until SYNC_HASH or SYNC_NO_DATA is received.
     */
    public void initPlayer(UUID playerId) {
        initPlayer(playerId, SyncState.PENDING_SYNC);
    }

    /**
     * Initialises a player with an explicit initial state.
     * S3 mode passes IDLE directly because it manages its own join-time check.
     */
    public void initPlayer(UUID playerId, SyncState initialState) {
        playerStates.put(playerId, new AtomicReference<>(initialState));
    }

    /**
     * Atomically transition state. Returns true if successful.
     */
    public boolean compareAndSetState(UUID playerId, SyncState expected, SyncState newState) {
        AtomicReference<SyncState> ref = playerStates.get(playerId);
        if (ref == null) return false;
        return ref.compareAndSet(expected, newState);
    }

    /**
     * Atomically transition state from either PENDING_SYNC or IDLE.
     * Used when handling initial proxy sync messages (SYNC_HASH / SYNC_NO_DATA)
     * where the player may still be in the initial join gate.
     * Returns true if successful.
     */
    public boolean transitionFromPendingOrIdle(UUID playerId, SyncState newState) {
        AtomicReference<SyncState> ref = playerStates.get(playerId);
        if (ref == null) return false;
        return ref.compareAndSet(SyncState.PENDING_SYNC, newState)
                || ref.compareAndSet(SyncState.IDLE, newState);
    }

    /**
     * Force set state (for error recovery / cleanup).
     */
    public void forceSetState(UUID playerId, SyncState state) {
        AtomicReference<SyncState> ref = playerStates.get(playerId);
        if (ref != null) {
            ref.set(state);
        }
    }

    public boolean isIdle(UUID playerId) {
        return getState(playerId) == SyncState.IDLE;
    }

    public boolean isTracked(UUID playerId) {
        return playerStates.containsKey(playerId);
    }

    // ── Hash cache ──

    public String getLocalHash(UUID playerId) {
        return localHashes.get(playerId);
    }

    public void setLocalHash(UUID playerId, String hash) {
        localHashes.put(playerId, hash);
    }

    public void forgetClipboard(UUID playerId) {
        localHashes.remove(playerId);
    }

    // ── Session management ──

    public void setActiveSessionId(UUID playerId, String sessionId) {
        activeSessionIds.put(playerId, sessionId);
    }

    public String getActiveSessionId(UUID playerId) {
        return activeSessionIds.get(playerId);
    }

    public void clearActiveSession(UUID playerId) {
        activeSessionIds.remove(playerId);
    }

    public void addDownloadSession(String sessionId, TransferSession session) {
        downloadSessions.put(sessionId, session);
    }

    public TransferSession getDownloadSession(String sessionId) {
        return downloadSessions.get(sessionId);
    }

    public void removeDownloadSession(String sessionId) {
        downloadSessions.remove(sessionId);
    }

    // ── Cleanup ──

    public void removePlayer(UUID playerId) {
        playerStates.remove(playerId);
        localHashes.remove(playerId);
        String sessionId = activeSessionIds.remove(playerId);
        if (sessionId != null) {
            downloadSessions.remove(sessionId);
        }
    }

    public void cleanupExpiredSessions(long timeoutMs) {
        downloadSessions.entrySet().removeIf(entry -> entry.getValue().isExpired(timeoutMs));
    }

    public void shutdown() {
        playerStates.clear();
        localHashes.clear();
        activeSessionIds.clear();
        downloadSessions.clear();
    }
}
