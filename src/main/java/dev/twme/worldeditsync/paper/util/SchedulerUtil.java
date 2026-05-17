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
    public static void runAsync(JavaPlugin plugin, Runnable task) {
        if (FOLIA) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, $ -> task.run());
        } else {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    /**
     * Run a task on the player's entity thread (Folia) or the main thread (Spigot/Paper).
     * On Folia, if the player is no longer valid when the task runs, it is silently skipped.
     */
    public static void runOnEntityThread(JavaPlugin plugin, Player player, Runnable task) {
        if (FOLIA) {
            player.getScheduler().run(plugin, $ -> task.run(), null);
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
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
