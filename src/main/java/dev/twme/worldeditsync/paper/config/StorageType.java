package dev.twme.worldeditsync.paper.config;

import java.util.Locale;

/** Supported shared-storage backends. */
public enum StorageType {
    S3,
    REDIS,
    MYSQL,
    MARIADB,
    POSTGRESQL,
    SQLITE;

    public static StorageType parse(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if ("POSTGRES".equals(normalized)) {
            normalized = "POSTGRESQL";
        }
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public boolean isSql() {
        return this == MYSQL || this == MARIADB || this == POSTGRESQL || this == SQLITE;
    }
}
