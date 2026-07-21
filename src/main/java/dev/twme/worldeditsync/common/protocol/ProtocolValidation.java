package dev.twme.worldeditsync.common.protocol;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.UUID;

import dev.twme.worldeditsync.common.Constants;

public final class ProtocolValidation {

    private ProtocolValidation() {
    }

    public static boolean isSessionId(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean isSha256(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isReason(String value) {
        return value != null && value.length() <= Constants.MAX_CANCEL_REASON_LENGTH;
    }

    public static boolean exhausted(DataInputStream input) throws IOException {
        return input.available() == 0;
    }
}
