package dev.twme.worldeditsync.paper.clipboard;

import com.google.common.hash.Hashing;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BaseBlock;
import dev.twme.worldeditsync.paper.WorldEditSyncPaper;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ClipboardManager {
    private final WorldEditSyncPaper plugin;
    private final Map<UUID, ClipboardData> clipboardCache;

    public ClipboardManager(WorldEditSyncPaper plugin) {
        this.plugin = plugin;
        this.clipboardCache = new ConcurrentHashMap<>();
    }

    /**
     * 儲存本地剪貼簿數據
     */
    public void setLocalClipboard(UUID playerUuid, byte[] data, String hash) {
        clipboardCache.put(playerUuid, new ClipboardData(data, hash));
    }

    /**
     * 獲取本地儲存的剪貼簿雜湊值
     */
    public String getLocalHash(UUID playerUuid) {
        ClipboardData data = clipboardCache.get(playerUuid);
        return data != null ? data.hash : "";
    }

    /**
     * 獲取本地儲存的剪貼簿數據
     */
    public byte[] getLocalData(UUID playerUuid) {
        ClipboardData data = clipboardCache.get(playerUuid);
        return data != null ? data.data : null;
    }

    /**
     * 計算剪貼簿的雜湊值
     */
    public String calculateClipboardHash(Clipboard clipboard) {
        if (clipboard == null) return "";

        try {
            StringBuilder contentBuilder = new StringBuilder();

            // 加入區域大小信息
            com.sk89q.worldedit.regions.Region region = clipboard.getRegion();
            contentBuilder.append(region.getWidth())
                    .append(":")
                    .append(region.getHeight())
                    .append(":")
                    .append(region.getLength())
                    .append(":");

            // 加入原點信息
            BlockVector3 origin = clipboard.getOrigin();
            contentBuilder.append(origin.x())
                    .append(":")
                    .append(origin.y())
                    .append(":")
                    .append(origin.z())
                    .append(":");

            // 加入方塊數據
            for (BlockVector3 pt : region) {
                BaseBlock block = clipboard.getFullBlock(pt);
                contentBuilder.append(block.getAsString());
            }

            // 計算雜湊值
            return Hashing.sha256()
                    .hashString(contentBuilder.toString(), StandardCharsets.UTF_8)
                    .toString();

        } catch (Exception e) {
            plugin.getLogger().warning("計算剪貼簿雜湊值時發生錯誤: " + e.getMessage());
            return "";
        }
    }

    /**
     * 檢查玩家的剪貼簿是否有變化
     */
    public boolean hasClipboardChanged(Player player, Clipboard currentClipboard) {
        String currentHash = calculateClipboardHash(currentClipboard);
        String storedHash = getLocalHash(player.getUniqueId());

        // 添加調試日誌
        if (!currentHash.equals(storedHash)) {
            plugin.getLogger().warning("剪貼簿雜湊值不同:");
            plugin.getLogger().warning("當前: " + currentHash);
            plugin.getLogger().warning("儲存: " + storedHash);
        }

        return !currentHash.equals(storedHash);
    }

    /**
     * 清理快取
     */
    public void cleanup() {
        clipboardCache.clear();
    }

    /**
     * 剪貼簿數據類
     */
    private static class ClipboardData {
        private final byte[] data;
        private final String hash;
        private final long timestamp;

        public ClipboardData(byte[] data, String hash) {
            this.data = data;
            this.hash = hash;
            this.timestamp = System.currentTimeMillis();
        }
    }
}