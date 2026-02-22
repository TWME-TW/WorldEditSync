package dev.twme.worldeditsync.velocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.clipboard.ClipboardData;
import dev.twme.worldeditsync.common.transfer.TransferProtocol;
import dev.twme.worldeditsync.velocity.WorldEditSyncVelocity;
import dev.twme.worldeditsync.velocity.clipboard.ClipboardManager;

import java.util.concurrent.TimeUnit;

/**
 * Velocity 端的玩家事件監聽器。
 * 當玩家切換伺服器時，發送 hash 檢查或通知無資料。
 */
public class PlayerListener {
    private final WorldEditSyncVelocity plugin;
    private final ClipboardManager clipboardManager;
    private static final MinecraftChannelIdentifier CHANNEL_ID = MinecraftChannelIdentifier.from(Constants.CHANNEL);

    public PlayerListener(WorldEditSyncVelocity plugin) {
        this.plugin = plugin;
        this.clipboardManager = plugin.getClipboardManager();
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        clipboardManager.setPlayerTransferring(event.getPlayer().getUniqueId(), false);

        // 延遲 1 秒確保新伺服器連線已就緒
        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            if (!event.getPlayer().isActive()) return;
            if (event.getPlayer().getCurrentServer().isEmpty()) return;

            ClipboardData data = clipboardManager.getClipboard(event.getPlayer().getUniqueId());
            if (data != null) {
                // 發送 hash 檢查至新伺服器
                event.getServer().sendPluginMessage(CHANNEL_ID,
                        plugin.getMessageCipher().encrypt(
                                TransferProtocol.createHashCheck(
                                        event.getPlayer().getUniqueId().toString(), data.getHash())));
            } else {
                // 通知新伺服器無剪貼簿資料
                event.getServer().sendPluginMessage(CHANNEL_ID,
                        plugin.getMessageCipher().encrypt(
                                TransferProtocol.createNoData(
                                        event.getPlayer().getUniqueId().toString())));
            }
        }).delay(1, TimeUnit.SECONDS).schedule();
    }
}
