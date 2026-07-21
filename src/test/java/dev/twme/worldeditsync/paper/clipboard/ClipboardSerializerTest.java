package dev.twme.worldeditsync.paper.clipboard;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.enginehub.linbus.stream.LinBinaryIO;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.enginehub.linbus.tree.LinIntTag;
import org.enginehub.linbus.tree.LinListTag;
import org.enginehub.linbus.tree.LinRootEntry;
import org.enginehub.linbus.tree.LinTagType;
import org.junit.Test;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extension.platform.Capability;
import com.sk89q.worldedit.extension.platform.Platform;
import com.sk89q.worldedit.extension.platform.Preference;
import com.sk89q.worldedit.event.platform.PlatformsRegisteredEvent;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.registry.BundledRegistries;

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

    @Test
    public void rejectsWriterOutputBeforeItCanGrowPastTheConfiguredLimit() {
        ClipboardSerializer serializer = new ClipboardSerializer() {
            @Override
            protected ClipboardWriter createWriter(OutputStream output) {
                return new ClipboardWriter() {
                    @Override
                    public void write(Clipboard clipboard) throws IOException {
                        output.write(new byte[6]);
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };

        assertThrows(IOException.class, () -> serializer.serialize(null, 5, 1));
    }

    @Test
    public void rejectsExpandedDimensionsBeforeWorldEditAllocatesClipboard() throws Exception {
        ClipboardSerializer serializer = new ClipboardSerializer();
        byte[] oversized = schematicWithDimensions(256, 256, 256, 1000L);

        assertThrows(IOException.class,
                () -> serializer.canonicalize(oversized, 1024 * 1024, 1000L));
    }

    @Test
    public void streamingValidationPreservesNestedListsArraysAndScalars() throws Exception {
        ClipboardSerializer serializer = new ClipboardSerializer();
        LinCompoundTag schematic = LinCompoundTag.builder()
                .putInt("Version", 3)
                .putShort("Width", (short) 1)
                .putShort("Height", (short) 1)
                .putShort("Length", (short) 1)
                .putByte("Byte", (byte) 7)
                .putLong("Long", 8L)
                .putFloat("Float", 1.5F)
                .putDouble("Double", 2.5D)
                .putString("String", "stable")
                .putByteArray("Bytes", new byte[] {1, 2, 3})
                .putIntArray("Ints", new int[] {4, 5, 6})
                .putLongArray("Longs", new long[] {7L, 8L, 9L})
                .put("List", LinListTag.of(
                        LinTagType.intTag(), List.of(LinIntTag.of(10), LinIntTag.of(11))))
                .put("Nested", LinCompoundTag.builder().putString("Name", "value").build())
                .build();

        LinCompoundTag result = readRoot(serializer.canonicalize(
                writeRoot(schematic), 1024 * 1024, 1L))
                .value()
                .findTag("Schematic", LinTagType.compoundTag());

        assertEquals(schematic, result);
    }

    @Test
    public void validatesOutputFromTheRealWorldEditWriter() throws Exception {
        WorldEdit worldEdit = WorldEdit.getInstance();
        Platform platform = mock(Platform.class);
        when(platform.getCapabilities()).thenReturn(
                Map.of(Capability.WORLD_EDITING, Preference.PREFERRED));
        when(platform.getRegistries()).thenReturn(BundledRegistries.getInstance());
        when(platform.getDataVersion()).thenReturn(4_189);
        when(platform.getPlatformName()).thenReturn("test");
        when(platform.getPlatformVersion()).thenReturn("1");
        when(platform.getVersion()).thenReturn("1");
        when(platform.id()).thenReturn("test");
        worldEdit.getPlatformManager().register(platform);
        try {
            worldEdit.getPlatformManager().handlePlatformsRegistered(
                    new PlatformsRegisteredEvent());
            ClipboardSerializer serializer = new ClipboardSerializer();
            Clipboard original = mock(Clipboard.class);
            CuboidRegion region = new CuboidRegion(
                    BlockVector3.ZERO, BlockVector3.at(1, 1, 1));
            BaseBlock block = mock(BaseBlock.class);
            BlockState blockState = mock(BlockState.class);
            when(original.getRegion()).thenReturn(region);
            when(original.getOrigin()).thenReturn(BlockVector3.at(1, 0, 1));
            when(original.getMinimumPoint()).thenReturn(BlockVector3.ZERO);
            when(original.getEntities()).thenReturn(List.of());
            when(original.hasBiomes()).thenReturn(false);
            when(original.getFullBlock(any(BlockVector3.class))).thenReturn(block);
            when(block.toImmutableState()).thenReturn(blockState);
            when(blockState.getAsString()).thenReturn("minecraft:stone");

            byte[] data = serializer.serialize(original, 1024 * 1024, 8L);
            LinCompoundTag schematic = readRoot(data).value()
                    .findTag("Schematic", LinTagType.compoundTag());
            LinCompoundTag metadata = schematic.findTag(
                    "Metadata", LinTagType.compoundTag());

            assertNotNull(schematic.findTag("Blocks", LinTagType.compoundTag()));
            assertNotNull(metadata);
            assertFalse(metadata.value().containsKey("Date"));
        } finally {
            worldEdit.getPlatformManager().unregister(platform);
        }
    }

    private static byte[] schematicWithDate(long date) throws IOException {
        return schematicWithDimensions(1, 1, 1, date);
    }

    private static byte[] schematicWithDimensions(
            int width, int height, int length, long date) throws IOException {
        LinCompoundTag metadata = LinCompoundTag.builder()
                .putLong("Date", date)
                .putString("Stable", "value")
                .build();
        LinCompoundTag schematic = LinCompoundTag.builder()
                .putInt("Version", 3)
                .putShort("Width", (short) width)
                .putShort("Height", (short) height)
                .putShort("Length", (short) length)
                .put("Metadata", metadata)
                .build();
        return writeRoot(schematic);
    }

    private static byte[] writeRoot(LinCompoundTag schematic) throws IOException {
        LinRootEntry root = new LinRootEntry("",
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
