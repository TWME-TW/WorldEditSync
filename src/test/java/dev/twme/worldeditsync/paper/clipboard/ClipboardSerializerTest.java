package dev.twme.worldeditsync.paper.clipboard;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.enginehub.linbus.stream.LinBinaryIO;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.enginehub.linbus.tree.LinRootEntry;
import org.enginehub.linbus.tree.LinTagType;
import org.junit.Test;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;

public class ClipboardSerializerTest {

    @Test
    public void closesWriterBeforeReadingSerializedBytes() throws Exception {
        ClipboardSerializer serializer = new ClipboardSerializer() {
            @Override
            protected ClipboardWriter createWriter(OutputStream output) {
                return new ClipboardWriter() {
                    @Override
                    public void write(Clipboard clipboard) throws IOException {
                        output.write("body".getBytes(StandardCharsets.US_ASCII));
                    }

                    @Override
                    public void close() throws IOException {
                        output.write("-footer".getBytes(StandardCharsets.US_ASCII));
                    }
                };
            }

            @Override
            protected byte[] canonicalize(byte[] data) {
                return data;
            }
        };

        assertArrayEquals(
                "body-footer".getBytes(StandardCharsets.US_ASCII),
                serializer.serialize(null));
    }

    @Test
    public void removesVolatileDateFromSchematicMetadata() throws Exception {
        ClipboardSerializer serializer = new ClipboardSerializer();

        byte[] first = serializer.canonicalize(schematicWithDate(1000L));
        byte[] second = serializer.canonicalize(schematicWithDate(2000L));

        assertArrayEquals(first, second);

        LinCompoundTag schematic = readRoot(first)
                .value()
                .findTag("Schematic", LinTagType.compoundTag());
        assertNotNull(schematic);
        LinCompoundTag metadata = schematic.findTag("Metadata", LinTagType.compoundTag());
        assertNotNull(metadata);
        assertFalse(metadata.value().containsKey("Date"));
    }

    private static byte[] schematicWithDate(long date) throws IOException {
        LinCompoundTag metadata = LinCompoundTag.builder()
                .putLong("Date", date)
                .putString("Stable", "value")
                .build();
        LinCompoundTag schematic = LinCompoundTag.builder()
                .putInt("Version", 3)
                .put("Metadata", metadata)
                .build();
        LinRootEntry root = new LinRootEntry(
                "",
                LinCompoundTag.builder().put("Schematic", schematic).build());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output);
             DataOutputStream dataOutput = new DataOutputStream(gzip)) {
            LinBinaryIO.write(dataOutput, root);
        }
        return output.toByteArray();
    }

    private static LinRootEntry readRoot(byte[] data) throws IOException {
        try (DataInputStream input = new DataInputStream(
                new GZIPInputStream(new ByteArrayInputStream(data)))) {
            return LinRootEntry.readFrom(LinBinaryIO.read(input));
        }
    }
}
