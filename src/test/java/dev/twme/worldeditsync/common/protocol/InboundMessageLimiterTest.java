package dev.twme.worldeditsync.common.protocol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

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
}
