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
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import com.velocitypowered.api.scheduler.Scheduler.TaskBuilder;

import dev.twme.worldeditsync.common.model.ClipboardPayload;
import dev.twme.worldeditsync.common.protocol.PluginMessageCodec;
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
        ProxyServer proxy = immediateScheduler();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getUsername()).thenReturn("Tester");
        when(player.getCurrentServer()).thenReturn(Optional.of(connection));

        ClipboardStore store = new ClipboardStore();
        PluginMessageCodec paperCodec = PluginMessageCodec.forPaper("test-token");
        PluginMessageCodec proxyCodec = PluginMessageCodec.forProxy("test-token");
        MessageHandler handler = new MessageHandler(
                new Object(),
                proxy,
                store,
                channel,
                3,
                1024,
                0,
                30_000,
                proxyCodec,
                mock(Logger.class));

        String sessionId = UUID.randomUUID().toString();
        String hash = "a".repeat(64);
        handler.handleMessage(player,
                paperCodec.encode(ProtocolCodec.encodeUploadBegin(sessionId, 5, 2, hash)));

        assertNotNull(store.getUploadSession(sessionId));
        ArgumentCaptor<byte[]> messages = ArgumentCaptor.forClass(byte[].class);
        verify(connection, atLeastOnce()).sendPluginMessage(eq(channel), messages.capture());

        ParsedMessage ready = paperCodec.decode(messages.getValue());
        assertNotNull(ready);
        assertEquals(MessageType.UPLOAD_READY, ready.type());
        try (DataInputStream input = ProtocolCodec.payloadStream(ready)) {
            assertEquals(sessionId, input.readUTF());
        }

        handler.handleMessage(player,
                paperCodec.encode(ProtocolCodec.encodeUploadChunk(sessionId, 1, new byte[] {4, 5})));
        handler.handleMessage(player,
                paperCodec.encode(ProtocolCodec.encodeUploadChunk(sessionId, 0, new byte[] {1, 2, 3})));

        ClipboardPayload stored = store.getClipboard(playerId);
        assertNotNull(stored);
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, stored.getData());
        verify(connection, atLeastOnce()).sendPluginMessage(eq(channel), any(byte[].class));
    }

    private ProxyServer immediateScheduler() {
        ProxyServer proxy = mock(ProxyServer.class);
        Scheduler scheduler = mock(Scheduler.class);
        when(proxy.getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(any(), any(Runnable.class))).thenAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(1);
            TaskBuilder builder = mock(TaskBuilder.class);
            when(builder.delay(org.mockito.ArgumentMatchers.anyLong(), any(TimeUnit.class)))
                    .thenReturn(builder);
            when(builder.schedule()).thenAnswer(ignored -> {
                runnable.run();
                return mock(ScheduledTask.class);
            });
            return builder;
        });
        return proxy;
    }
}
