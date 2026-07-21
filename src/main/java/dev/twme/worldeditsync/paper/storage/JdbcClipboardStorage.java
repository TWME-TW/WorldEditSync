package dev.twme.worldeditsync.paper.storage;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import dev.twme.worldeditsync.common.crypto.MessageCipher;
import dev.twme.worldeditsync.common.protocol.ProtocolValidation;
import dev.twme.worldeditsync.common.storage.ClipboardStorage;
import dev.twme.worldeditsync.common.storage.StoredClipboard;
import dev.twme.worldeditsync.paper.config.StorageType;

/** JDBC storage shared by MySQL, MariaDB, PostgreSQL, and SQLite. */
public final class JdbcClipboardStorage implements ClipboardStorage {

    private static final String TABLE_PATTERN = "[A-Za-z][A-Za-z0-9_]{0,62}";

    private final StorageType type;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String table;
    private final int poolSize;
    private final long connectionTimeoutMs;
    private final long ttlMinutes;
    private final MessageCipher cipher;
    private final int maxClipboardSize;
    private final SqlDialect dialect;
    private volatile HikariDataSource dataSource;

    public JdbcClipboardStorage(StorageType type, String jdbcUrl, String username, String password,
                                String table, int poolSize, long connectionTimeoutMs,
                                long ttlMinutes, MessageCipher cipher, int maxClipboardSize) {
        if (!type.isSql()) {
            throw new IllegalArgumentException(type + " is not a SQL storage type");
        }
        if (table == null || !table.matches(TABLE_PATTERN)) {
            throw new IllegalArgumentException("SQL table name must match " + TABLE_PATTERN);
        }
        this.type = type;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.table = table;
        this.poolSize = Math.max(1, Math.min(16, poolSize));
        this.connectionTimeoutMs = Math.max(1_000L, connectionTimeoutMs);
        this.ttlMinutes = Math.max(0L, ttlMinutes);
        this.cipher = cipher;
        this.maxClipboardSize = maxClipboardSize;
        this.dialect = SqlDialect.forType(type);
    }

