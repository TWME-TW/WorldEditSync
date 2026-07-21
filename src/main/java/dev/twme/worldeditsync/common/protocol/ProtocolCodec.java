package dev.twme.worldeditsync.common.protocol;

import dev.twme.worldeditsync.common.Constants;

import java.io.*;

/**
 * Codec for encoding/decoding all protocol messages.
 *
 * Wire format: [1 byte: protocol version] [1 byte: MessageType] [N bytes: payload]
 */
public final class ProtocolCodec {

    private ProtocolCodec() {
    }

    // ── Encoding ────────────────────────────────────────────────

    public static byte[] encodeUploadBegin(String sessionId, int totalBytes, int totalChunks, String hash) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bos)) {
            writeHeader(out, MessageType.UPLOAD_BEGIN);
            out.writeUTF(sessionId);
            out.writeInt(totalBytes);
            out.writeInt(totalChunks);
            out.writeUTF(hash);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode UPLOAD_BEGIN", e);
        }
    }

    public static byte[] encodeUploadChunk(String sessionId, int chunkIndex, byte[] data) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bos)) {
            writeHeader(out, MessageType.UPLOAD_CHUNK);
            out.writeUTF(sessionId);
            out.writeInt(chunkIndex);
            out.writeInt(data.length);
            out.write(data);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode UPLOAD_CHUNK", e);
        }
    }

    public static byte[] encodeUploadAck(String sessionId) {
        return encodeSessionMessage(MessageType.UPLOAD_ACK, sessionId);
    }

    public static byte[] encodeUploadReady(String sessionId) {
        return encodeSessionMessage(MessageType.UPLOAD_READY, sessionId);
    }

    private static byte[] encodeSessionMessage(MessageType type, String sessionId) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bos)) {
            writeHeader(out, type);
            out.writeUTF(sessionId);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode " + type, e);
        }
    }

    public static byte[] encodeSyncRequest(String requestId) {
        return encodeSessionMessage(MessageType.SYNC_REQUEST, requestId);
    }

    public static byte[] encodeSyncHash(String requestId, String hash) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bos)) {
            writeHeader(out, MessageType.SYNC_HASH);
            out.writeUTF(requestId);
            out.writeUTF(hash);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode SYNC_HASH", e);
        }
    }

    public static byte[] encodeSyncNoData(String requestId) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bos)) {
            writeHeader(out, MessageType.SYNC_NO_DATA);
            out.writeUTF(requestId);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode SYNC_NO_DATA", e);
        }
    }

    public static byte[] encodeDownloadRequest(String requestId) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bos)) {
            writeHeader(out, MessageType.DOWNLOAD_REQUEST);
            out.writeUTF(requestId);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode DOWNLOAD_REQUEST", e);
        }
    }

    public static byte[] encodeDownloadBegin(String requestId, String sessionId,
                                             int totalBytes, int totalChunks, String hash) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bos)) {
            writeHeader(out, MessageType.DOWNLOAD_BEGIN);
            out.writeUTF(requestId);
            out.writeUTF(sessionId);
            out.writeInt(totalBytes);
            out.writeInt(totalChunks);
            out.writeUTF(hash);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode DOWNLOAD_BEGIN", e);
        }
    }

    public static byte[] encodeDownloadChunk(String sessionId, int chunkIndex, byte[] data) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bos)) {
            writeHeader(out, MessageType.DOWNLOAD_CHUNK);
            out.writeUTF(sessionId);
            out.writeInt(chunkIndex);
            out.writeInt(data.length);
            out.write(data);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode DOWNLOAD_CHUNK", e);
        }
    }

    public static byte[] encodeDownloadAck(String sessionId) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bos)) {
            writeHeader(out, MessageType.DOWNLOAD_ACK);
            out.writeUTF(sessionId);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode DOWNLOAD_ACK", e);
        }
    }

    public static byte[] encodeCancel(String sessionId, String reason) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bos)) {
            writeHeader(out, MessageType.CANCEL);
            out.writeUTF(sessionId);
            String safeReason = reason == null ? "unspecified" : reason;
            if (safeReason.length() > Constants.MAX_CANCEL_REASON_LENGTH) {
                safeReason = safeReason.substring(0, Constants.MAX_CANCEL_REASON_LENGTH);
            }
            out.writeUTF(safeReason);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode CANCEL", e);
        }
    }

    // ── Decoding ────────────────────────────────────────────────

    /**
     * Decode a raw message into a ParsedMessage.
     * Returns null if the message is invalid or uses an incompatible protocol version.
     */
    public static ParsedMessage decode(byte[] raw) {
        if (raw == null || raw.length < 2 || raw.length > Constants.MAX_PLUGIN_MESSAGE_SIZE) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            byte version = in.readByte();
            if (version != Constants.PROTOCOL_VERSION) {
                return null;
            }
            byte typeId = in.readByte();
            MessageType type = MessageType.fromId(typeId);
            if (type == null) {
                return null;
            }

            // Read remaining bytes as payload
            byte[] payload = in.readAllBytes();
            return new ParsedMessage(type, payload);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Helper to read fields from a ParsedMessage payload.
     */
    public static DataInputStream payloadStream(ParsedMessage msg) {
        return new DataInputStream(new ByteArrayInputStream(msg.payload()));
    }

    // ── Internal ────────────────────────────────────────────────

    private static void writeHeader(DataOutputStream out, MessageType type) throws IOException {
        out.writeByte(Constants.PROTOCOL_VERSION);
        out.writeByte(type.getId());
    }

    // ── Parsed message record ───────────────────────────────────

    public record ParsedMessage(MessageType type, byte[] payload) {
    }
}
