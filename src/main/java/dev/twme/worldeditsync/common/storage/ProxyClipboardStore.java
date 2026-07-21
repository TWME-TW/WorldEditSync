package dev.twme.worldeditsync.common.storage;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.model.ClipboardPayload;
import dev.twme.worldeditsync.common.protocol.TransferSession;

/**
 * Memory-bounded clipboard and upload storage shared by proxy implementations.
 * Declared upload sizes are reserved before any large receive buffer is allocated.
 */
public class ProxyClipboardStore {

    private final Map<UUID, ClipboardPayload> clipboards = new HashMap<>();
    private final Map<String, TransferSession> uploadSessions = new HashMap<>();
    private final Map<String, UUID> sessionOwners = new HashMap<>();
    private final Map<UUID, String> ownerSessions = new HashMap<>();
    private final long maxMemoryBytes;

    private long storedBytes;
    private long reservedUploadBytes;

    public ProxyClipboardStore() {
        this(Constants.DEFAULT_TRANSFER_MEMORY_LIMIT_BYTES);
    }

    public ProxyClipboardStore(long maxMemoryBytes) {
        if (maxMemoryBytes <= 0L) {
            throw new IllegalArgumentException("maxMemoryBytes must be positive");
        }
        this.maxMemoryBytes = maxMemoryBytes;
    }

    public synchronized boolean storeClipboard(UUID playerId, byte[] data, String hash) {
        if (playerId == null || data == null || data.length == 0 || hash == null) {
            return false;
        }
        ClipboardPayload previous = clipboards.get(playerId);
        long previousBytes = previous == null ? 0L : previous.getData().length;
        if (!makeRoom(data.length - previousBytes, playerId)) {
            return false;
        }
        clipboards.put(playerId, new ClipboardPayload(data, hash));
        storedBytes += data.length - previousBytes;
        return true;
    }

    /** Commits only if this is still the player's current, complete upload. */
    public synchronized boolean completeUploadSession(String sessionId, UUID playerId,
                                                       TransferSession expectedSession) {
        if (!sessionId.equals(ownerSessions.get(playerId))
                || !playerId.equals(sessionOwners.get(sessionId))
                || uploadSessions.get(sessionId) != expectedSession
                || !expectedSession.isComplete()) {
            return false;
        }

        byte[] data = expectedSession.assemble();
        ClipboardPayload previous = clipboards.put(playerId,
                new ClipboardPayload(data, expectedSession.getExpectedHash()));
        if (previous != null) {
            storedBytes -= previous.getData().length;
        }
        storedBytes += data.length;
        detachUploadSession(sessionId);
        return true;
    }

    public synchronized ClipboardPayload getClipboard(UUID playerId) {
        return clipboards.get(playerId);
    }

    public synchronized boolean hasClipboard(UUID playerId) {
        return clipboards.containsKey(playerId);
    }

    public synchronized boolean addUploadSession(String sessionId, UUID playerId,
                                                 TransferSession session) {
        if (sessionId == null || playerId == null || session == null
                || uploadSessions.containsKey(sessionId)) {
            return false;
        }

        String previousSessionId = ownerSessions.get(playerId);
        TransferSession previousSession = previousSessionId == null
                ? null : uploadSessions.get(previousSessionId);
        long previousReservation = previousSession == null ? 0L : previousSession.getTotalBytes();
        long additionalReservation = (long) session.getTotalBytes() - previousReservation;
        if (!makeRoom(additionalReservation, null)) {
            return false;
        }
        if (previousSessionId != null) {
            removeUploadSession(previousSessionId);
        }

        uploadSessions.put(sessionId, session);
        sessionOwners.put(sessionId, playerId);
        ownerSessions.put(playerId, sessionId);
        reservedUploadBytes += session.getTotalBytes();
        return true;
    }

    public synchronized TransferSession getUploadSession(String sessionId) {
        return uploadSessions.get(sessionId);
    }

    public synchronized UUID getSessionOwner(String sessionId) {
        return sessionOwners.get(sessionId);
    }

    public synchronized void removeUploadSession(String sessionId) {
        TransferSession removed = detachUploadSession(sessionId);
        if (removed != null) {
            removed.release();
        }
    }

    public synchronized boolean removeUploadSession(String sessionId, UUID expectedOwner) {
        if (expectedOwner == null || !expectedOwner.equals(sessionOwners.get(sessionId))) {
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

    public synchronized boolean hasActiveUpload(UUID playerId) {
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

    public synchronized void cleanupExpiredSessions(long timeoutMs) {
        for (String sessionId : uploadSessions.entrySet().stream()
                .filter(entry -> entry.getValue().isExpired(timeoutMs))
                .map(Map.Entry::getKey)
                .toList()) {
            removeUploadSession(sessionId);
        }
    }

    public synchronized void cleanupExpiredClipboards(long ttlMinutes) {
        if (ttlMinutes <= 0L) {
            return;
        }
        for (UUID playerId : clipboards.entrySet().stream()
                .filter(entry -> entry.getValue().isExpired(ttlMinutes))
                .map(Map.Entry::getKey)
                .toList()) {
            removeClipboard(playerId);
        }
    }

    public synchronized long getUsedMemoryBytes() {
        return storedBytes + reservedUploadBytes;
    }

    public synchronized long getStoredBytes() {
        return storedBytes;
    }

    public synchronized long getReservedUploadBytes() {
        return reservedUploadBytes;
    }

    public synchronized void shutdown() {
        for (TransferSession session : uploadSessions.values()) {
            session.release();
        }
        clipboards.clear();
        uploadSessions.clear();
        sessionOwners.clear();
        ownerSessions.clear();
        storedBytes = 0L;
        reservedUploadBytes = 0L;
    }

    private boolean makeRoom(long additionalBytes, UUID protectedPlayer) {
        if (additionalBytes <= 0L) {
            return true;
        }
        if (additionalBytes > maxMemoryBytes
                || getUsedMemoryBytes() > maxMemoryBytes - additionalBytes) {
            for (UUID playerId : clipboards.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(protectedPlayer))
                    .sorted(Comparator.comparingLong(entry -> entry.getValue().getTimestamp()))
                    .map(Map.Entry::getKey)
                    .toList()) {
                removeClipboard(playerId);
                if (getUsedMemoryBytes() <= maxMemoryBytes - additionalBytes) {
                    return true;
                }
            }
        }
        return getUsedMemoryBytes() <= maxMemoryBytes - additionalBytes;
    }

    private void removeClipboard(UUID playerId) {
        ClipboardPayload removed = clipboards.remove(playerId);
        if (removed != null) {
            storedBytes -= removed.getData().length;
        }
    }

    /** Removes bookkeeping without releasing data that has moved into clipboard storage. */
    private TransferSession detachUploadSession(String sessionId) {
        TransferSession removed = uploadSessions.remove(sessionId);
        UUID owner = sessionOwners.remove(sessionId);
        if (owner != null) {
            ownerSessions.remove(owner, sessionId);
        }
        if (removed != null) {
            reservedUploadBytes -= removed.getTotalBytes();
        }
        return removed;
    }
}
