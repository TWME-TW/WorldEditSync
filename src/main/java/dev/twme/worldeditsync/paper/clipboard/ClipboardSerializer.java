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
import org.enginehub.linbus.stream.LinBinaryIO;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.enginehub.linbus.tree.LinRootEntry;
import org.enginehub.linbus.tree.LinTagType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class ClipboardSerializer {

    /**
     * Get the player's current WorldEdit clipboard, or null if none.
     */
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

    /**
     * Serialize a WorldEdit Clipboard to Sponge V3 schematic bytes.
     */
    public byte[] serialize(Clipboard clipboard) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ClipboardWriter writer = createWriter(bos)) {
            writer.write(clipboard);
        }
        return canonicalize(bos.toByteArray());
    }

    protected ClipboardWriter createWriter(OutputStream output) throws IOException {
        return BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(output);
    }

    /**
     * Remove volatile writer metadata so equal clipboard contents produce equal bytes.
     * WorldEdit writes the current time to Metadata.Date on every serialization.
     */
    protected byte[] canonicalize(byte[] data) throws IOException {
        LinRootEntry root;
        try (DataInputStream input = new DataInputStream(
                new GZIPInputStream(new ByteArrayInputStream(data)))) {
            root = LinRootEntry.readFrom(LinBinaryIO.read(input));
        }

        LinCompoundTag rootValue = root.value();
        LinCompoundTag schematic = rootValue.findTag("Schematic", LinTagType.compoundTag());
        if (schematic == null) {
            return data;
        }

        LinCompoundTag metadata = schematic.findTag("Metadata", LinTagType.compoundTag());
        if (metadata == null || !metadata.value().containsKey("Date")) {
            return data;
        }

        LinCompoundTag canonicalMetadata = metadata.toBuilder()
                .remove("Date")
                .build();
        LinCompoundTag canonicalSchematic = schematic.toBuilder()
                .put("Metadata", canonicalMetadata)
                .build();
        LinRootEntry canonicalRoot = new LinRootEntry(
                root.name(),
                rootValue.toBuilder().put("Schematic", canonicalSchematic).build());

        ByteArrayOutputStream output = new ByteArrayOutputStream(data.length);
        try (GZIPOutputStream gzip = new GZIPOutputStream(output);
             DataOutputStream dataOutput = new DataOutputStream(gzip)) {
            LinBinaryIO.write(dataOutput, canonicalRoot);
        }
        return output.toByteArray();
    }

    /**
     * Deserialize bytes (Sponge V3 schematic) into a WorldEdit Clipboard.
     */
    public Clipboard deserialize(byte[] data) throws IOException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             ClipboardReader reader = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getReader(bis)) {
            return reader.read();
        }
    }

    /**
     * Set the player's WorldEdit clipboard.
     */
    public void setPlayerClipboard(Player player, Clipboard clipboard) {
        WorldEditPlugin we = (WorldEditPlugin) Bukkit.getPluginManager().getPlugin("WorldEdit");
        if (we == null) return;

        var actor = BukkitAdapter.adapt(player);
        var session = we.getWorldEdit().getSessionManager().get(actor);
        session.setClipboard(new ClipboardHolder(clipboard));
    }
}
