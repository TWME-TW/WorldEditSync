package dev.twme.worldeditsync.bungeecord.listener;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.bungeecord.WorldEditSyncBungee;
import dev.twme.worldeditsync.bungeecord.clipboard.ClipboardManager;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import java.util.concurrent.TimeUnit;

public class PlayerListener implements Listener {
    private final WorldEditSyncBungee plugin;
    private final ClipboardManager clipboardManager;

    public PlayerListener(WorldEditSyncBungee plugin) {
        this.plugin = plugin;
        this.clipboardManager = plugin.getClipboardManager();
    }

    @EventHandler
    public void onServerSwitch(ServerSwitchEvent event) {
        // 當玩家連接到新服務器時，檢查是否有可用的剪貼簿數據
        handleServerUpdate(event.getPlayer());
    }

    private void handleServerUpdate(ProxiedPlayer player) {
        ClipboardManager.ClipboardData clipboardData =
                clipboardManager.getClipboard(player.getUniqueId());

        plugin.getProxy().getScheduler().schedule(plugin, () -> {
            if (clipboardData != null) {
                // 發送剪貼簿信息到新服務器
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("ClipboardInfo");
                out.writeUTF(player.getUniqueId().toString());
                out.writeUTF(clipboardData.getHash());

                player.getServer().getInfo().sendData(Constants.CHANNEL, out.toByteArray());
            } else {
                // 請求從BungeeCord下載剪貼簿
                noticeNoClipboardData(player);
            }
        }, 1, TimeUnit.SECONDS);
    }

    @EventHandler
    public void onServerConnected(ServerConnectedEvent event) {
        // 當玩家連接到新服務器時，檢查是否有可用的剪貼簿數據
        handleServerUpdate(event.getPlayer());
    }

    private void noticeNoClipboardData(ProxiedPlayer player) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("NoClipboardData");
        out.writeUTF(player.getUniqueId().toString());

        player.getServer().getInfo().sendData(Constants.CHANNEL, out.toByteArray());
    }
}
