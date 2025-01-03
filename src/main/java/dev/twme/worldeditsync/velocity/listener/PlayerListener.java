package dev.twme.worldeditsync.velocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.velocity.WorldEditSyncVelocity;
import dev.twme.worldeditsync.velocity.clipboard.ClipboardManager;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import java.util.concurrent.TimeUnit;

public class PlayerListener {
    private final WorldEditSyncVelocity plugin;
    private final ClipboardManager clipboardManager;

    public PlayerListener(WorldEditSyncVelocity plugin) {
        this.plugin = plugin;
        this.clipboardManager = plugin.getClipboardManager();
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        // 當玩家連接到新服務器時，檢查是否有可用的剪貼簿數據
        ClipboardManager.ClipboardData clipboardData =
                clipboardManager.getClipboard(event.getPlayer().getUniqueId());

        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            if (clipboardData != null) {
                // 發送剪貼簿信息到新服務器
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("ClipboardInfo");
                out.writeUTF(event.getPlayer().getUniqueId().toString());
                out.writeUTF(clipboardData.getHash());

                event.getServer().sendPluginMessage(
                        MinecraftChannelIdentifier.from(Constants.CHANNEL),
                        out.toByteArray()
                );

                // plugin.getLogger().info("發送剪貼簿信息到新服務器: {}", event.getPlayer().getUniqueId());
            } else {

                // 請求從Velocity下載剪貼簿
                // requestClipboardDownload(event);

                // 提醒 Paper Velocity 上沒有剪貼簿資料
                noticeNoClipboardData(event);
            }
        }).delay(1, TimeUnit.SECONDS).schedule();
    }

    private void noticeNoClipboardData(ServerConnectedEvent event) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("NoClipboardData");
        out.writeUTF(event.getPlayer().getUniqueId().toString());

        event.getServer().sendPluginMessage(
                MinecraftChannelIdentifier.from(Constants.CHANNEL),
                out.toByteArray()
        );

        // plugin.getLogger().info("提醒 Paper Velocity 上沒有剪貼簿資料: {}", event.getPlayer().getUniqueId());
    }



    private void requestClipboardDownload(ServerConnectedEvent event) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("ClipboardDownload");
        out.writeUTF(event.getPlayer().getUniqueId().toString());

        event.getServer().sendPluginMessage(
                MinecraftChannelIdentifier.from(Constants.CHANNEL),
                out.toByteArray()
        );

        // plugin.getLogger().info("要求 Paper 從 Velocity 下載剪貼簿: {}", event.getPlayer().getUniqueId());
    }
}
