package dev.twme.worldeditsync.paper.util;

import java.util.concurrent.TimeUnit;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Scheduler abstraction that supports Spigot, Paper, and Folia.
 *
 * <p>On Folia (region-threaded), {@code BukkitScheduler} throws
 * {@code UnsupportedOperationException}; the new scheduler API must be used.
 * On Spigot the new API does not exist; only {@code BukkitScheduler} is available.
 * This utility detects Folia at startup and routes each call accordingly.</p>
 */
public final class SchedulerUtil {

    /** {@code true} when the server is running Folia (region-threaded Paper fork). */
    public static final boolean FOLIA;

    static {
        boolean folia;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException e) {
            folia = false;
        }
        FOLIA = folia;
    }

    private SchedulerUtil() {}

    /**
     * Run a task asynchronously (off the main/region thread).
     * Equivalent to {@code runTaskAsynchronously} on Spigot/Paper.
     */
    public static Object runAsync(JavaPlugin plugin, Runnable task) {
        if (FOLIA) {
            return plugin.getServer().getAsyncScheduler().runNow(plugin, $ -> task.run());
        } else {
            return plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    public static Object runDelayedAsync(JavaPlugin plugin, Runnable task, long delayMs) {
        if (FOLIA) {
            return plugin.getServer().getAsyncScheduler().runDelayed(
                    plugin, $ -> task.run(), Math.max(1L, delayMs), TimeUnit.MILLISECONDS);
        } else {
            long delayTicks = Math.max(1L, (delayMs + 49L) / 50L);
            return plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks);
        }
    }

    /**
     * Run a task on the player's entity thread (Folia) or the main thread (Spigot/Paper).
     * On Folia, if the player is no longer valid when the task runs, it is silently skipped.
     */
    public static Object runOnEntityThread(JavaPlugin plugin, Player player, Runnable task) {
        if (FOLIA) {
            return player.getScheduler().run(plugin, $ -> task.run(), null);
        } else {
            return plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    public static Object runDelayedOnEntityThread(JavaPlugin plugin, Player player, Runnable task,
                                                   long delayTicks) {
        long safeDelay = Math.max(1L, delayTicks);
        if (FOLIA) {
            return player.getScheduler().runDelayed(plugin, $ -> task.run(), null, safeDelay);
        }
        return plugin.getServer().getScheduler().runTaskLater(plugin, task, safeDelay);
    }

    /** Run Bukkit-wide work on the global region thread (Folia) or main thread. */
    public static Object runOnGlobalThread(JavaPlugin plugin, Runnable task) {
        if (FOLIA) {
            return plugin.getServer().getGlobalRegionScheduler().run(plugin, $ -> task.run());
        }
        return plugin.getServer().getScheduler().runTask(plugin, task);
    }

    /**
     * Schedule a repeating async task.
     *
     * @return an opaque handle that can be passed to {@link #cancelTask(Object)}
     */
    public static Object runAtFixedRateAsync(JavaPlugin plugin, Runnable task,
                                             long initialDelayTicks, long periodTicks) {
        if (FOLIA) {
            return plugin.getServer().getAsyncScheduler().runAtFixedRate(
                    plugin,
                    $ -> task.run(),
                    Math.max(1L, initialDelayTicks * 50L),
                    Math.max(1L, periodTicks * 50L),
                    TimeUnit.MILLISECONDS);
        } else {
            return plugin.getServer().getScheduler()
                    .runTaskTimerAsynchronously(plugin, task, initialDelayTicks, periodTicks);
        }
    }

    /**
     * Cancel a task handle returned by {@link #runAtFixedRateAsync}.
     * Uses reflection so neither {@code BukkitTask} nor Folia's {@code ScheduledTask}
     * is referenced directly, keeping the class loadable on all platforms.
     */
    public static void cancelTask(Object handle) {
        if (handle == null) return;
        try {
            handle.getClass().getMethod("cancel").invoke(handle);
        } catch (ReflectiveOperationException ignored) {}
    }
}
