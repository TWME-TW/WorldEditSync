package dev.twme.worldeditsync.velocity.handler;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.DataInputStream;
import java.util.Optional;
import java.util.UUID;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;

import dev.twme.worldeditsync.common.model.ClipboardPayload;
import dev.twme.worldeditsync.common.protocol.MessageType;
import dev.twme.worldeditsync.common.protocol.ProtocolCodec;
import dev.twme.worldeditsync.common.protocol.ProtocolCodec.ParsedMessage;
import dev.twme.worldeditsync.velocity.storage.ClipboardStore;

public class MessageHandlerTest {

    @Test
    public void acknowledgesBeginBeforeAcceptingChunks() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        ServerConnection connection = mock(ServerConnection.class);
        ChannelIdentifier channel = mock(ChannelIdentifier.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getUsername()).thenReturn("Tester");
        when(player.getCurrentServer()).thenReturn(Optional.of(connection));

        ClipboardStore store = new ClipboardStore();
        MessageHandler handler = new MessageHandler(
                new Object(),
                mock(ProxyServer.class),
                store,
                channel,
                3,
                1024,
                0,
                mock(Logger.class));

        String sessionId = "session-id";
        handler.handleMessage(player,
                ProtocolCodec.encodeUploadBegin(sessionId, 5, 2, "hash"));

        assertNotNull(store.getUploadSession(sessionId));
        ArgumentCaptor<byte[]> messages = ArgumentCaptor.forClass(byte[].class);
        verify(connection, atLeastOnce()).sendPluginMessage(eq(channel), messages.capture());

        ParsedMessage ready = ProtocolCodec.decode(messages.getValue());
        assertNotNull(ready);
        assertEquals(MessageType.UPLOAD_READY, ready.type());
        try (DataInputStream input = ProtocolCodec.payloadStream(ready)) {
            assertEquals(sessionId, input.readUTF());
        }

        handler.handleMessage(player,
                ProtocolCodec.encodeUploadChunk(sessionId, 1, new byte[] {4, 5}));
        handler.handleMessage(player,
                ProtocolCodec.encodeUploadChunk(sessionId, 0, new byte[] {1, 2, 3}));

        ClipboardPayload stored = store.getClipboard(playerId);
        assertNotNull(stored);
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, stored.getData());
        verify(connection, atLeastOnce()).sendPluginMessage(eq(channel), any(byte[].class));
    }
}
