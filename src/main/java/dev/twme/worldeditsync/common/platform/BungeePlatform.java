package dev.twme.worldeditsync.common.platform;

import java.util.UUID;

import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;

public interface BungeePlatform {
    void sendPluginMessage(ServerInfo server, byte[] message);
    ProxiedPlayer getPlayer(UUID uuid);
    void logInfo(String message);
    void logWarning(String message);
}