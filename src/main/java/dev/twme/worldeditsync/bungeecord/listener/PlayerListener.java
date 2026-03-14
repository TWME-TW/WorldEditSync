package dev.twme.worldeditsync.bungeecord.listener;

import java.util.concurrent.TimeUnit;

import dev.twme.worldeditsync.bungeecord.storage.ClipboardStore;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.model.ClipboardPayload;
import dev.twme.worldeditsync.common.protocol.ProtocolCodec;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

public class PlayerListener implements Listener {

    private final Plugin plugin;
    private final ClipboardStore store;

    public PlayerListener(Plugin plugin, ClipboardStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    @EventHandler
    public void onServerSwitch(ServerSwitchEvent event) {
        ProxiedPlayer player = event.getPlayer();

        // Delay slightly to ensure the player is fully connected to the new server
        plugin.getProxy().getScheduler().schedule(plugin, () -> {
            if (!player.isConnected()) return;

            ClipboardPayload payload = store.getClipboard(player.getUniqueId());

            byte[] msg;
            if (payload != null) {
                msg = ProtocolCodec.encodeSyncHash(payload.getHash());
            } else {
                msg = ProtocolCodec.encodeSyncNoData();
            }

            if (player.getServer() != null) {
                player.getServer().getInfo().sendData(Constants.CHANNEL, msg);
            }
        }, 1, TimeUnit.SECONDS);
    }
}