    @Override
    public boolean initialize() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setPoolName("WorldEditSync-" + type.name());
        config.setJdbcUrl(jdbcUrl);
        config.setDriverClassName(driverClassName());
        if (username != null && !username.isBlank()) {
            config.setUsername(username);
        }
        if (password != null && !password.isEmpty()) {
            config.setPassword(password);
        }
        config.setMaximumPoolSize(type == StorageType.SQLITE ? 1 : poolSize);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(connectionTimeoutMs);
        config.setInitializationFailTimeout(connectionTimeoutMs);
        config.setAutoCommit(true);
        if (type == StorageType.SQLITE) {
            config.setConnectionInitSql("PRAGMA busy_timeout = 10000");
        }
        HikariDataSource initialized = new HikariDataSource(config);
        try (Connection connection = initialized.getConnection();
             Statement statement = connection.createStatement()) {
            if (type == StorageType.SQLITE) {
                statement.execute("PRAGMA journal_mode = WAL");
            }
            statement.executeUpdate(dialect.createTable(table));
        } catch (SQLException e) {
            initialized.close();
            throw e;
        }
        dataSource = initialized;
        return true;
    }

    @Override
    public StoredClipboard inspect(String playerId) throws Exception {
        String sql = "SELECT clipboard_hash, payload_size, updated_at FROM " + table
                + " WHERE player_id = ?";
        try (Connection connection = requireDataSource().getConnection()) {
            StoredClipboard stored;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return StoredClipboard.missing();
                    }
                    String hash = result.getString(1);
                    long storedSize = result.getLong(2);
                    long updatedAt = result.getLong(3);
                    validateMetadata(hash, storedSize, updatedAt);
                    stored = new StoredClipboard(true, hash, storedSize, updatedAt);
                }
            }
            if (isExpired(stored.updatedAt())) {
                deleteExpired(connection, playerId, stored);
                return StoredClipboard.missing();
            }
            return stored;
        }
    }

    @Override
    public void upload(String playerId, byte[] data, String hash, long updatedAt) throws Exception {
        validatePlaintext(data, hash);
        byte[] encrypted = cipher.encrypt(data);
        validateMetadata(hash, encrypted.length, updatedAt);
        try (Connection connection = requireDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(dialect.upsert(table))) {
            statement.setString(1, playerId);
            statement.setString(2, hash);
            statement.setLong(3, updatedAt);
            statement.setLong(4, encrypted.length);
            statement.setBytes(5, encrypted);
            statement.executeUpdate();
        }
    }

    @Override
    public byte[] download(String playerId, StoredClipboard expected) throws Exception {
        validateMetadata(expected.hash(), expected.storedSize(), expected.updatedAt());
        String sql = "SELECT payload FROM " + table
                + " WHERE player_id = ? AND clipboard_hash = ? AND updated_at = ? AND payload_size = ?";
        try (Connection connection = requireDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId);
            statement.setString(2, expected.hash());
            statement.setLong(3, expected.updatedAt());
            statement.setLong(4, expected.storedSize());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IOException("SQL clipboard changed while it was being downloaded");
                }
                int maximum = Math.addExact(
                        maxClipboardSize, MessageCipher.ENCRYPTION_OVERHEAD_BYTES);
                byte[] encrypted;
                try (InputStream payload = result.getBinaryStream(1)) {
                    if (payload == null) {
                        throw new IOException("SQL clipboard payload is missing");
                    }
                    encrypted = payload.readNBytes(maximum + 1);
                }
                if (encrypted.length > maximum || encrypted.length != expected.storedSize()) {
                    throw new IOException("SQL clipboard size changed or exceeds configured limit");
                }
                byte[] data = cipher.decrypt(encrypted);
                if (data.length <= 0 || data.length > maxClipboardSize) {
                    throw new IOException("SQL clipboard exceeds configured size limit");
                }
                return data;
            }
        }
    }

    @Override
    public String description() {
        return type.name();
    }

    @Override
    public void close() {
        HikariDataSource activeDataSource = dataSource;
        dataSource = null;
        if (activeDataSource != null) {
            activeDataSource.close();
        }
    }

    private HikariDataSource requireDataSource() {
        HikariDataSource activeDataSource = dataSource;
        if (activeDataSource == null || activeDataSource.isClosed()) {
            throw new IllegalStateException("SQL storage is not initialized");
        }
        return activeDataSource;
    }

    private String driverClassName() {
        return switch (type) {
            case MYSQL -> "com.mysql.cj.jdbc.Driver";
            case MARIADB -> "org.mariadb.jdbc.Driver";
            case POSTGRESQL -> "org.postgresql.Driver";
            case SQLITE -> "org.sqlite.JDBC";
            default -> throw new IllegalStateException(type + " is not a SQL storage type");
        };
    }

    private void deleteExpired(Connection connection, String playerId,
                               StoredClipboard expected) throws SQLException {
        String sql = "DELETE FROM " + table + " WHERE player_id = ? AND clipboard_hash = ?"
                + " AND updated_at = ? AND payload_size = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId);
            statement.setString(2, expected.hash());
            statement.setLong(3, expected.updatedAt());
            statement.setLong(4, expected.storedSize());
            statement.executeUpdate();
        }
    }

    private boolean isExpired(long updatedAt) {
        if (ttlMinutes <= 0L) {
            return false;
        }
        long cutoff;
        try {
            cutoff = Math.subtractExact(System.currentTimeMillis(), Math.multiplyExact(ttlMinutes, 60_000L));
        } catch (ArithmeticException ignored) {
            return false;
        }
        return updatedAt < cutoff;
    }

    private void validatePlaintext(byte[] data, String hash) throws IOException {
        if (data == null || data.length <= 0 || data.length > maxClipboardSize
                || !ProtocolValidation.isSha256(hash)) {
            throw new IOException("Clipboard data or hash is invalid");
        }
    }

    private void validateMetadata(String hash, long storedSize, long updatedAt) throws IOException {
        long maximum = (long) maxClipboardSize + MessageCipher.ENCRYPTION_OVERHEAD_BYTES;
        if (!ProtocolValidation.isSha256(hash) || storedSize <= 0 || storedSize > maximum
                || updatedAt <= 0) {
            throw new IOException("SQL clipboard metadata is invalid");
        }
    }
}
