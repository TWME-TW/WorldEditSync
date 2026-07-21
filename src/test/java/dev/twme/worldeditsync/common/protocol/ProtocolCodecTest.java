package dev.twme.worldeditsync.common.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.DataInputStream;
import java.util.UUID;

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

    @Test
    public void correlatesDownloadBeginWithItsRequest() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        ProtocolCodec.ParsedMessage message = ProtocolCodec.decode(ProtocolCodec.encodeDownloadBegin(
                requestId, sessionId, 5, 1, "a".repeat(64)));

        assertNotNull(message);
        assertEquals(MessageType.DOWNLOAD_BEGIN, message.type());
        try (DataInputStream input = ProtocolCodec.payloadStream(message)) {
            assertEquals(requestId, input.readUTF());
            assertEquals(sessionId, input.readUTF());
            assertEquals(5, input.readInt());
            assertEquals(1, input.readInt());
            assertEquals("a".repeat(64), input.readUTF());
        }
    }
}
