package dev.twme.worldeditsync.common.protocol;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.twme.worldeditsync.common.Constants;

/** Bounds per-player protocol work before messages are decrypted or parsed. */
public final class InboundMessageLimiter {

    private static final long WINDOW_MS = 1_000L;

    private final ConcurrentHashMap<UUID, Window> windows = new ConcurrentHashMap<>();

    public boolean tryAcquire(UUID playerId, int bytes) {
        if (playerId == null || bytes < 0 || bytes > Constants.MAX_PLUGIN_MESSAGE_SIZE) {
            return false;
        }
        return windows.computeIfAbsent(playerId, ignored -> new Window())
                .tryAcquire(bytes, System.currentTimeMillis());
    }

    public void remove(UUID playerId) {
        windows.remove(playerId);
    }

    private static final class Window {
        private long startedAt = System.currentTimeMillis();
        private int messages;
        private int bytes;

        private synchronized boolean tryAcquire(int messageBytes, long now) {
            if (now - startedAt >= WINDOW_MS || now < startedAt) {
                startedAt = now;
                messages = 0;
                bytes = 0;
            }
            if (messages >= Constants.MAX_INBOUND_MESSAGES_PER_SECOND
                    || bytes > Constants.MAX_INBOUND_BYTES_PER_SECOND - messageBytes) {
                return false;
            }
            messages++;
            bytes += messageBytes;
            return true;
        }
    }
}
