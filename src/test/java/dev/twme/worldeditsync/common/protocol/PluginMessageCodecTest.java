package dev.twme.worldeditsync.common.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.DataInputStream;
import java.util.Random;
import java.util.UUID;

import org.junit.Test;

public class PluginMessageCodecTest {

    @Test
    public void encryptsAndAuthenticatesEveryProtocolMessage() throws Exception {
        PluginMessageCodec paper = PluginMessageCodec.forPaper("shared-token");
        PluginMessageCodec proxy = PluginMessageCodec.forProxy("shared-token");
        String requestId = UUID.randomUUID().toString();
        byte[] raw = ProtocolCodec.encodeSyncRequest(requestId);
        byte[] wire = paper.encode(raw);

        ProtocolCodec.ParsedMessage parsed = proxy.decode(wire);
        assertNotNull(parsed);
        assertEquals(MessageType.SYNC_REQUEST, parsed.type());
        try (DataInputStream input = ProtocolCodec.payloadStream(parsed)) {
            assertEquals(requestId, input.readUTF());
        }

        assertNull(PluginMessageCodec.forProxy("wrong-token").decode(wire));
        assertNull(paper.decode(wire));
        assertNull(proxy.decode(raw));

        wire[wire.length - 1] ^= 1;
        assertNull(proxy.decode(wire));

        byte[] response = proxy.encode(ProtocolCodec.encodeSyncNoData(requestId));
        assertNotNull(paper.decode(response));
        assertNull(proxy.decode(response));
    }

    @Test
    public void rejectsMessagesAboveTheTransportLimit() {
        PluginMessageCodec codec = PluginMessageCodec.forPaper("shared-token");
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(new byte[dev.twme.worldeditsync.common.Constants.MAX_PLUGIN_MESSAGE_SIZE]));
    }

    @Test
    public void rejectsRandomMalformedEnvelopesWithoutThrowing() {
        PluginMessageCodec codec = PluginMessageCodec.forPaper("shared-token");
        Random random = new Random(0x574553L);

        for (int attempt = 0; attempt < 1_000; attempt++) {
            byte[] malformed = new byte[random.nextInt(4_096)];
            random.nextBytes(malformed);
            assertNull(codec.decode(malformed));
        }

        assertNull(codec.decode(new byte[dev.twme.worldeditsync.common.Constants.MAX_PLUGIN_MESSAGE_SIZE + 1]));
    }
}
