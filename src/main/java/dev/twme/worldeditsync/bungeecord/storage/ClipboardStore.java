package dev.twme.worldeditsync.bungeecord.storage;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.twme.worldeditsync.common.model.ClipboardPayload;
import dev.twme.worldeditsync.common.protocol.TransferSession;

/**
 * Proxy-side clipboard storage for BungeeCord.
 * Stores encrypted clipboard data per player with TTL support.
 */
public class ClipboardStore {

    private final ConcurrentHashMap<UUID, ClipboardPayload> clipboards = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TransferSession> uploadSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> sessionOwners = new ConcurrentHashMap<>();

    // ── Clipboard storage ──

    public void storeClipboard(UUID playerId, byte[] data, String hash) {
        clipboards.put(playerId, new ClipboardPayload(data, hash));
    }

    public ClipboardPayload getClipboard(UUID playerId) {
        return clipboards.get(playerId);
    }

    public boolean hasClipboard(UUID playerId) {
        return clipboards.containsKey(playerId);
    }

    // ── Upload session management ──

    public void addUploadSession(String sessionId, UUID playerId, TransferSession session) {
        uploadSessions.put(sessionId, session);
        sessionOwners.put(sessionId, playerId);
    }

    public TransferSession getUploadSession(String sessionId) {
        return uploadSessions.get(sessionId);
    }

    public UUID getSessionOwner(String sessionId) {
        return sessionOwners.get(sessionId);
    }

    public void removeUploadSession(String sessionId) {
        uploadSessions.remove(sessionId);
        sessionOwners.remove(sessionId);
    }

    // ── Cleanup ──

    public void cleanupExpiredSessions(long timeoutMs) {
        uploadSessions.entrySet().removeIf(entry -> {
            if (entry.getValue().isExpired(timeoutMs)) {
                sessionOwners.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    public void cleanupExpiredClipboards(long ttlMinutes) {
        if (ttlMinutes <= 0) return;
        clipboards.entrySet().removeIf(entry -> entry.getValue().isExpired(ttlMinutes));
    }

    public void shutdown() {
        clipboards.clear();
        uploadSessions.clear();
        sessionOwners.clear();
    }
}
