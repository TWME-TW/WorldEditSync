package dev.twme.worldeditsync.common.util;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

public class DataUtil {
    public static byte[] createMessage(String subChannel, String... contents) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(subChannel);
        for (String content : contents) {
            out.writeUTF(content);
        }
        return out.toByteArray();
    }

    public static byte[] createChunkMessage(String sessionId, int chunkIndex, byte[] data) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("ClipboardChunk");
        out.writeUTF(sessionId);
        out.writeInt(chunkIndex);
        out.writeInt(data.length);
        out.write(data);
        return out.toByteArray();
    }

    public static String readString(ByteArrayDataInput in) {
        try {
            return in.readUTF();
        } catch (Exception e) {
            return "";
        }
    }

    public static byte[] readBytes(ByteArrayDataInput in, int length) {
        if (length <= 0) {
            return new byte[0];
        }
        byte[] data = new byte[length];
        try {
            in.readFully(data);
            return data;
        } catch (Exception e) {
            return new byte[0];
        }
    }
}