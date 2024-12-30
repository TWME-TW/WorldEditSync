package dev.twme.worldeditsync.paper.listener;

import dev.twme.worldeditsync.paper.WorldEditSyncPaper;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerListener implements Listener {

    private final WorldEditSyncPaper plugin;

    public PlayerListener(WorldEditSyncPaper plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        plugin.getClipboardManager().uncheck(player.getUniqueId());
    }
}
