package dev.twme.worldeditsync.common.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.DataInputStream;
import java.util.UUID;

import org.junit.Test;

public class PluginMessageCodecTest {

    @Test
    public void encryptsAndAuthenticatesEveryProtocolMessage() throws Exception {
        PluginMessageCodec codec = new PluginMessageCodec("shared-token");
        String requestId = UUID.randomUUID().toString();
        byte[] raw = ProtocolCodec.encodeSyncRequest(requestId);
        byte[] wire = codec.encode(raw);

        ProtocolCodec.ParsedMessage parsed = codec.decode(wire);
        assertNotNull(parsed);
        assertEquals(MessageType.SYNC_REQUEST, parsed.type());
        try (DataInputStream input = ProtocolCodec.payloadStream(parsed)) {
            assertEquals(requestId, input.readUTF());
        }

        assertNull(new PluginMessageCodec("wrong-token").decode(wire));
        assertNull(codec.decode(raw));

        wire[wire.length - 1] ^= 1;
        assertNull(codec.decode(wire));
    }

    @Test
    public void rejectsMessagesAboveTheTransportLimit() {
        PluginMessageCodec codec = new PluginMessageCodec("shared-token");
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(new byte[dev.twme.worldeditsync.common.Constants.MAX_PLUGIN_MESSAGE_SIZE]));
    }
}
