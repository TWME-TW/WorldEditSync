package dev.twme.worldeditsync.common.transfer;

import java.util.UUID;

public class MessageEventData {
    private final UUID playerUuid;
    private final byte[] data;

    public MessageEventData(UUID playerUuid, String sessionId, int chunkIndex, byte[] data) {
        this.playerUuid = playerUuid;
        this.data = data;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public byte[] getData() {
        return data;
    }
}
