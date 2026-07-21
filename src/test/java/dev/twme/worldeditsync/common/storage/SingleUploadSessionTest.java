package dev.twme.worldeditsync.common.storage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertArrayEquals;

import java.util.UUID;

import org.junit.Test;

import dev.twme.worldeditsync.common.protocol.TransferSession;

public class SingleUploadSessionTest {

    @Test
    public void bungeeStoreKeepsOnlyOneUploadPerPlayer() {
        var store = new dev.twme.worldeditsync.bungeecord.storage.ClipboardStore();
        assertSingleSession(store);
    }

    @Test
    public void velocityStoreKeepsOnlyOneUploadPerPlayer() {
        var store = new dev.twme.worldeditsync.velocity.storage.ClipboardStore();
        UUID playerId = UUID.randomUUID();
        TransferSession first = session("first");
        TransferSession second = session("second");

        assertTrue(store.addUploadSession("first", playerId, first));
        assertTrue(store.addUploadSession("second", playerId, second));
        assertNull(store.getUploadSession("first"));
        assertSame(second, store.getUploadSession("second"));
        assertTrue(store.hasActiveUpload(playerId));
        assertFalse(store.addUploadSession("second", playerId, session("duplicate")));
    }

    @Test
    public void bungeeStoreRejectsStaleCompletion() {
        var store = new dev.twme.worldeditsync.bungeecord.storage.ClipboardStore();
        assertStaleCompletionIsRejected(store);
    }

    @Test
    public void velocityStoreRejectsStaleCompletion() {
        var store = new dev.twme.worldeditsync.velocity.storage.ClipboardStore();
        UUID playerId = UUID.randomUUID();
        TransferSession first = session("first");
        TransferSession second = session("second");

        assertTrue(store.addUploadSession("first", playerId, first));
        assertTrue(store.addUploadSession("second", playerId, second));
        assertFalse(store.completeUploadSession("first", playerId, first,
                new byte[] {1}, "first-hash"));
        assertNull(store.getClipboard(playerId));
        assertTrue(store.completeUploadSession("second", playerId, second,
                new byte[] {2}, "second-hash"));
        assertArrayEquals(new byte[] {2}, store.getClipboard(playerId).getData());
        assertFalse(store.hasActiveUpload(playerId));
    }

    @Test
    public void bungeeDisconnectCleanupPreservesCompleteUpload() {
        var store = new dev.twme.worldeditsync.bungeecord.storage.ClipboardStore();
        UUID playerId = UUID.randomUUID();
        TransferSession incomplete = session("incomplete");
        assertTrue(store.addUploadSession("incomplete", playerId, incomplete));
        store.removeIncompleteUploadSessionForOwner(playerId);
        assertFalse(store.hasActiveUpload(playerId));

        TransferSession complete = session("complete");
        complete.addChunk(0, new byte[] {1});
        assertTrue(store.addUploadSession("complete", playerId, complete));
        store.removeIncompleteUploadSessionForOwner(playerId);
        assertSame(complete, store.getUploadSession("complete"));
        assertTrue(store.completeUploadSession(
                "complete", playerId, complete, new byte[] {1}, "hash"));
    }

    @Test
    public void velocityDisconnectCleanupPreservesCompleteUpload() {
        var store = new dev.twme.worldeditsync.velocity.storage.ClipboardStore();
        UUID playerId = UUID.randomUUID();
        TransferSession incomplete = session("incomplete");
        assertTrue(store.addUploadSession("incomplete", playerId, incomplete));
        store.removeIncompleteUploadSessionForOwner(playerId);
        assertFalse(store.hasActiveUpload(playerId));

        TransferSession complete = session("complete");
        complete.addChunk(0, new byte[] {1});
        assertTrue(store.addUploadSession("complete", playerId, complete));
        store.removeIncompleteUploadSessionForOwner(playerId);
        assertSame(complete, store.getUploadSession("complete"));
        assertTrue(store.completeUploadSession(
                "complete", playerId, complete, new byte[] {1}, "hash"));
    }

    @Test
    public void bungeeOwnerCannotRemoveAnotherPlayersUpload() {
        var store = new dev.twme.worldeditsync.bungeecord.storage.ClipboardStore();
        UUID owner = UUID.randomUUID();
        TransferSession session = session("owned");
        assertTrue(store.addUploadSession("owned", owner, session));
        assertFalse(store.removeUploadSession("owned", UUID.randomUUID()));
        assertSame(session, store.getUploadSession("owned"));
    }

    @Test
    public void velocityOwnerCannotRemoveAnotherPlayersUpload() {
        var store = new dev.twme.worldeditsync.velocity.storage.ClipboardStore();
        UUID owner = UUID.randomUUID();
        TransferSession session = session("owned");
        assertTrue(store.addUploadSession("owned", owner, session));
        assertFalse(store.removeUploadSession("owned", UUID.randomUUID()));
        assertSame(session, store.getUploadSession("owned"));
    }

    private void assertSingleSession(dev.twme.worldeditsync.bungeecord.storage.ClipboardStore store) {
        UUID playerId = UUID.randomUUID();
        TransferSession first = session("first");
        TransferSession second = session("second");

        assertTrue(store.addUploadSession("first", playerId, first));
        assertTrue(store.addUploadSession("second", playerId, second));
        assertNull(store.getUploadSession("first"));
        assertSame(second, store.getUploadSession("second"));
        assertTrue(store.hasActiveUpload(playerId));
        assertFalse(store.addUploadSession("second", playerId, session("duplicate")));
    }

    private void assertStaleCompletionIsRejected(
            dev.twme.worldeditsync.bungeecord.storage.ClipboardStore store) {
        UUID playerId = UUID.randomUUID();
        TransferSession first = session("first");
        TransferSession second = session("second");

        assertTrue(store.addUploadSession("first", playerId, first));
        assertTrue(store.addUploadSession("second", playerId, second));
        assertFalse(store.completeUploadSession("first", playerId, first,
                new byte[] {1}, "first-hash"));
        assertNull(store.getClipboard(playerId));
        assertTrue(store.completeUploadSession("second", playerId, second,
                new byte[] {2}, "second-hash"));
        assertArrayEquals(new byte[] {2}, store.getClipboard(playerId).getData());
        assertFalse(store.hasActiveUpload(playerId));
    }

    private TransferSession session(String id) {
        return new TransferSession(id, 1, 1, "hash");
    }
}
