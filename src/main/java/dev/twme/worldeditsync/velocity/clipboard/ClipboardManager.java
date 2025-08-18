package dev.twme.worldeditsync.velocity.clipboard;

import java.util.UUID;

import com.google.common.io.ByteArrayDataOutput;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.clipboard.BaseClipboardManager;
import dev.twme.worldeditsync.velocity.WorldEditSyncVelocity;

public class ClipboardManager extends BaseClipboardManager {
    private final WorldEditSyncVelocity plugin;

    public ClipboardManager(WorldEditSyncVelocity plugin) {
        this.plugin = plugin;
    }

    public void broadcastClipboardUpdate(UUID playerUuid, byte[] data) {
        Player targetPlayer = plugin.getServer().getPlayer(playerUuid).orElse(null);
        if (targetPlayer == null) return;

        for (RegisteredServer server : plugin.getServer().getAllServers()) {
            if (!targetPlayer.getCurrentServer().isPresent()) {
                // 玩家不在線上
                return;
            }
            if (!server.equals(targetPlayer.getCurrentServer().get().getServer())) {
                // 發送到其他服務器
                server.sendPluginMessage(
                        MinecraftChannelIdentifier.from(Constants.CHANNEL),
                        createUpdateMessage(playerUuid, data)
                );
            }
        }
    }

    private byte[] createUpdateMessage(UUID playerUuid, byte[] data) {
        ByteArrayDataOutput out =
                com.google.common.io.ByteStreams.newDataOutput();
        out.writeUTF("ClipboardUpdate");
        out.writeUTF(playerUuid.toString());
        out.write(data);
        return out.toByteArray();
    }

    @Override
    protected void handleSessionAssemblyFailure(String sessionId) {
        plugin.getLogger().warn("Failed to assemble data: {}", sessionId);
    }

    @Override
    protected void handleSessionComplete(String sessionId, UUID playerUuid, byte[] fullData, String hash) {
        plugin.getLogger().info("Finished receiving clipboard data: {}", sessionId);
        // 儲存剪貼簿
        storeClipboard(playerUuid, fullData, hash);

        // 儲存並廣播到其他服務器
        // broadcastClipboardUpdate(playerUuid, fullData);
    }

    @Override
    protected void handleSessionNotFound(String sessionId) {
        plugin.getLogger().warn("Session not found: {}", sessionId);
    }

    @Override
    protected void handleBroadcastClipboardUpdate(UUID playerUuid, byte[] data) {
        // 這個方法在 Velocity 中不使用，因為有專門的 broadcastClipboardUpdate 方法
    }
}