package dev.twme.worldeditsync.paper.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import dev.twme.worldeditsync.paper.sync.SyncEngine;

public class PlayerListener implements Listener {

    private final SyncEngine syncEngine;

    public PlayerListener(SyncEngine syncEngine) {
        this.syncEngine = syncEngine;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        syncEngine.onPlayerJoinServer(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        syncEngine.onPlayerQuit(event.getPlayer());
    }
}
