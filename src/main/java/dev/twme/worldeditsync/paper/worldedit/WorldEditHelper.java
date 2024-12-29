package dev.twme.worldeditsync.paper.worldedit;

import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.BukkitPlayer;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.*;
import dev.twme.worldeditsync.paper.WorldEditSyncPaper;
import org.bukkit.entity.Player;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class WorldEditHelper {
    private final WorldEditSyncPaper plugin;
    private final WorldEditPlugin worldEdit;

    public WorldEditHelper(WorldEditSyncPaper plugin) {
        this.plugin = plugin;
        this.worldEdit = (WorldEditPlugin) plugin.getServer().getPluginManager().getPlugin("WorldEdit");
    }

    public Clipboard getPlayerClipboard(Player player) {
        try {
            BukkitPlayer bukkitPlayer = BukkitAdapter.adapt(player);
            LocalSession session = worldEdit.getSession(player);
            return session.getClipboard().getClipboard();
        } catch (Exception e) {
            return null;
        }
    }

    public byte[] serializeClipboard(Clipboard clipboard) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            try (ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(bos)) {
                writer.write(clipboard);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to serialize clipboard: " + e.getMessage());
            return null;
        }
    }

    public Clipboard deserializeClipboard(byte[] data) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data)) {
            try (ClipboardReader reader = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getReader(bis)) {
                return reader.read();
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to deserialize clipboard: " + e.getMessage());
            return null;
        }
    }

    public void setPlayerClipboard(Player player, Clipboard clipboard) {
        try {
            BukkitPlayer bukkitPlayer = BukkitAdapter.adapt(player);
            LocalSession session = worldEdit.getSession(player);
            session.setClipboard(new com.sk89q.worldedit.session.ClipboardHolder(clipboard));
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to set player clipboard: " + e.getMessage());
        }
    }
}