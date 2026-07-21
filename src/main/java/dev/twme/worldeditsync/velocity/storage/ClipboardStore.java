package dev.twme.worldeditsync.velocity.storage;

import dev.twme.worldeditsync.common.model.ClipboardPayload;
import dev.twme.worldeditsync.common.protocol.TransferSession;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proxy-side clipboard storage for Velocity.
 * Stores encrypted clipboard data per player with TTL support.
 */
public class ClipboardStore {

    private final ConcurrentHashMap<UUID, ClipboardPayload> clipboards = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TransferSession> uploadSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> sessionOwners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> ownerSessions = new ConcurrentHashMap<>();

    // ── Clipboard storage ──

    public void storeClipboard(UUID playerId, byte[] data, String hash) {
        clipboards.put(playerId, new ClipboardPayload(data, hash));
    }

    /** Commit only if this is still the player's current upload session. */
    public synchronized boolean completeUploadSession(String sessionId, UUID playerId,
                                                      TransferSession expectedSession,
                                                      byte[] data, String hash) {
        if (!sessionId.equals(ownerSessions.get(playerId))
                || !playerId.equals(sessionOwners.get(sessionId))
                || uploadSessions.get(sessionId) != expectedSession) {
            return false;
        }
        clipboards.put(playerId, new ClipboardPayload(data, hash));
        removeUploadSession(sessionId);
        return true;
    }

    public ClipboardPayload getClipboard(UUID playerId) {
        return clipboards.get(playerId);
    }

    public boolean hasClipboard(UUID playerId) {
        return clipboards.containsKey(playerId);
    }

    // ── Upload session management ──

    public synchronized boolean addUploadSession(String sessionId, UUID playerId, TransferSession session) {
        if (uploadSessions.containsKey(sessionId)) {
            return false;
        }
        String previousSession = ownerSessions.put(playerId, sessionId);
        if (previousSession != null) {
            uploadSessions.remove(previousSession);
            sessionOwners.remove(previousSession);
        }
        uploadSessions.put(sessionId, session);
        sessionOwners.put(sessionId, playerId);
        return true;
    }

    public TransferSession getUploadSession(String sessionId) {
        return uploadSessions.get(sessionId);
    }

    public UUID getSessionOwner(String sessionId) {
        return sessionOwners.get(sessionId);
    }

    public synchronized void removeUploadSession(String sessionId) {
        uploadSessions.remove(sessionId);
        UUID owner = sessionOwners.remove(sessionId);
        if (owner != null) {
            ownerSessions.remove(owner, sessionId);
        }
    }

    public synchronized boolean removeUploadSession(String sessionId, UUID expectedOwner) {
        if (!expectedOwner.equals(sessionOwners.get(sessionId))) {
            return false;
        }
        removeUploadSession(sessionId);
        return true;
    }

    public synchronized boolean removeUploadSession(String sessionId, UUID expectedOwner,
                                                    TransferSession expectedSession) {
        if (uploadSessions.get(sessionId) != expectedSession) {
            return false;
        }
        return removeUploadSession(sessionId, expectedOwner);
    }

    public boolean hasActiveUpload(UUID playerId) {
        return ownerSessions.containsKey(playerId);
    }

    public synchronized TransferSession getUploadSessionForOwner(UUID playerId) {
        String sessionId = ownerSessions.get(playerId);
        return sessionId == null ? null : uploadSessions.get(sessionId);
    }

    public synchronized void removeUploadSessionForOwner(UUID playerId) {
        String sessionId = ownerSessions.get(playerId);
        if (sessionId != null) {
            removeUploadSession(sessionId);
        }
    }

    public synchronized void removeIncompleteUploadSessionForOwner(UUID playerId) {
        String sessionId = ownerSessions.get(playerId);
        TransferSession session = sessionId == null ? null : uploadSessions.get(sessionId);
        if (session != null && !session.isComplete()) {
            removeUploadSession(sessionId);
        }
    }

    // ── Cleanup ──

    public synchronized void cleanupExpiredSessions(long timeoutMs) {
        uploadSessions.entrySet().removeIf(entry -> {
            if (!entry.getValue().isComplete() && entry.getValue().isExpired(timeoutMs)) {
                UUID owner = sessionOwners.remove(entry.getKey());
                if (owner != null) {
                    ownerSessions.remove(owner, entry.getKey());
                }
                return true;
            }
            return false;
        });
    }

    public void cleanupExpiredClipboards(long ttlMinutes) {
        if (ttlMinutes <= 0) return;
        clipboards.entrySet().removeIf(entry -> entry.getValue().isExpired(ttlMinutes));
    }

    public synchronized void shutdown() {
        clipboards.clear();
        uploadSessions.clear();
        sessionOwners.clear();
        ownerSessions.clear();
    }
}
