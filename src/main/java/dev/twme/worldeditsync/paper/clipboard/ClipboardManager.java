package dev.twme.worldeditsync.paper.clipboard;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.transfer.TransferSession;
import dev.twme.worldeditsync.paper.WorldEditSyncPaper;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ClipboardManager {
    private final WorldEditSyncPaper plugin;
    private final Map<UUID, ClipboardData> clipboardCache;
    private final Map<String, TransferSession> activeSessions;
    private final Set<UUID> firstCheck = new HashSet<>();
    private final MiniMessage mm = MiniMessage.miniMessage();

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

        return String.valueOf(clipboard.hashCode());
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
            plugin.getLogger().warning("Cannot find active session: " + sessionId);
            return;
        }

        // 驗證玩家
        if (!session.getPlayerUuid().equals(player.getUniqueId())) {
            plugin.getLogger().warning("Player's UUID does not match: " + player.getName());
            return;
        }

        // 添加區塊數據
        session.addChunk(chunkIndex, chunkData);

        player.sendActionBar(mm.deserialize("<blue>Receiving clipboard... <gray>(" + session.getChunkCount() + "</gray>/<yellow>" + session.getTotalChunks() + "</yellow>)</blue>"));

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
                plugin.getLogger().warning("Cannot assemble data: " + session.getSessionId());
                return;
            }

            Clipboard clipboard = plugin.getWorldEditHelper().deserializeClipboard(fullData);

            // 反序列化並設置剪貼簿
            setLocalClipboard(player.getUniqueId(), fullData, calculateClipboardHash(
                    clipboard
            ));

            plugin.getWorldEditHelper().setPlayerClipboard(player, clipboard);

            //player.sendMessage("§a剪貼簿同步完成！");
            player.sendActionBar(mm.deserialize("<green>Clipboard synchronized!</green>"));

            plugin.getLogger().info(String.format(
                    "Data received - Player: %s, Session: %s, Chunk: %d, Size: %d",
                    player.getName(), session.getSessionId(), session.getTotalChunks(), session.getChunkSize()
            ));
            check(player.getUniqueId());
        } catch (Exception e) {
            plugin.getLogger().severe("An error occurred while synchronizing clipboard: " + e.getMessage());
            player.sendMessage("§cAn error occurred while synchronizing clipboard!");
            e.fillInStackTrace();
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
                plugin.getLogger().warning("The clipboard is too large to sync: " + player.getName());
                player.sendMessage("§cThe clipboard is too large to sync!");
                return;
            }

            // 在上傳之前檢查並下載剪貼簿
            if (!checkOrDownloadClipboard(player)) {
                plugin.getLogger().warning("Failed to check or download clipboard before upload: " + player.getName());
                player.sendActionBar(mm.deserialize("Failed to check or download clipboard before upload"));
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

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                sendChunks(player, sessionId, data, totalChunks);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.getLogger().info("Uploaded clipboard for player: " + player.getName() + ", Session: " + sessionId + ", Chunks: " + totalChunks);
                });
            });

        } catch (Exception e) {
            plugin.getLogger().severe("上傳剪貼簿時發生錯誤: " + e.getMessage());
            player.sendMessage("§c上傳剪貼簿時發生錯誤！");
        }
    }

    private void sendChunks(Player player, String sessionId, byte[] data, int totalChunks) {
        //wait 10ms
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            e.fillInStackTrace();
        }

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
                out.writeInt(i + 1);  // 區塊索引
                out.writeInt(chunkLength);  // 區塊大小
                out.write(chunkData);  // 區塊數據

                player.sendPluginMessage(plugin, Constants.CHANNEL, out.toByteArray());

                if (i + 1 < totalChunks) {
                    player.sendActionBar(mm.deserialize("<blue>Uploading clipboard... <gray>(" + (i + 1) + "</gray>/<yellow>" + totalChunks + "</yellow>)</blue>"));
                } else {
                    player.sendActionBar(mm.deserialize("<green>Clipboard uploaded!</green>"));
                }
            }

        } catch (Exception e) {
            plugin.getLogger().severe("An error occurred while sending chunks: " + e.getMessage());
            throw e;
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
            // plugin.getLogger().warning("當前剪貼簿為空");
            return false;
        }

        // 如果玩家沒有暫存的剪貼簿數據，視為有變化
        if (!clipboardCache.containsKey(player.getUniqueId())) {
            return true;
        }

        try {
            // 計算當前剪貼簿的雜湊值
            String currentHash = calculateClipboardHash(currentClipboard);
            // 獲取儲存的雜湊值
            String storedHash = getLocalHash(player.getUniqueId());

            // 如果任一雜湊值為空，視為有變化
            if (currentHash.isEmpty() || storedHash.isEmpty()) {
                return true;
            }

            return !currentHash.equals(storedHash);

        } catch (Exception e) {
            plugin.getLogger().warning("An error occurred while checking clipboard changes: " + e.getMessage());
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
            plugin.getLogger().warning("An error occurred while comparing clipboard data: " + e.getMessage());
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
            plugin.getLogger().warning("An error occurred while getting current clipboard hash: " + e.getMessage());
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

    /**
     * 請求下載剪貼簿
     */
    public void requestClipboardDownload(Player player) {
        // plugin.getLogger().info("開始請求下載剪貼簿");

        try {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("ClipboardDownload");
            out.writeUTF(player.getUniqueId().toString());

            player.sendPluginMessage(plugin, Constants.CHANNEL, out.toByteArray());
            // player.sendMessage("§e開始從Velocity下載剪貼簿...");

        } catch (Exception e) {
            plugin.getLogger().severe("Requesting clipboard download failed: " + e.getMessage());
            // player.sendMessage("§c請求下載剪貼簿時發生錯誤！");
        }
    }

    /**
     * 檢查並下載剪貼簿
     */
    public boolean checkOrDownloadClipboard(Player player) {
        // plugin.getLogger().info("檢查或下載剪貼簿");
        try {
            // 檢查本地剪貼簿雜湊值
            String localHash = getLocalHash(player.getUniqueId());
            if (localHash.isEmpty()) {
                // 如果本地沒有剪貼簿，請求下載
                requestClipboardDownload(player);
                return false;
            }


            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("An error occurred while checking and downloading clipboard: " + e.getMessage());
            player.sendActionBar(mm.deserialize("An error occurred while checking and downloading clipboard"));
            return false;
        }
    }

    public boolean isChecked(UUID playerUuid) {
        return firstCheck.contains(playerUuid);
    }

    public void check(UUID playerUuid) {
        firstCheck.add(playerUuid);
    }

    public void uncheck(UUID playerUuid) {
        firstCheck.remove(playerUuid);
    }

    public void startUploadClipboard(Player player) {
        Clipboard clipboard = plugin.getWorldEditHelper().getPlayerClipboard(player);
        byte[] serializedClipboard = plugin.getWorldEditHelper().serializeClipboard(clipboard);
        if (serializedClipboard != null) {
            String hash = plugin.getClipboardManager().calculateClipboardHash(clipboard);
            plugin.getClipboardManager().setLocalClipboard(player.getUniqueId(), serializedClipboard, hash);
            plugin.getClipboardManager().uploadClipboard(player, serializedClipboard);
        }
    }
}
