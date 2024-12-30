package dev.twme.worldeditsync.paper.clipboard;

import com.google.common.hash.Hashing;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BaseBlock;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.transfer.TransferSession;
import dev.twme.worldeditsync.paper.WorldEditSyncPaper;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ClipboardManager {
    private final WorldEditSyncPaper plugin;
    private final Map<UUID, ClipboardData> clipboardCache;
    private final Map<String, TransferSession> activeSessions;

    public ClipboardManager(WorldEditSyncPaper plugin) {
        this.plugin = plugin;
        this.clipboardCache = new ConcurrentHashMap<>();
        this.activeSessions = new HashMap<>();
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

            for (Entity entity: clipboard.getEntities()) {
                contentBuilder.append(entity.toString());
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
     * 開始新的下載會話
     */
    public void startDownloadSession(Player player, String sessionId, int totalChunks, int chunkSize) {
        TransferSession session = new TransferSession(
                player.getUniqueId(),
                sessionId,
                totalChunks,
                chunkSize
        );
        activeSessions.put(sessionId, session);
    }

    /**
     * 處理接收到的區塊數據
     */
    public void handleChunkData(Player player, String sessionId, int chunkIndex, byte[] chunkData) {
        TransferSession session = activeSessions.get(sessionId);
        if (session == null) {
            plugin.getLogger().warning("找不到會話: " + sessionId);
            return;
        }

        // 驗證玩家
        if (!session.getPlayerUuid().equals(player.getUniqueId())) {
            plugin.getLogger().warning("玩家UUID不匹配");
            return;
        }

        // 添加區塊數據
        session.addChunk(chunkIndex, chunkData);

        // 檢查是否完成
        if (session.isComplete()) {
            handleCompleteTransfer(player, session);
            activeSessions.remove(sessionId);
        }
    }

    /**
     * 處理完成的傳輸
     */
    private void handleCompleteTransfer(Player player, TransferSession session) {
        try {
            byte[] fullData = session.assembleData();
            if (fullData == null) {
                plugin.getLogger().warning("無法組裝完整數據");
                return;
            }

            // 反序列化並設置剪貼簿
            setLocalClipboard(player.getUniqueId(), fullData, calculateClipboardHash(
                    plugin.getWorldEditHelper().deserializeClipboard(fullData)
            ));

            plugin.getLogger().info("玩家 " + player.getName() + " 的剪貼簿已同步完成");
            player.sendMessage("§a剪貼簿同步完成！");

        } catch (Exception e) {
            plugin.getLogger().severe("完成傳輸時發生錯誤: " + e.getMessage());
            player.sendMessage("§c同步剪貼簿時發生錯誤！");
        }
    }

    public void uploadClipboard(Player player, byte[] data) {
        try {
            // 生成一個新的會話ID
            String sessionId = UUID.randomUUID().toString();

            // 計算總區塊數
            int totalChunks = (int) Math.ceil(data.length / (double) Constants.DEFAULT_CHUNK_SIZE);

            // 檢查區塊數量是否超過限制
            if (totalChunks > Constants.MAX_CHUNKS) {
                plugin.getLogger().warning("剪貼簿過大，無法上傳: " + player.getName());
                player.sendMessage("§c剪貼簿太大，無法同步！");
                return;
            }

            // 發送上傳開始訊息
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("ClipboardUpload");
            out.writeUTF(player.getUniqueId().toString());
            out.writeUTF(sessionId);
            out.writeInt(totalChunks);
            out.writeInt(Constants.DEFAULT_CHUNK_SIZE);

            player.sendPluginMessage(plugin, Constants.CHANNEL, out.toByteArray());

            // 發送區塊數據
            sendChunks(player, sessionId, data, totalChunks);

            player.sendMessage("§e正在上傳剪貼簿...");

        } catch (Exception e) {
            plugin.getLogger().severe("上傳剪貼簿時發生錯誤: " + e.getMessage());
            player.sendMessage("§c上傳剪貼簿時發生錯誤！");
        }
    }

    private void sendChunks(Player player, String sessionId, byte[] data, int totalChunks) {
        try {
            for (int i = 0; i < totalChunks; i++) {
                // 計算當前區塊的起始和結束位置
                int start = i * Constants.DEFAULT_CHUNK_SIZE;
                int end = Math.min(start + Constants.DEFAULT_CHUNK_SIZE, data.length);
                int chunkLength = end - start;

                // 創建區塊數據
                byte[] chunkData = new byte[chunkLength];
                System.arraycopy(data, start, chunkData, 0, chunkLength);

                // 發送區塊
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("ClipboardChunk");
                out.writeUTF(sessionId);
                out.writeInt(i);  // 區塊索引
                out.writeInt(chunkLength);  // 區塊大小
                out.write(chunkData);  // 區塊數據

                player.sendPluginMessage(plugin, Constants.CHANNEL, out.toByteArray());

                plugin.getLogger().warning(String.format(
                        "發送區塊 %d/%d, 大小: %d bytes",
                        i + 1, totalChunks, chunkLength
                ));
            }

            plugin.getLogger().info(String.format(
                    "完成發送剪貼簿，共 %d 個區塊",
                    totalChunks
            ));

        } catch (Exception e) {
            plugin.getLogger().severe("發送區塊數據時發生錯誤: " + e.getMessage());
            throw e;  // 讓上層方法處理錯誤
        }
    }

    /**
     * 處理接收到的剪貼簿數據
     */
    public void handleClipboardData(Player player, byte[] data) {
        try {
            // 反序列化剪貼簿數據
            Clipboard clipboard = plugin.getWorldEditHelper().deserializeClipboard(data);
            if (clipboard == null) {
                throw new IllegalStateException("無法反序列化剪貼簿數據");
            }

            // 計算並存儲雜湊值
            String hash = calculateClipboardHash(clipboard);
            setLocalClipboard(player.getUniqueId(), data, hash);

            // 設置玩家的剪貼簿
            plugin.getWorldEditHelper().setPlayerClipboard(player, clipboard);

            plugin.getLogger().info(String.format(
                    "已設置玩家 %s 的剪貼簿 (雜湊值: %s)",
                    player.getName(), hash
            ));

        } catch (Exception e) {
            plugin.getLogger().severe("處理剪貼簿數據時發生錯誤: " + e.getMessage());
            throw new RuntimeException("處理剪貼簿數據失敗", e);
        }
    }

    /**
     * 檢查玩家的剪貼簿是否有變化
     * @param player 要檢查的玩家
     * @param currentClipboard 當前的剪貼簿
     * @return 如果剪貼簿有變化則返回 true
     */
    public boolean hasClipboardChanged(Player player, Clipboard currentClipboard) {
        // 如果當前剪貼簿為空，視為無變化
        if (currentClipboard == null) {
            plugin.getLogger().warning("當前剪貼簿為空");
            return false;
        }

        // 如果玩家沒有暫存的剪貼簿數據，視為有變化
        if (!clipboardCache.containsKey(player.getUniqueId())) {
            plugin.getLogger().warning("玩家沒有暫存的剪貼簿數據");
            return true;
        }

        try {
            // 計算當前剪貼簿的雜湊值
            String currentHash = calculateClipboardHash(currentClipboard);
            // 獲取儲存的雜湊值
            String storedHash = getLocalHash(player.getUniqueId());

            // 如果任一雜湊值為空，視為有變化
            if (currentHash.isEmpty() || storedHash.isEmpty()) {
                plugin.getLogger().warning("雜湊值為空 - 當前: " + currentHash + ", 儲存: " + storedHash);
                return true;
            }

            // 比較雜湊值
            boolean changed = !currentHash.equals(storedHash);

            // 添加調試日誌
            if (changed) {
                plugin.getLogger().warning("剪貼簿雜湊值不同:");
                plugin.getLogger().warning("當前: " + currentHash);
                plugin.getLogger().warning("儲存: " + storedHash);
            }

            return changed;

        } catch (Exception e) {
            plugin.getLogger().warning("檢查剪貼簿變化時發生錯誤: " + e.getMessage());
            // 發生錯誤時，為安全起見返回 true
            return true;
        }
    }

    /**
     * 檢查給定的剪貼簿數據與儲存的數據是否相同
     * @param playerUuid 玩家UUID
     * @param clipboardData 要比較的剪貼簿數據
     * @return 如果數據相同則返回 true
     */
    public boolean isSameClipboard(UUID playerUuid, byte[] clipboardData) {
        try {
            byte[] storedData = getLocalData(playerUuid);
            if (storedData == null || clipboardData == null) {
                return false;
            }

            // 如果數據長度不同，直接返回 false
            if (storedData.length != clipboardData.length) {
                return false;
            }

            // 比較每一個字節
            for (int i = 0; i < storedData.length; i++) {
                if (storedData[i] != clipboardData[i]) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("比較剪貼簿數據時發生錯誤: " + e.getMessage());
            return false;
        }
    }

    /**
     * 獲取玩家當前的剪貼簿雜湊值
     * @param player 玩家
     * @return 雜湊值，如果無法獲取則返回空字符串
     */
    public String getCurrentClipboardHash(Player player) {
        try {
            Clipboard clipboard = plugin.getWorldEditHelper().getPlayerClipboard(player);
            if (clipboard == null) {
                return "";
            }
            return calculateClipboardHash(clipboard);
        } catch (Exception e) {
            plugin.getLogger().warning("獲取當前剪貼簿雜湊值時發生錯誤: " + e.getMessage());
            return "";
        }
    }

    /**
     * 清理快取
     */
    public void cleanup() {
        clipboardCache.clear();
        activeSessions.clear();
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