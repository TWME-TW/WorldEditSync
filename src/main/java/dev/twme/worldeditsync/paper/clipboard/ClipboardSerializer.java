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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

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
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(bos)) {
            writer.write(clipboard);
            return bos.toByteArray();
        }
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
