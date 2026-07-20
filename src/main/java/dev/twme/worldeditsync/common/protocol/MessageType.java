package dev.twme.worldeditsync.common.protocol;

public enum MessageType {

    // Paper → Proxy: Upload flow
    UPLOAD_BEGIN((byte) 0x01),
    UPLOAD_CHUNK((byte) 0x02),

    // Proxy → Paper: Upload acknowledgement
    UPLOAD_ACK((byte) 0x03),
    UPLOAD_READY((byte) 0x04),

    // Proxy → Paper: Sync on server switch
    SYNC_HASH((byte) 0x10),
    SYNC_NO_DATA((byte) 0x11),

    // Paper → Proxy: Download request
    DOWNLOAD_REQUEST((byte) 0x12),

    // Proxy → Paper: Download flow
    DOWNLOAD_BEGIN((byte) 0x13),
    DOWNLOAD_CHUNK((byte) 0x14),

    // Paper → Proxy: Download acknowledgement
    DOWNLOAD_ACK((byte) 0x15),

    // Bidirectional: Cancel
    CANCEL((byte) 0x20);

    private final byte id;

    MessageType(byte id) {
        this.id = id;
    }

    public byte getId() {
        return id;
    }

    public static MessageType fromId(byte id) {
        for (MessageType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        return null;
    }
}
