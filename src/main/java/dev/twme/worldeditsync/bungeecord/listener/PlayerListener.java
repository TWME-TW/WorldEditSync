package dev.twme.worldeditsync.bungeecord.listener;

import dev.twme.worldeditsync.bungeecord.WorldEditSyncBungee;
import dev.twme.worldeditsync.bungeecord.clipboard.ClipboardManager;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.clipboard.ClipboardData;
import dev.twme.worldeditsync.common.transfer.TransferProtocol;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.util.concurrent.TimeUnit;

/**
 * BungeeCord 端的玩家事件監聽器。
 * 當玩家切換伺服器時，發送 hash 檢查或通知無資料。
 */
public class PlayerListener implements Listener {
    private final WorldEditSyncBungee plugin;
    private final ClipboardManager clipboardManager;

    public PlayerListener(WorldEditSyncBungee plugin) {
        this.plugin = plugin;
        this.clipboardManager = plugin.getClipboardManager();
    }

    @EventHandler
    public void onServerSwitch(ServerSwitchEvent event) {
        ProxiedPlayer player = event.getPlayer();
        clipboardManager.setPlayerTransferring(player.getUniqueId(), false);

        // 延遲 1 秒確保新伺服器連線已就緒
        plugin.getProxy().getScheduler().schedule(plugin, () -> {
            if (!player.isConnected() || player.getServer() == null) return;

            ClipboardData data = clipboardManager.getClipboard(player.getUniqueId());
            if (data != null) {
                // 發送 hash 檢查至新伺服器
                player.getServer().getInfo().sendData(Constants.CHANNEL,
                        TransferProtocol.createHashCheck(player.getUniqueId().toString(), data.getHash()));
            } else {
                // 通知新伺服器無剪貼簿資料
                player.getServer().getInfo().sendData(Constants.CHANNEL,
                        TransferProtocol.createNoData(player.getUniqueId().toString()));
            }
        }, 1, TimeUnit.SECONDS);
    }
}
