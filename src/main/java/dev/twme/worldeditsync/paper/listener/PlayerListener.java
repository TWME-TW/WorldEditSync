package dev.twme.worldeditsync.paper.listener;

import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.transfer.SyncState;
import dev.twme.worldeditsync.common.transfer.TransferProtocol;
import dev.twme.worldeditsync.paper.WorldEditSyncPaper;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Paper 端的玩家事件監聽器。
 * 處理：
 * - 玩家離開：清理資源、取消進行中的傳輸
 * - 玩家執行 //copy 或 //cut：取消進行中的下載，確保 Watcher 能偵測新的本地剪貼簿
 */
public class PlayerListener implements Listener {
    private final WorldEditSyncPaper plugin;

    public PlayerListener(WorldEditSyncPaper plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // 如有進行中的傳輸，通知 Proxy 取消
        SyncState state = plugin.getClipboardManager().getState(uuid);
        if (state == SyncState.UPLOADING || state == SyncState.DOWNLOADING) {
            player.sendPluginMessage(plugin, Constants.CHANNEL,
                    TransferProtocol.createCancel(uuid.toString()));
        }

        plugin.getClipboardManager().removePlayer(uuid);
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String command = event.getMessage().split(" ")[0].toLowerCase();

        // 當玩家執行 //copy 或 //cut 時，取消進行中的傳輸並啟用偵測
        if (command.equals("//copy") || command.equals("//cut")) {
            UUID uuid = player.getUniqueId();
            SyncState state = plugin.getClipboardManager().getState(uuid);

            // 取消進行中的下載或上傳
            if (state == SyncState.DOWNLOADING || state == SyncState.UPLOADING) {
                player.sendPluginMessage(plugin, Constants.CHANNEL,
                        TransferProtocol.createCancel(uuid.toString()));
            }

            // 確保狀態為 IDLE，讓 ClipboardWatcher 偵測到新的剪貼簿
            plugin.getClipboardManager().setState(uuid, SyncState.IDLE);
        }
    }
}
