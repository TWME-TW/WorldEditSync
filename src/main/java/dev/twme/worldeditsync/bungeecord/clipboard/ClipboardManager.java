package dev.twme.worldeditsync.bungeecord.clipboard;

import java.util.UUID;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import dev.twme.worldeditsync.bungeecord.WorldEditSyncBungee;
import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.clipboard.BaseClipboardManager;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;

public class ClipboardManager extends BaseClipboardManager {
    private final WorldEditSyncBungee plugin;

    public ClipboardManager(WorldEditSyncBungee plugin) {
        this.plugin = plugin;
    }

    public void broadcastClipboardUpdate(UUID playerUuid, byte[] data) {
        ProxiedPlayer targetPlayer = plugin.getProxy().getPlayer(playerUuid);
        if (targetPlayer == null) return;

        for (ServerInfo server : plugin.getProxy().getServers().values()) {
            if (!targetPlayer.getServer().isConnected()) {
                // 玩家不在線上
                return;
            }
            if (!server.equals(targetPlayer.getServer().getInfo())) {
                // 發送到其他服務器
                server.sendData(Constants.CHANNEL, createUpdateMessage(playerUuid, data));
            }
        }
    }

    private byte[] createUpdateMessage(UUID playerUuid, byte[] data) {
        ByteArrayDataOutput out =
                ByteStreams.newDataOutput();
        out.writeUTF("ClipboardUpdate");
        out.writeUTF(playerUuid.toString());
        out.write(data);
        return out.toByteArray();
    }

    @Override
    protected void handleSessionAssemblyFailure(String sessionId) {
        plugin.getLogger().warning("Failed to assemble data: " + sessionId);
    }

    @Override
    protected void handleSessionComplete(String sessionId, UUID playerUuid, byte[] fullData, String hash) {
        plugin.getLogger().info("Finished receiving clipboard data: " + sessionId);
        // 儲存剪貼簿
        storeClipboard(playerUuid, fullData, hash);

        // 儲存並廣播到其他服務器
        // broadcastClipboardUpdate(playerUuid, fullData);
    }

    @Override
    protected void handleSessionNotFound(String sessionId) {
        plugin.getLogger().warning("Session not found: " + sessionId);
    }

    @Override
    protected void handleBroadcastClipboardUpdate(UUID playerUuid, byte[] data) {
        // 這個方法在 BungeeCord 中不使用，因為有專門的 broadcastClipboardUpdate 方法
    }
}
