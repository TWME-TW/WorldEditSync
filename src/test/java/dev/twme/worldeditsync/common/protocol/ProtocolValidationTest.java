package dev.twme.worldeditsync.common.protocol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.junit.Test;

public class ProtocolValidationTest {

    @Test
    public void validatesCanonicalSessionIdsAndSha256Hashes() {
        assertTrue(ProtocolValidation.isSessionId(UUID.randomUUID().toString()));
        assertFalse(ProtocolValidation.isSessionId("session-id"));
        assertFalse(ProtocolValidation.isSessionId("00000000-0-0-0-000000000001"));

        assertTrue(ProtocolValidation.isSha256("a".repeat(64)));
        assertTrue(ProtocolValidation.isSha256("ABCDEF0123456789".repeat(4)));
        assertFalse(ProtocolValidation.isSha256("hash"));
        assertFalse(ProtocolValidation.isSha256("g".repeat(64)));
    }
}
