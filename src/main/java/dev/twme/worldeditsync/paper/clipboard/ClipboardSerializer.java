package dev.twme.worldeditsync.paper.clipboard;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.session.ClipboardHolder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import dev.twme.worldeditsync.common.Constants;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class ClipboardSerializer {

    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_LONG_ARRAY = 12;
    private static final int MAX_NBT_DEPTH = 64;
    private static final long MAX_NBT_TAGS = 1_000_000L;

    /** Get the player's current WorldEdit clipboard, or null if none. */
    public Clipboard getPlayerClipboard(Player player) {
        try {
            WorldEditPlugin we = (WorldEditPlugin) Bukkit.getPluginManager().getPlugin("WorldEdit");
            if (we == null) return null;

            var actor = BukkitAdapter.adapt(player);
            var session = we.getWorldEdit().getSessionManager().get(actor);
            return session.getClipboard().getClipboard();
        } catch (Exception e) {
            return null;
        }
    }

    /** Serialize a WorldEdit Clipboard to canonical Sponge V3 schematic bytes. */
    public byte[] serialize(Clipboard clipboard) throws IOException {
        validateVolume(clipboard, Constants.DEFAULT_MAX_CLIPBOARD_BLOCKS);
        byte[] data = writeClipboard(clipboard, Constants.DEFAULT_MAX_CLIPBOARD_SIZE);
        return canonicalize(data);
    }

    public byte[] serialize(Clipboard clipboard, int maxBytes, long maxBlocks) throws IOException {
        validateLimits(maxBytes, maxBlocks);
        validateVolume(clipboard, maxBlocks);
        return canonicalize(writeClipboard(clipboard, maxBytes), maxBytes, maxBlocks);
    }

    private byte[] writeClipboard(Clipboard clipboard, int maxBytes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
        try (ClipboardWriter writer = createWriter(new BoundedOutputStream(bytes, maxBytes))) {
            writer.write(clipboard);
        }
        return bytes.toByteArray();
    }

    protected ClipboardWriter createWriter(OutputStream output) throws IOException {
        return BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(output);
    }

    /** Removes WorldEdit's volatile Metadata.Date without materializing the NBT tree. */
    protected byte[] canonicalize(byte[] data) throws IOException {
        return canonicalize(data, Constants.DEFAULT_MAX_CLIPBOARD_SIZE,
                Constants.DEFAULT_MAX_CLIPBOARD_BLOCKS);
    }

    protected byte[] canonicalize(byte[] data, int maxBytes, long maxBlocks) throws IOException {
        validateLimits(maxBytes, maxBlocks);
        if (data == null || data.length == 0 || data.length > maxBytes) {
            throw new IOException("Schematic exceeds configured compressed size limit");
        }

        long expandedLimit = expandedLimit(maxBytes, maxBlocks);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(data.length, 64 * 1024));
        NbtCopyState state = new NbtCopyState(maxBlocks, expandedLimit);
        try (DataInputStream input = new DataInputStream(new LimitedInputStream(
                     new GZIPInputStream(new ByteArrayInputStream(data)), expandedLimit));
             DataOutputStream output = new DataOutputStream(new GZIPOutputStream(
                     new BoundedOutputStream(bytes, maxBytes)))) {
            int rootType = input.readUnsignedByte();
            if (rootType != TAG_COMPOUND) {
                throw new IOException("Schematic root must be a compound tag");
            }
            output.writeByte(rootType);
            output.writeUTF(input.readUTF());
            copyPayload(input, output, rootType, Context.ROOT, null, 0, state);
            if (input.read() != -1) {
                throw new IOException("Trailing data after schematic root");
            }
        }
        state.validateDimensions();
        return bytes.toByteArray();
    }

    public Clipboard deserialize(byte[] data) throws IOException {
        return deserialize(data, Constants.DEFAULT_MAX_CLIPBOARD_SIZE,
                Constants.DEFAULT_MAX_CLIPBOARD_BLOCKS);
    }

    public Clipboard deserialize(byte[] data, int maxBytes, long maxBlocks) throws IOException {
        byte[] validated = canonicalize(data, maxBytes, maxBlocks);
        try (ByteArrayInputStream input = new ByteArrayInputStream(validated);
             ClipboardReader reader = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getReader(input)) {
            Clipboard clipboard = reader.read();
            validateVolume(clipboard, maxBlocks);
            return clipboard;
        }
    }

    /** Set the player's WorldEdit clipboard. */
    public void setPlayerClipboard(Player player, Clipboard clipboard) {
        WorldEditPlugin we = (WorldEditPlugin) Bukkit.getPluginManager().getPlugin("WorldEdit");
        if (we == null) return;

        var actor = BukkitAdapter.adapt(player);
        var session = we.getWorldEdit().getSessionManager().get(actor);
        session.setClipboard(new ClipboardHolder(clipboard));
    }

    private static void copyPayload(DataInputStream input, DataOutputStream output, int type,
                                    Context context, String name, int depth,
                                    NbtCopyState state) throws IOException {
        if (depth > MAX_NBT_DEPTH) {
            throw new IOException("Schematic NBT nesting is too deep");
        }
        state.recordTag();
        switch (type) {
            case TAG_BYTE -> output.writeByte(input.readByte());
            case TAG_SHORT -> {
                short value = input.readShort();
                output.writeShort(value);
                state.recordDimension(context, name, Short.toUnsignedInt(value));
            }
            case TAG_INT -> {
                int value = input.readInt();
                output.writeInt(value);
                state.recordDimension(context, name, value);
            }
            case TAG_LONG -> output.writeLong(input.readLong());
            case TAG_FLOAT -> output.writeFloat(input.readFloat());
            case TAG_DOUBLE -> output.writeDouble(input.readDouble());
            case TAG_BYTE_ARRAY -> copyArray(input, output, 1, state);
            case TAG_STRING -> output.writeUTF(input.readUTF());
            case TAG_LIST -> {
                int elementType = input.readUnsignedByte();
                int length = input.readInt();
                validateTagType(elementType, true);
                if (elementType == TAG_END && length != 0) {
                    throw new IOException("Non-empty schematic list has no element type");
                }
                state.recordCollection(length);
                output.writeByte(elementType);
                output.writeInt(length);
                for (int index = 0; index < length; index++) {
                    copyPayload(input, output, elementType, Context.OTHER,
                            null, depth + 1, state);
                }
            }
            case TAG_COMPOUND -> {
                while (true) {
                    int childType = input.readUnsignedByte();
                    if (childType == TAG_END) {
                        output.writeByte(TAG_END);
                        break;
                    }
                    validateTagType(childType, false);
                    String childName = input.readUTF();
                    Context childContext = childContext(context, childType, childName);
                    boolean removeDate = context == Context.METADATA && "Date".equals(childName);
                    if (!removeDate) {
                        output.writeByte(childType);
                        output.writeUTF(childName);
                    }
                    copyPayload(input, removeDate ? state.discarded : output,
                            childType, childContext, childName, depth + 1, state);
                }
            }
            case TAG_INT_ARRAY -> copyArray(input, output, Integer.BYTES, state);
            case TAG_LONG_ARRAY -> copyArray(input, output, Long.BYTES, state);
            default -> throw new IOException("Unsupported schematic NBT tag type: " + type);
        }
    }

    private static void copyArray(DataInputStream input, DataOutputStream output,
                                  int elementBytes, NbtCopyState state) throws IOException {
        int length = input.readInt();
        state.recordArray(length, elementBytes);
        output.writeInt(length);
        long remaining = (long) length * elementBytes;
        byte[] buffer = new byte[8 * 1024];
        while (remaining > 0L) {
            int amount = (int) Math.min(buffer.length, remaining);
            input.readFully(buffer, 0, amount);
            output.write(buffer, 0, amount);
            remaining -= amount;
        }
    }

    private static Context childContext(Context parent, int type, String name) {
        if (type != TAG_COMPOUND) {
            return parent;
        }
        if (parent == Context.ROOT && "Schematic".equals(name)) {
            return Context.SCHEMATIC;
        }
        if (parent == Context.SCHEMATIC && "Metadata".equals(name)) {
            return Context.METADATA;
        }
        return Context.OTHER;
    }

    private static void validateTagType(int type, boolean listElement) throws IOException {
        if (type < TAG_END || type > TAG_LONG_ARRAY || (!listElement && type == TAG_END)) {
            throw new IOException("Invalid schematic NBT tag type: " + type);
        }
    }

    private static void validateLimits(int maxBytes, long maxBlocks) {
        if (maxBytes <= 0 || maxBlocks <= 0L) {
            throw new IllegalArgumentException("Clipboard limits must be positive");
        }
    }

    private static void validateVolume(Clipboard clipboard, long maxBlocks) throws IOException {
        if (clipboard == null) {
            return;
        }
        long volume = clipboard.getRegion().getVolume();
        if (volume <= 0L || volume > maxBlocks) {
            throw new IOException("Clipboard exceeds configured block limit: " + volume);
        }
    }

    private static long expandedLimit(int maxBytes, long maxBlocks) {
        long byCompressedSize = saturatingMultiply(maxBytes, 4L);
        long byBlockCount = saturatingAdd(saturatingMultiply(maxBlocks, 6L), 16L * 1024 * 1024);
        return Math.min(Constants.MAX_EXPANDED_SCHEMATIC_SIZE,
                Math.max(16L * 1024 * 1024, Math.max(byCompressedSize, byBlockCount)));
    }

    private static long saturatingMultiply(long value, long multiplier) {
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }

    private static long saturatingAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private enum Context {
        ROOT,
        SCHEMATIC,
        METADATA,
        OTHER
    }

    private static final class NbtCopyState {
        private final long maxBlocks;
        private final long expandedLimit;
        private final long maxCollectionElements;
        private long tags;
        private int width = -1;
        private int height = -1;
        private int length = -1;
        private final DataOutputStream discarded = new DataOutputStream(OutputStream.nullOutputStream());

        private NbtCopyState(long maxBlocks, long expandedLimit) {
            this.maxBlocks = maxBlocks;
            this.expandedLimit = expandedLimit;
            this.maxCollectionElements = saturatingAdd(maxBlocks, 1_000_000L);
        }

        private void recordTag() throws IOException {
            if (++tags > MAX_NBT_TAGS) {
                throw new IOException("Schematic contains too many NBT tags");
            }
        }

        private void recordCollection(int elements) throws IOException {
            if (elements < 0 || elements > maxCollectionElements) {
                throw new IOException("Schematic collection exceeds configured element limit");
            }
        }

        private void recordArray(int elements, int elementBytes) throws IOException {
            if (elements < 0 || (long) elements * elementBytes > expandedLimit) {
                throw new IOException("Schematic array exceeds configured size limit");
            }
        }

        private void recordDimension(Context context, String name, int value) throws IOException {
            if (context != Context.SCHEMATIC || name == null) {
                return;
            }
            switch (name) {
                case "Width" -> width = uniqueDimension(width, value);
                case "Height" -> height = uniqueDimension(height, value);
                case "Length" -> length = uniqueDimension(length, value);
                default -> {
                }
            }
        }

        private int uniqueDimension(int current, int value) throws IOException {
            if (current != -1) {
                throw new IOException("Schematic contains duplicate dimensions");
            }
            return value;
        }

        private void validateDimensions() throws IOException {
            if (width <= 0 || height <= 0 || length <= 0
                    || (long) width * height > maxBlocks
                    || (long) width * height * length > maxBlocks) {
                throw new IOException("Schematic dimensions exceed configured block limit");
            }
        }
    }

    private static final class BoundedOutputStream extends FilterOutputStream {
        private final long limit;
        private long written;

        private BoundedOutputStream(OutputStream output, long limit) {
            super(output);
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            out.write(value);
            written++;
        }

        @Override
        public void write(byte[] value, int offset, int length) throws IOException {
            requireCapacity(length);
            out.write(value, offset, length);
            written += length;
        }

        private void requireCapacity(int amount) throws IOException {
            if (amount < 0 || written > limit - amount) {
                throw new IOException("Schematic exceeds configured compressed size limit");
            }
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long read;

        private LimitedInputStream(InputStream input, long limit) {
            super(input);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value != -1 && ++read > limit) {
                throw new IOException("Schematic exceeds configured expanded size limit");
            }
            return value;
        }

        @Override
        public int read(byte[] value, int offset, int length) throws IOException {
            int amount = in.read(value, offset, length);
            if (amount > 0 && (read > limit - amount)) {
                throw new IOException("Schematic exceeds configured expanded size limit");
            }
            if (amount > 0) {
                read += amount;
            }
            return amount;
        }
    }

}
