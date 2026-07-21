package dev.twme.worldeditsync.bungeecord.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.DataInputStream;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import dev.twme.worldeditsync.bungeecord.storage.ClipboardStore;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.protocol.MessageType;
import dev.twme.worldeditsync.common.protocol.PluginMessageCodec;
import dev.twme.worldeditsync.common.protocol.ProtocolCodec;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.plugin.Plugin;

public class MessageHandlerTest {

    @Test
    public void repliesToTheRequestingPlayersBackendConnection() throws Exception {
        UUID playerId = UUID.randomUUID();
        String requestId = UUID.randomUUID().toString();
        String hash = "b".repeat(64);
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        ProxiedPlayer player = mock(ProxiedPlayer.class);
        Server backend = mock(Server.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Tester");
        when(player.isConnected()).thenReturn(true);
        when(player.getServer()).thenReturn(backend);

        ClipboardStore store = new ClipboardStore();
        store.storeClipboard(playerId, new byte[] {1, 2, 3}, hash);
        PluginMessageCodec paperCodec = PluginMessageCodec.forPaper("test-token");
        PluginMessageCodec proxyCodec = PluginMessageCodec.forProxy("test-token");
        MessageHandler handler = new MessageHandler(
                plugin, store, 30_000, 1024, 5, 30_000, proxyCodec);

        handler.handleMessage(player,
                paperCodec.encode(ProtocolCodec.encodeSyncRequest(requestId)));

        ArgumentCaptor<byte[]> response = ArgumentCaptor.forClass(byte[].class);
        verify(backend).sendData(eq(Constants.CHANNEL), response.capture());
        ProtocolCodec.ParsedMessage parsed = paperCodec.decode(response.getValue());
        assertNotNull(parsed);
        assertEquals(MessageType.SYNC_HASH, parsed.type());
        try (DataInputStream input = ProtocolCodec.payloadStream(parsed)) {
            assertEquals(requestId, input.readUTF());
            assertEquals(hash, input.readUTF());
        }
    }
}
