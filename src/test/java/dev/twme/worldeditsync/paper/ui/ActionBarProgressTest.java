package dev.twme.worldeditsync.paper.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.entity.Player;
import org.junit.Before;
import org.junit.Test;

import dev.twme.worldeditsync.paper.ui.ActionBarProgress.Operation;
import dev.twme.worldeditsync.paper.ui.ActionBarProgress.ProgressHandle;

public class ActionBarProgressTest {

    private final AtomicLong now = new AtomicLong();
    private final Queue<Runnable> scheduled = new ArrayDeque<>();
    private final List<String> messages = new ArrayList<>();
    private Player player;
    private ActionBarProgress progress;

    @Before
    public void setUp() {
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        progress = new ActionBarProgress(
                true,
                now::get,
                (ignored, task) -> scheduled.add(task),
                (ignored, message) -> messages.add(message));
    }

    @Test
    public void rateLimitsAndCoalescesIntermediateProgress() {
        ProgressHandle handle = progress.begin(player, Operation.UPLOAD);
        runNext();

        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("Uploading clipboard"));

        now.set(TimeUnit.MILLISECONDS.toNanos(999L));
        handle.update(0.50);
        assertTrue(scheduled.isEmpty());

        now.set(TimeUnit.SECONDS.toNanos(1L));
        handle.update(0.09);
        assertTrue(scheduled.isEmpty());

        handle.update(0.10);
        handle.update(0.90);
        assertEquals(1, scheduled.size());
        runNext();

        assertEquals(2, messages.size());
        assertTrue(messages.get(1).contains("10%"));

        now.set(TimeUnit.MILLISECONDS.toNanos(1_999L));
        handle.update(0.95);
        assertTrue(scheduled.isEmpty());

        now.set(TimeUnit.SECONDS.toNanos(2L));
        handle.update(0.95);
        assertEquals(1, scheduled.size());

        handle.complete();
        assertEquals(1, scheduled.size());
        runNext();

        assertEquals(3, messages.size());
        assertTrue(messages.get(2).contains("100%"));
        assertEquals(0, progress.activeDisplayCount());
    }

    @Test
    public void completionReplacesUnsentStartMessage() {
        ProgressHandle handle = progress.begin(player, Operation.DOWNLOAD);
        handle.complete();

        assertEquals(1, scheduled.size());
        runNext();

        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("100%"));
        assertTrue(messages.get(0).contains("Clipboard ready"));
    }

    @Test
    public void delayedStartFlushMovesTheNextProgressWindow() {
        ProgressHandle handle = progress.begin(player, Operation.UPLOAD);
        now.set(TimeUnit.SECONDS.toNanos(2L));
        runNext();

        handle.update(0.50);
        assertTrue(scheduled.isEmpty());

        now.set(TimeUnit.MILLISECONDS.toNanos(2_999L));
        handle.update(0.50);
        assertTrue(scheduled.isEmpty());

        now.set(TimeUnit.SECONDS.toNanos(3L));
        handle.update(0.50);
        assertEquals(1, scheduled.size());
    }

    @Test
    public void staleHandleCannotReplaceNewOperation() {
        ProgressHandle stale = progress.begin(player, Operation.UPLOAD);
        ProgressHandle current = progress.begin(player, Operation.DOWNLOAD);

        stale.complete();
        runNext();
        runNext();

        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("Downloading clipboard"));

        current.cancel();
        assertEquals(0, progress.activeDisplayCount());
    }

    @Test
    public void disabledDisplayDoesNotScheduleMessages() {
        ActionBarProgress disabled = new ActionBarProgress(
                false,
                now::get,
                (ignored, task) -> scheduled.add(task),
                (ignored, message) -> messages.add(message));

        ProgressHandle handle = disabled.begin(player, Operation.UPLOAD);
        handle.update(0.5);
        handle.complete();

        assertTrue(scheduled.isEmpty());
        assertTrue(messages.isEmpty());
        assertEquals(0, disabled.activeDisplayCount());
    }

    private void runNext() {
        Runnable task = scheduled.remove();
        task.run();
    }
}
