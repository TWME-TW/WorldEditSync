package dev.twme.worldeditsync.common.protocol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import dev.twme.worldeditsync.common.Constants;

public class InboundMessageLimiterTest {

    @Test
    public void capsMessagesBeforeProtocolProcessing() {
        InboundMessageLimiter limiter = new InboundMessageLimiter();
        UUID playerId = UUID.randomUUID();

        for (int i = 0; i < Constants.MAX_INBOUND_MESSAGES_PER_SECOND; i++) {
            assertTrue(limiter.tryAcquire(playerId, 1));
        }
        assertFalse(limiter.tryAcquire(playerId, 1));
    }

    @Test
    public void rejectsInvalidSizesAndTracksPlayersSeparately() {
        InboundMessageLimiter limiter = new InboundMessageLimiter();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertFalse(limiter.tryAcquire(first, -1));
        assertFalse(limiter.tryAcquire(first, Constants.MAX_PLUGIN_MESSAGE_SIZE + 1));
        assertTrue(limiter.tryAcquire(second, Constants.MAX_PLUGIN_MESSAGE_SIZE));
    }

    @Test
    public void invalidMessageTemporarilyBlocksOnlyItsPlayer() {
        AtomicLong clock = new AtomicLong(10_000L);
        InboundMessageLimiter limiter = new InboundMessageLimiter(clock::get);
        UUID attacker = UUID.randomUUID();
        UUID legitimate = UUID.randomUUID();

        limiter.recordInvalidMessage(attacker);

        assertFalse(limiter.tryAcquire(attacker, 1));
        assertTrue(limiter.tryAcquire(legitimate, 1));
        clock.addAndGet(Constants.INVALID_MESSAGE_PLAYER_COOLDOWN_MS);
        assertTrue(limiter.tryAcquire(attacker, 1));
    }

    @Test
    public void aggregateInvalidTrafficTripsShortGlobalBreaker() {
        AtomicLong clock = new AtomicLong(20_000L);
        InboundMessageLimiter limiter = new InboundMessageLimiter(clock::get);
        for (int i = 0; i <= Constants.MAX_INVALID_MESSAGES_PER_SECOND; i++) {
            limiter.recordInvalidMessage(UUID.randomUUID());
        }

        assertFalse(limiter.tryAcquire(UUID.randomUUID(), 1));
        clock.addAndGet(Constants.INVALID_MESSAGE_GLOBAL_COOLDOWN_MS);
        assertTrue(limiter.tryAcquire(UUID.randomUUID(), 1));
    }

    @Test
    public void clearDropsPlayerAndGlobalCircuitBreakerState() {
        AtomicLong clock = new AtomicLong(30_000L);
        InboundMessageLimiter limiter = new InboundMessageLimiter(clock::get);
        UUID playerId = UUID.randomUUID();
        for (int i = 0; i <= Constants.MAX_INVALID_MESSAGES_PER_SECOND; i++) {
            limiter.recordInvalidMessage(UUID.randomUUID());
        }
        limiter.recordInvalidMessage(playerId);

        limiter.clear();

        assertTrue(limiter.tryAcquire(playerId, 1));
    }
}
