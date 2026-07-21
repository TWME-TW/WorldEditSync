package dev.twme.worldeditsync.paper.config;

import java.nio.file.Path;

/** Immutable database-mode settings loaded from config.yml. */
public record DatabaseSettings(
        StorageType type,
        String url,
        String host,
        int port,
        String database,
        String username,
        String password,
        String table,
        String keyPrefix,
        int poolSize,
        long connectionTimeoutMs,
        int checkIntervalTicks,
        long ttlMinutes) {

    public String resolveUrl(Path pluginDataFolder) {
        if (url != null && !url.isBlank()) {
            return url.trim();
        }
        String resolvedHost = host == null || host.isBlank() ? "127.0.0.1" : host.trim();
        String resolvedDatabase = database == null || database.isBlank()
                ? "worldeditsync" : database.trim();
        return switch (type) {
            case MYSQL -> "jdbc:mysql://" + resolvedHost + ":" + resolvePort(3306)
                    + "/" + resolvedDatabase;
            case MARIADB -> "jdbc:mariadb://" + resolvedHost + ":" + resolvePort(3306)
                    + "/" + resolvedDatabase;
            case POSTGRESQL -> "jdbc:postgresql://" + resolvedHost + ":" + resolvePort(5432)
                    + "/" + resolvedDatabase;
            case SQLITE -> "jdbc:sqlite:" + pluginDataFolder.resolve("clipboards.db")
                    .toAbsolutePath().normalize();
            case REDIS -> "redis://" + resolvedHost + ":" + resolvePort(6379);
            default -> throw new IllegalArgumentException(type + " is not a database storage type");
        };
    }

    public boolean isSupported() {
        return type != null && (type.isSql() || type == StorageType.REDIS);
    }

    private int resolvePort(int defaultPort) {
        return port > 0 && port <= 65_535 ? port : defaultPort;
    }
}
