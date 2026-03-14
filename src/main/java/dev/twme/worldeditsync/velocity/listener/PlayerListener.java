package dev.twme.worldeditsync.velocity.listener;

import java.time.Duration;
import java.util.logging.Logger;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;

import dev.twme.worldeditsync.common.model.ClipboardPayload;
import dev.twme.worldeditsync.common.protocol.ProtocolCodec;
import dev.twme.worldeditsync.velocity.storage.ClipboardStore;

public class PlayerListener {

    private final ProxyServer server;
    private final ClipboardStore store;
    private final ChannelIdentifier channelId;
    private final Logger logger;

    public PlayerListener(ProxyServer server, ClipboardStore store,
                          ChannelIdentifier channelId, Logger logger) {
        this.server = server;
        this.store = store;
        this.channelId = channelId;
        this.logger = logger;
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();

        // Delay to ensure the player is fully connected to the new server
        server.getScheduler().buildTask(server, () -> {
            if (!player.isActive()) return;

            ClipboardPayload payload = store.getClipboard(player.getUniqueId());

            byte[] msg;
            if (payload != null) {
                msg = ProtocolCodec.encodeSyncHash(payload.getHash());
            } else {
                msg = ProtocolCodec.encodeSyncNoData();
            }

            player.getCurrentServer().ifPresent(serverConnection ->
                    serverConnection.sendPluginMessage(channelId, msg));
        }).delay(Duration.ofSeconds(1)).schedule();
    }
}
