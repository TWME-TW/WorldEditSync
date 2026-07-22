package dev.twme.worldeditsync.common.protocol;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import dev.twme.worldeditsync.common.Constants;

/** Bounds protocol work before messages are decrypted or parsed. */
public final class InboundMessageLimiter {

    private static final long WINDOW_MS = 1_000L;

    private final ConcurrentHashMap<UUID, Window> windows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> blockedPlayers = new ConcurrentHashMap<>();
    private final Window invalidMessages = new Window();
    private final LongSupplier clock;
    private volatile long globallyBlockedUntil;

    public InboundMessageLimiter() {
        this(System::currentTimeMillis);
    }

    InboundMessageLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    public boolean tryAcquire(UUID playerId, int bytes) {
        if (playerId == null || bytes < 0 || bytes > Constants.MAX_PLUGIN_MESSAGE_SIZE) {
            return false;
        }
        long now = clock.getAsLong();
        if (now < globallyBlockedUntil) {
            return false;
        }
        Long blockedUntil = blockedPlayers.get(playerId);
        if (blockedUntil != null) {
            if (now < blockedUntil) {
                return false;
            }
            blockedPlayers.remove(playerId, blockedUntil);
        }
        return windows.computeIfAbsent(playerId, ignored -> new Window())
                .tryAcquire(bytes, now,
                        Constants.MAX_INBOUND_MESSAGES_PER_SECOND,
                        Constants.MAX_INBOUND_BYTES_PER_SECOND);
    }

    /**
     * Applies a cheap per-player circuit breaker after authentication or decoding fails.
     * A global breaker bounds aggregate work from many attacking connections.
     */
    public void recordInvalidMessage(UUID playerId) {
        if (playerId == null) {
            return;
        }
        long now = clock.getAsLong();
        blockedPlayers.put(playerId, saturatingAdd(
                now, Constants.INVALID_MESSAGE_PLAYER_COOLDOWN_MS));
        if (!invalidMessages.tryAcquire(0, now,
                Constants.MAX_INVALID_MESSAGES_PER_SECOND, Integer.MAX_VALUE)) {
            globallyBlockedUntil = saturatingAdd(
                    now, Constants.INVALID_MESSAGE_GLOBAL_COOLDOWN_MS);
        }
    }

    public void remove(UUID playerId) {
        windows.remove(playerId);
        blockedPlayers.remove(playerId);
    }

    public void clear() {
        windows.clear();
        blockedPlayers.clear();
        invalidMessages.reset();
        globallyBlockedUntil = 0L;
    }

    private long saturatingAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private static final class Window {
        private long startedAt = Long.MIN_VALUE;
        private int messages;
        private int bytes;

        private synchronized boolean tryAcquire(int messageBytes, long now,
                                                int maxMessages, int maxBytes) {
            if (startedAt == Long.MIN_VALUE || now < startedAt
                    || now - startedAt >= WINDOW_MS) {
                startedAt = now;
                messages = 0;
                bytes = 0;
            }
            if (messages >= maxMessages || bytes > maxBytes - messageBytes) {
                return false;
            }
            messages++;
            bytes += messageBytes;
            return true;
        }

        private synchronized void reset() {
            startedAt = Long.MIN_VALUE;
            messages = 0;
            bytes = 0;
        }
    }
}
