package dev.twme.worldeditsync.common.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.DataInputStream;

import org.junit.Test;

public class ProtocolCodecTest {

    @Test
    public void roundTripsUploadReady() throws Exception {
        ProtocolCodec.ParsedMessage message =
                ProtocolCodec.decode(ProtocolCodec.encodeUploadReady("session-id"));

        assertNotNull(message);
        assertEquals(MessageType.UPLOAD_READY, message.type());
        try (DataInputStream input = ProtocolCodec.payloadStream(message)) {
            assertEquals("session-id", input.readUTF());
        }
    }
}
