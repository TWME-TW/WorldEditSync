package dev.twme.worldeditsync.common.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TransferSessionTest {

    @Test
    public void assemblesOutOfOrderChunksExactlyOnce() {
        TransferSession session = new TransferSession("session", 2, 5, "hash");

        assertTrue(session.addChunk(1, new byte[] {4, 5}));
        assertFalse(session.isComplete());
        assertTrue(session.addChunk(0, new byte[] {1, 2, 3}));
        assertTrue(session.isComplete());
        assertTrue(session.tryClaimCompletion());
        assertFalse(session.tryClaimCompletion());
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, session.assemble());
    }

    @Test
    public void ignoresDuplicateChunkWithoutReplacingData() {
        TransferSession session = new TransferSession("session", 1, 2, "hash");

        assertTrue(session.addChunk(0, new byte[] {1, 2}));
        assertFalse(session.addChunk(0, new byte[] {9, 9}));
        assertArrayEquals(new byte[] {1, 2}, session.assemble());
    }

    @Test
    public void rejectsInvalidChunkIndexAndOverflow() {
        TransferSession session = new TransferSession("session", 2, 3, "hash");

        assertThrows(IllegalArgumentException.class,
                () -> session.addChunk(2, new byte[] {1}));
        assertTrue(session.addChunk(0, new byte[] {1, 2}));
        assertThrows(IllegalArgumentException.class,
                () -> session.addChunk(1, new byte[] {3, 4}));
    }

    @Test
    public void validatesDeclaredChunkLayout() {
        assertTrue(TransferSession.isValidLayout(60_001, 3, 30_000));
        assertFalse(TransferSession.isValidLayout(60_001, 2, 30_000));
        assertFalse(TransferSession.isValidLayout(0, 0, 30_000));
    }

    @Test
    public void expiresAtTheConfiguredDeadline() {
        TransferSession session = new TransferSession("session", 1, 1, "hash");

        assertTrue(session.isExpired(0));
    }
}
