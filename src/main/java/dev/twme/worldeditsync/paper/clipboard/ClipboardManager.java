package dev.twme.worldeditsync.paper.clipboard;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import dev.twme.worldeditsync.common.model.SyncState;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.protocol.TransferSession;
import dev.twme.worldeditsync.common.protocol.TransferMemoryBudget;

/**
 * Manages per-player sync state, local clipboard hash cache, and active transfer sessions.
 * All state transitions use AtomicReference.compareAndSet to avoid race conditions.
 */
public class ClipboardManager {

    private final ConcurrentHashMap<UUID, AtomicReference<SyncState>> playerStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> playerTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> localHashes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, SerializedClipboard> serializedClipboards = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> activeSessionIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TransferSession> downloadSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TransferSession> processingDownloadSessions = new ConcurrentHashMap<>();
    private volatile TransferMemoryBudget transferMemoryBudget;
    private final LongSupplier clock;

    public ClipboardManager() {
        this(System::currentTimeMillis);
    }

    ClipboardManager(LongSupplier clock) {
        this.clock = clock;
    }

    public void setTransferMemoryBudget(TransferMemoryBudget transferMemoryBudget) {
        this.transferMemoryBudget = transferMemoryBudget;
    }

    public boolean tryReserveTransferMemory(long bytes) {
        TransferMemoryBudget budget = transferMemoryBudget;
        return budget == null || budget.tryReserve(bytes);
    }

    public void releaseTransferMemory(long bytes) {
        TransferMemoryBudget budget = transferMemoryBudget;
        if (budget != null) {
            budget.release(bytes);
        }
    }

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
        playerTokens.put(playerId, new Object());
        playerStates.put(playerId, new AtomicReference<>(initialState));
        serializedClipboards.remove(playerId);
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

    /** Identifies one connection lifetime so old async work cannot affect a rejoin. */
    public Object getPlayerToken(UUID playerId) {
        return playerTokens.get(playerId);
    }

    public boolean isCurrentPlayerToken(UUID playerId, Object token) {
        return token != null && playerTokens.get(playerId) == token;
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
        serializedClipboards.remove(playerId);
    }

    /** Returns true when this clipboard instance was confirmed recently enough to skip a scan. */
    public boolean isSerializedClipboard(UUID playerId, Object clipboard) {
        SerializedClipboard serialized = serializedClipboards.get(playerId);
        String localHash = localHashes.get(playerId);
        long now = clock.getAsLong();
        return clipboard != null && serialized != null && serialized.clipboard == clipboard
                && serialized.hash.equals(localHash)
                && now >= serialized.timestamp
                && now - serialized.timestamp < Constants.UNCHANGED_CLIPBOARD_RECHECK_MS;
    }

    public void markSerializedClipboard(UUID playerId, Object clipboard, String hash) {
        if (clipboard != null && hash != null) {
            serializedClipboards.put(playerId,
                    new SerializedClipboard(clipboard, hash, clock.getAsLong()));
        }
    }

    public void clearSerializedClipboard(UUID playerId, Object expectedClipboard) {
        if (expectedClipboard == null) {
            serializedClipboards.remove(playerId);
        } else {
            serializedClipboards.computeIfPresent(playerId,
                    (ignored, serialized) -> serialized.clipboard == expectedClipboard
                            ? null : serialized);
        }
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

    public synchronized boolean addDownloadSession(String sessionId, TransferSession session) {
        TransferMemoryBudget budget = transferMemoryBudget;
        if (budget != null && !budget.tryReserve(session.getTotalBytes())) {
            return false;
        }
        TransferSession previous = downloadSessions.putIfAbsent(sessionId, session);
        if (previous != null) {
            releaseReservation(session);
            return false;
        }
        return true;
    }

    public TransferSession getDownloadSession(String sessionId) {
        return downloadSessions.get(sessionId);
    }

    /** Takes ownership out of the session map while async completion is in progress. */
    public synchronized boolean detachDownloadSession(
            String sessionId, TransferSession expectedSession) {
        if (!downloadSessions.remove(sessionId, expectedSession)) {
            return false;
        }
        TransferSession previous = processingDownloadSessions.putIfAbsent(
                sessionId, expectedSession);
        if (previous == null) {
            return true;
        }
        releaseReservation(expectedSession);
        expectedSession.release();
        return false;
    }

    public synchronized void releaseDetachedDownloadSession(TransferSession session) {
        if (processingDownloadSessions.remove(session.getSessionId(), session)) {
            releaseReservation(session);
            session.release();
        }
    }

    public synchronized void removeDownloadSession(String sessionId) {
        TransferSession removed = downloadSessions.remove(sessionId);
        if (removed == null) {
            removed = processingDownloadSessions.remove(sessionId);
        }
        if (removed != null) {
            releaseReservation(removed);
            removed.release();
        }
    }

    // ── Cleanup ──

    public void removePlayer(UUID playerId) {
        playerStates.remove(playerId);
        playerTokens.remove(playerId);
        localHashes.remove(playerId);
        serializedClipboards.remove(playerId);
        String sessionId = activeSessionIds.remove(playerId);
        if (sessionId != null) {
            removeDownloadSession(sessionId);
        }
    }

    public synchronized void cleanupExpiredSessions(long timeoutMs) {
        for (var entry : downloadSessions.entrySet()) {
            TransferSession session = entry.getValue();
            if (session.isExpired(timeoutMs)
                    && downloadSessions.remove(entry.getKey(), session)) {
                releaseReservation(session);
                session.release();
            }
        }
    }

    public synchronized void shutdown() {
        playerStates.clear();
        playerTokens.clear();
        localHashes.clear();
        serializedClipboards.clear();
        activeSessionIds.clear();
        downloadSessions.forEach((sessionId, session) -> {
            if (downloadSessions.remove(sessionId, session)) {
                releaseReservation(session);
                session.release();
            }
        });
        processingDownloadSessions.forEach((sessionId, session) -> {
            if (processingDownloadSessions.remove(sessionId, session)) {
                releaseReservation(session);
                session.release();
            }
        });
    }

    private record SerializedClipboard(Object clipboard, String hash, long timestamp) {
    }

    private void releaseReservation(TransferSession session) {
        TransferMemoryBudget budget = transferMemoryBudget;
        if (budget != null) {
            releaseTransferMemory(session.getTotalBytes());
        }
    }
}
