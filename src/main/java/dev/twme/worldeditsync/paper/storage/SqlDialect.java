package dev.twme.worldeditsync.paper.storage;

import dev.twme.worldeditsync.paper.config.StorageType;

/** SQL differences kept behind a small, testable dialect boundary. */
public enum SqlDialect {
    MYSQL("LONGBLOB", "INSERT INTO %s (player_id, clipboard_hash, updated_at, payload_size, payload) "
            + "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE "
            + "clipboard_hash = VALUES(clipboard_hash), updated_at = VALUES(updated_at), "
            + "payload_size = VALUES(payload_size), payload = VALUES(payload)"),
    POSTGRESQL("BYTEA", "INSERT INTO %s (player_id, clipboard_hash, updated_at, payload_size, payload) "
            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (player_id) DO UPDATE SET "
            + "clipboard_hash = EXCLUDED.clipboard_hash, updated_at = EXCLUDED.updated_at, "
            + "payload_size = EXCLUDED.payload_size, payload = EXCLUDED.payload"),
    SQLITE("BLOB", "INSERT INTO %s (player_id, clipboard_hash, updated_at, payload_size, payload) "
            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT(player_id) DO UPDATE SET "
            + "clipboard_hash = excluded.clipboard_hash, updated_at = excluded.updated_at, "
            + "payload_size = excluded.payload_size, payload = excluded.payload");

    private final String blobType;
    private final String upsertTemplate;

    SqlDialect(String blobType, String upsertTemplate) {
        this.blobType = blobType;
        this.upsertTemplate = upsertTemplate;
    }

    public static SqlDialect forType(StorageType type) {
        return switch (type) {
            case MYSQL, MARIADB -> MYSQL;
            case POSTGRESQL -> POSTGRESQL;
            case SQLITE -> SQLITE;
            default -> throw new IllegalArgumentException(type + " is not a SQL storage type");
        };
    }

    public String createTable(String table) {
        return "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "player_id VARCHAR(36) PRIMARY KEY, "
                + "clipboard_hash VARCHAR(64) NOT NULL, "
                + "updated_at BIGINT NOT NULL, "
                + "payload_size BIGINT NOT NULL, "
                + "payload " + blobType + " NOT NULL)";
    }

    public String upsert(String table) {
        return upsertTemplate.formatted(table);
    }
}
