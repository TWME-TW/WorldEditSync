package dev.twme.worldeditsync.paper.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.UUID;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import dev.twme.worldeditsync.common.crypto.MessageCipher;
import dev.twme.worldeditsync.common.storage.StoredClipboard;
import dev.twme.worldeditsync.common.util.HashUtil;
import dev.twme.worldeditsync.paper.config.StorageType;

public class JdbcClipboardStorageTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void sqlitePersistsEncryptedClipboardAcrossConnections() throws Exception {
        Path database = temporaryFolder.newFile("clipboards.db").toPath();
        String playerId = UUID.randomUUID().toString();
        byte[] data = "sqlite clipboard".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = HashUtil.sha256Hex(data);
        long updatedAt = System.currentTimeMillis();

        JdbcClipboardStorage writer = storage(database, "shared-token", 0L);
        assertTrue(writer.initialize());
        assertFalse(writer.inspect(playerId).exists());
        writer.upload(playerId, data, hash, updatedAt);
        StoredClipboard stored = writer.inspect(playerId);
        assertTrue(stored.exists());
        assertTrue(stored.storedSize() > data.length);
        writer.close();

        JdbcClipboardStorage reader = storage(database, "shared-token", 0L);
        assertTrue(reader.initialize());
        assertArrayEquals(data, reader.download(playerId, reader.inspect(playerId)));
        reader.close();
    }

    @Test
    public void rejectsStaleMetadataAfterConcurrentReplacement() throws Exception {
        Path database = temporaryFolder.newFile("stale.db").toPath();
        JdbcClipboardStorage storage = storage(database, "token", 0L);
        storage.initialize();
        String playerId = UUID.randomUUID().toString();
        byte[] first = new byte[] {1, 2, 3};
        byte[] second = new byte[] {4, 5, 6};
        storage.upload(playerId, first, HashUtil.sha256Hex(first), 1_000L);
        StoredClipboard stale = storage.inspect(playerId);
        storage.upload(playerId, second, HashUtil.sha256Hex(second), 2_000L);

        assertThrows(IOException.class, () -> storage.download(playerId, stale));
        assertArrayEquals(second, storage.download(playerId, storage.inspect(playerId)));
        storage.close();
    }

    @Test
    public void rejectsMismatchedEncryptionToken() throws Exception {
        Path database = temporaryFolder.newFile("encrypted.db").toPath();
        String playerId = UUID.randomUUID().toString();
        byte[] data = new byte[] {9, 8, 7};
        JdbcClipboardStorage writer = storage(database, "correct", 0L);
        writer.initialize();
        writer.upload(playerId, data, HashUtil.sha256Hex(data), System.currentTimeMillis());
        StoredClipboard stored = writer.inspect(playerId);
        writer.close();

        JdbcClipboardStorage reader = storage(database, "incorrect", 0L);
        reader.initialize();
        assertThrows(SecurityException.class, () -> reader.download(playerId, stored));
        reader.close();
    }

    @Test
    public void lazilyDeletesExpiredClipboard() throws Exception {
        Path database = temporaryFolder.newFile("ttl.db").toPath();
        JdbcClipboardStorage storage = storage(database, "token", 1L);
        storage.initialize();
        String playerId = UUID.randomUUID().toString();
        byte[] data = new byte[] {1};
        storage.upload(playerId, data, HashUtil.sha256Hex(data),
                System.currentTimeMillis() - 120_000L);

        assertFalse(storage.inspect(playerId).exists());
        assertFalse(storage.inspect(playerId).exists());
        storage.close();
    }

    @Test
    public void rejectsPayloadLargerThanItsValidatedMetadata() throws Exception {
        Path database = temporaryFolder.newFile("oversized.db").toPath();
        JdbcClipboardStorage storage = storage(database, "token", 0L);
        storage.initialize();
        String playerId = UUID.randomUUID().toString();
        byte[] data = new byte[] {1, 2, 3};
        storage.upload(playerId, data, HashUtil.sha256Hex(data), System.currentTimeMillis());
        StoredClipboard expected = storage.inspect(playerId);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE worldeditsync_clipboards SET payload = ? WHERE player_id = ?")) {
            statement.setBytes(1, new byte[1_024 + MessageCipher.ENCRYPTION_OVERHEAD_BYTES + 1]);
            statement.setString(2, playerId);
            statement.executeUpdate();
        }

        assertThrows(IOException.class, () -> storage.download(playerId, expected));
        storage.close();
    }

    @Test
    public void validatesSqlTableName() throws Exception {
        Path database = temporaryFolder.newFile("invalid.db").toPath();
        assertThrows(IllegalArgumentException.class, () -> new JdbcClipboardStorage(
                StorageType.SQLITE,
                "jdbc:sqlite:" + database,
                "",
                "",
                "clipboards; DROP TABLE users",
                1,
                1_000L,
                0L,
                new MessageCipher("token"),
                1_024));
    }

    private JdbcClipboardStorage storage(Path database, String token, long ttlMinutes) {
        return new JdbcClipboardStorage(
                StorageType.SQLITE,
                "jdbc:sqlite:" + database,
                "",
                "",
                "worldeditsync_clipboards",
                4,
                2_000L,
                ttlMinutes,
                new MessageCipher(token),
                1_024);
    }
}
