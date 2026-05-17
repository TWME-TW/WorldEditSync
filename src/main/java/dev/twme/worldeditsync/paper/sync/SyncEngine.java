package dev.twme.worldeditsync.paper.sync;

import org.bukkit.entity.Player;

/**
 * Abstraction for clipboard sync engines.
 * Implementations: ProxySyncEngine (Plugin Message), S3SyncEngine (S3 storage).
 */
public interface SyncEngine {

    /**
     * Called when a player's clipboard has changed and needs to be uploaded.
     *
     * @param player     the player
     * @param data       serialized clipboard bytes (already encrypted if encryption is enabled)
     * @param hash       SHA-256 hash of the original (unencrypted) data
     */
    void uploadClipboard(Player player, byte[] data, String hash);

    /**
     * Called when a player joins a server and needs to check/download their clipboard.
     */
    void onPlayerJoinServer(Player player);

    /**
     * Called when a player leaves.
     */
    void onPlayerQuit(Player player);

    /**
     * Start the sync engine (register channels, start watchers, etc.)
     */
    void start();

    /**
     * Stop the sync engine and release resources.
     */
    void shutdown();
}
