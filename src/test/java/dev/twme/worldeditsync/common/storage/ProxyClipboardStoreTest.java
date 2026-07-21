package dev.twme.worldeditsync.common.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.junit.Test;

import dev.twme.worldeditsync.common.protocol.TransferSession;

public class ProxyClipboardStoreTest {

    @Test
    public void evictsOldestClipboardInsteadOfExceedingMemoryBudget() {
        ProxyClipboardStore store = new ProxyClipboardStore(5L);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(store.storeClipboard(first, new byte[] {1, 2, 3}, "first"));
        assertTrue(store.storeClipboard(second, new byte[] {4, 5, 6}, "second"));

        assertNull(store.getClipboard(first));
        assertTrue(store.hasClipboard(second));
        assertEquals(3L, store.getUsedMemoryBytes());
    }

    @Test
    public void reservesDeclaredUploadBytesBeforeAllocatingChunks() {
        ProxyClipboardStore store = new ProxyClipboardStore(4L);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        TransferSession full = new TransferSession("full", 1, 4, 4, "hash");
        TransferSession extra = new TransferSession("extra", 1, 1, 1, "hash");

        assertTrue(store.addUploadSession("full", first, full));
        assertEquals(4L, store.getReservedUploadBytes());
        assertFalse(store.addUploadSession("extra", second, extra));
        assertEquals(4L, store.getUsedMemoryBytes());
    }

    @Test
    public void completionMovesReservationWithoutDuplicatingAccounting() {
        ProxyClipboardStore store = new ProxyClipboardStore(4L);
        UUID playerId = UUID.randomUUID();
        TransferSession session = new TransferSession("upload", 1, 4, 4, "hash");
        assertTrue(store.addUploadSession("upload", playerId, session));
        session.addChunk(0, new byte[] {1, 2, 3, 4});

        assertTrue(store.completeUploadSession("upload", playerId, session));

        assertEquals(0L, store.getReservedUploadBytes());
        assertEquals(4L, store.getStoredBytes());
        assertEquals(4L, store.getUsedMemoryBytes());
    }

    @Test
    public void cleanupReleasesExpiredCompletedSessions() {
        ProxyClipboardStore store = new ProxyClipboardStore(4L);
        UUID playerId = UUID.randomUUID();
        TransferSession session = new TransferSession("upload", 1, 4, 4, "hash");
        assertTrue(store.addUploadSession("upload", playerId, session));
        session.addChunk(0, new byte[] {1, 2, 3, 4});

        store.cleanupExpiredSessions(0L);

        assertFalse(store.hasActiveUpload(playerId));
        assertEquals(0L, store.getUsedMemoryBytes());
        assertThrows(IllegalStateException.class, session::assemble);
    }
}
