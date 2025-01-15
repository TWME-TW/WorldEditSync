package dev.twme.worldeditsync.paper.listener;

import dev.twme.worldeditsync.paper.WorldEditSyncPaper;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerListener implements Listener {

    private final WorldEditSyncPaper plugin;

    public PlayerListener(WorldEditSyncPaper plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        plugin.getClipboardManager().setPlayerTransferring(player.getUniqueId(), false);
        plugin.getClipboardManager().uncheck(player.getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        plugin.getClipboardManager().setPlayerTransferring(player.getUniqueId(), false);
        plugin.getClipboardManager().uncheck(player.getUniqueId());
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();
        String[] args = message.split(" ");

        if (args[0].equalsIgnoreCase("//copy") || args[0].equalsIgnoreCase("//cut")) {
            plugin.getClipboardManager().sendStopMessage(player);
            plugin.getClipboardManager().setPlayerTransferring(player.getUniqueId(), false);
            plugin.getClipboardManager().check(player.getUniqueId());
        }
    }
}
