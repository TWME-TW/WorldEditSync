package dev.twme.worldeditsync.paper.clipboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.model.SyncState;
import dev.twme.worldeditsync.common.protocol.TransferMemoryBudget;
import dev.twme.worldeditsync.common.protocol.TransferSession;

public class ClipboardManagerTest {

    @Test
    public void skipsOnlyTheSameRecentlyConfirmedClipboard() {
        AtomicLong clock = new AtomicLong(10_000L);
        ClipboardManager manager = new ClipboardManager(clock::get);
        UUID playerId = UUID.randomUUID();
        Object clipboard = new Object();
        String hash = "a".repeat(64);
        manager.initPlayer(playerId, SyncState.IDLE);
        manager.setLocalHash(playerId, hash);
        manager.markSerializedClipboard(playerId, clipboard, hash);

        assertTrue(manager.isSerializedClipboard(playerId, clipboard));
        assertFalse(manager.isSerializedClipboard(playerId, new Object()));

        clock.addAndGet(Constants.UNCHANGED_CLIPBOARD_RECHECK_MS);
        assertFalse(manager.isSerializedClipboard(playerId, clipboard));
    }

    @Test
    public void changedOrUnconfirmedHashForcesSerialization() {
        ClipboardManager manager = new ClipboardManager();
        UUID playerId = UUID.randomUUID();
        Object clipboard = new Object();
        manager.initPlayer(playerId, SyncState.IDLE);
        manager.markSerializedClipboard(playerId, clipboard, "a".repeat(64));

        assertFalse(manager.isSerializedClipboard(playerId, clipboard));
        manager.setLocalHash(playerId, "b".repeat(64));
        assertFalse(manager.isSerializedClipboard(playerId, clipboard));
    }

    @Test
    public void downloadedClipboardKeepsRemoteAndLocalHashesSeparate() {
        AtomicLong clock = new AtomicLong(10_000L);
        ClipboardManager manager = new ClipboardManager(clock::get);
        UUID playerId = UUID.randomUUID();
        Object downgradedClipboard = new Object();
        String remoteHash = "a".repeat(64);
        String downgradedLocalHash = "b".repeat(64);
        manager.initPlayer(playerId, SyncState.IDLE);

        manager.markDownloadedClipboard(
                playerId, downgradedClipboard, remoteHash, downgradedLocalHash);

        assertEquals(remoteHash, manager.getRemoteHash(playerId));
        assertEquals(downgradedLocalHash, manager.getLocalHash(playerId));
        assertTrue(manager.isSerializedClipboard(playerId, downgradedClipboard));

        clock.addAndGet(Constants.UNCHANGED_CLIPBOARD_RECHECK_MS);
        assertFalse(manager.isSerializedClipboard(playerId, downgradedClipboard));
        assertEquals(downgradedLocalHash, manager.getLocalHash(playerId));
        assertEquals(remoteHash, manager.getRemoteHash(playerId));
    }

    @Test
    public void uploadAndCleanupUpdateBothHashBaselines() {
        ClipboardManager manager = new ClipboardManager();
        UUID playerId = UUID.randomUUID();
        String hash = "c".repeat(64);
        manager.initPlayer(playerId, SyncState.IDLE);

        manager.markUploadedClipboard(playerId, hash);

        assertEquals(hash, manager.getLocalHash(playerId));
        assertEquals(hash, manager.getRemoteHash(playerId));

        manager.forgetClipboard(playerId);

        assertNull(manager.getLocalHash(playerId));
        assertNull(manager.getRemoteHash(playerId));
    }

    @Test
    public void shutdownOwnsAndReleasesDetachedDownloadExactlyOnce() {
        ClipboardManager manager = new ClipboardManager();
        TransferMemoryBudget budget = new TransferMemoryBudget(4L);
        TransferSession session = new TransferSession("download", 1, 4, 4, "hash");
        manager.setTransferMemoryBudget(budget);

        assertTrue(manager.addDownloadSession("download", session));
        session.addChunk(0, new byte[] {1, 2, 3, 4});
        assertTrue(manager.detachDownloadSession("download", session));
        assertEquals(4L, budget.getReservedBytes());

        manager.shutdown();
        manager.releaseDetachedDownloadSession(session);

        assertEquals(0L, budget.getReservedBytes());
        assertThrows(IllegalStateException.class, session::assemble);
    }

    @Test
    public void rejectedDuplicateSessionDoesNotConsumeTransferBudget() {
        ClipboardManager manager = new ClipboardManager();
        TransferMemoryBudget budget = new TransferMemoryBudget(8L);
        manager.setTransferMemoryBudget(budget);

        assertTrue(manager.addDownloadSession(
                "same", new TransferSession("same", 1, 4, 4, "hash")));
        assertFalse(manager.addDownloadSession(
                "same", new TransferSession("same", 1, 4, 4, "hash")));

        assertEquals(4L, budget.getReservedBytes());
        manager.shutdown();
        assertEquals(0L, budget.getReservedBytes());
    }
}
