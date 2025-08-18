package dev.twme.worldeditsync.common.platform;

import java.util.Optional;
import java.util.UUID;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

public interface VelocityPlatform {
    Optional<Player> getPlayer(UUID uuid);
    void sendPluginMessage(RegisteredServer server, byte[] message);
    void logInfo(String message);
    void logWarning(String message);
}