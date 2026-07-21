package dev.twme.worldeditsync.paper.ui;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import dev.twme.worldeditsync.paper.util.SchedulerUtil;

/** Displays low-frequency, coalesced clipboard progress in the player's Action Bar. */
public final class ActionBarProgress {

    static final long UPDATE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1L);
    static final int MINIMUM_PERCENT_STEP = 10;
    private static final int BAR_WIDTH = 12;

    private final boolean enabled;
    private final LongSupplier nanoTime;
    private final BiConsumer<Player, Runnable> entityExecutor;
    private final BiConsumer<Player, String> messageSender;
    private final ConcurrentHashMap<UUID, DisplayState> displays = new ConcurrentHashMap<>();

    public ActionBarProgress(JavaPlugin plugin, boolean enabled) {
        this(
                enabled,
                System::nanoTime,
                (player, task) -> {
                    if (SchedulerUtil.runOnEntityThread(plugin, player, task) == null) {
                        throw new IllegalStateException("Player scheduler rejected Action Bar update");
                    }
                },
                ActionBarProgress::sendSpigotActionBar);
    }

    ActionBarProgress(boolean enabled, LongSupplier nanoTime,
                      BiConsumer<Player, Runnable> entityExecutor,
                      BiConsumer<Player, String> messageSender) {
        this.enabled = enabled;
        this.nanoTime = nanoTime;
        this.entityExecutor = entityExecutor;
        this.messageSender = messageSender;
    }

    public ProgressHandle begin(Player player, Operation operation) {
        UUID playerId = player.getUniqueId();
        Object token = new Object();
        ProgressHandle handle = new ProgressHandle(player, playerId, token);
        if (!enabled || !player.isOnline()) {
            return handle;
        }

        DisplayState state = new DisplayState(token, operation, nanoTime.getAsLong());
        displays.put(playerId, state);
        queue(player, playerId, state, statusMessage(operation), false);
        return handle;
    }

    public void removePlayer(UUID playerId) {
        displays.remove(playerId);
    }

    public void shutdown() {
        displays.clear();
    }

    int activeDisplayCount() {
        return displays.size();
    }

    private void update(ProgressHandle handle, double progress) {
        DisplayState state = currentState(handle);
        if (state == null || !Double.isFinite(progress)) {
            return;
        }

        int percent = Math.max(0, Math.min(99, (int) Math.floor(progress * 100.0)));
        if (percent < MINIMUM_PERCENT_STEP) {
            return;
        }

        boolean schedule = false;
        long now = nanoTime.getAsLong();
        synchronized (state) {
            if (state.terminal || displays.get(handle.playerId) != state
                    || percent - state.lastPercent < MINIMUM_PERCENT_STEP) {
                return;
            }

            if (now < state.nextIntermediateAt || state.scheduled) {
                return;
            }

            state.lastPercent = percent;
            state.pendingMessage = progressMessage(state.operation, percent);
            state.nextIntermediateAt = now + UPDATE_INTERVAL_NANOS;
            state.scheduled = true;
            schedule = true;
        }
        if (schedule) {
            scheduleFlush(handle.player, handle.playerId, state);
        }
    }

    private void finish(ProgressHandle handle, boolean success) {
        DisplayState state = currentState(handle);
        if (state == null) {
            return;
        }

        String message = success
                ? completionMessage(state.operation)
                : failureMessage();
        queue(handle.player, handle.playerId, state, message, true);
    }

    private void cancel(ProgressHandle handle) {
        DisplayState state = currentState(handle);
        if (state != null) {
            displays.remove(handle.playerId, state);
        }
    }

    private DisplayState currentState(ProgressHandle handle) {
        if (!enabled) {
            return null;
        }
        DisplayState state = displays.get(handle.playerId);
        return state != null && state.token == handle.token ? state : null;
    }

    private void queue(Player player, UUID playerId, DisplayState state,
                       String message, boolean terminal) {
        boolean schedule = false;
        synchronized (state) {
            if (displays.get(playerId) != state || state.terminal) {
                return;
            }
            state.pendingMessage = message;
            state.terminal = terminal;
            if (!state.scheduled) {
                state.scheduled = true;
                schedule = true;
            }
        }
        if (schedule) {
            scheduleFlush(player, playerId, state);
        }
    }

    private void scheduleFlush(Player player, UUID playerId, DisplayState state) {
        try {
            entityExecutor.accept(player, () -> flush(player, playerId, state));
        } catch (RuntimeException e) {
            displays.remove(playerId, state);
        }
    }

    private void flush(Player player, UUID playerId, DisplayState state) {
        if (displays.get(playerId) != state) {
            return;
        }

        String message;
        boolean terminal;
        synchronized (state) {
            if (displays.get(playerId) != state) {
                return;
            }
            message = state.pendingMessage;
            terminal = state.terminal;
            state.pendingMessage = null;
            state.scheduled = false;
            state.nextIntermediateAt = Math.max(
                    state.nextIntermediateAt,
                    nanoTime.getAsLong() + UPDATE_INTERVAL_NANOS);
        }

        if (terminal) {
            displays.remove(playerId, state);
        }
        if (message == null || !player.isOnline()) {
            displays.remove(playerId, state);
            return;
        }

        try {
            messageSender.accept(player, message);
        } catch (RuntimeException e) {
            displays.remove(playerId, state);
        }
    }

    private static void sendSpigotActionBar(Player player, String message) {
        player.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                TextComponent.fromLegacyText(message));
    }

    private static String statusMessage(Operation operation) {
        return prefix() + ChatColor.GRAY + operation.status;
    }

    private static String progressMessage(Operation operation, int percent) {
        int filled = Math.min(BAR_WIDTH - 1, percent * BAR_WIDTH / 100);
        String bar = ChatColor.AQUA + "#".repeat(filled)
                + ChatColor.DARK_GRAY + "-".repeat(BAR_WIDTH - filled);
        return prefix() + ChatColor.GRAY + operation.verb + " "
                + ChatColor.DARK_GRAY + "[" + bar + ChatColor.DARK_GRAY + "] "
                + ChatColor.WHITE + percent + "%";
    }

    private static String completionMessage(Operation operation) {
        return prefix() + ChatColor.DARK_GRAY + "[" + ChatColor.GREEN
                + "#".repeat(BAR_WIDTH) + ChatColor.DARK_GRAY + "] "
                + ChatColor.WHITE + "100% " + ChatColor.GREEN + operation.completed;
    }

    private static String failureMessage() {
        return prefix() + ChatColor.RED + "Clipboard sync failed";
    }

    private static String prefix() {
        return ChatColor.AQUA + "WorldEditSync" + ChatColor.DARK_GRAY + " | ";
    }

    public enum Operation {
        UPLOAD("Uploading clipboard...", "Uploading", "Clipboard uploaded"),
        DOWNLOAD("Downloading clipboard...", "Downloading", "Clipboard ready");

        private final String status;
        private final String verb;
        private final String completed;

        Operation(String status, String verb, String completed) {
            this.status = status;
            this.verb = verb;
            this.completed = completed;
        }
    }

    public final class ProgressHandle {
        private final Player player;
        private final UUID playerId;
        private final Object token;

        private ProgressHandle(Player player, UUID playerId, Object token) {
            this.player = player;
            this.playerId = playerId;
            this.token = token;
        }

        public UUID playerId() {
            return playerId;
        }

        public void update(double progress) {
            ActionBarProgress.this.update(this, progress);
        }

        public void complete() {
            ActionBarProgress.this.finish(this, true);
        }

        public void fail() {
            ActionBarProgress.this.finish(this, false);
        }

        public void cancel() {
            ActionBarProgress.this.cancel(this);
        }
    }

    private static final class DisplayState {
        private final Object token;
        private final Operation operation;
        private long nextIntermediateAt;
        private int lastPercent;
        private String pendingMessage;
        private boolean scheduled;
        private boolean terminal;

        private DisplayState(Object token, Operation operation, long now) {
            this.token = token;
            this.operation = operation;
            this.nextIntermediateAt = now + UPDATE_INTERVAL_NANOS;
        }
    }
}
