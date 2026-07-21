package dev.twme.worldeditsync.paper.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.junit.Assume;
import org.junit.Test;

import dev.twme.worldeditsync.common.crypto.MessageCipher;
import dev.twme.worldeditsync.common.storage.StoredClipboard;
import dev.twme.worldeditsync.common.util.HashUtil;
import dev.twme.worldeditsync.paper.config.StorageType;

/** Opt-in test used against containerized server databases. */
public class JdbcClipboardStorageIntegrationTest {

    @Test
    public void externalDatabaseRoundTrip() throws Exception {
        String configuredType = System.getProperty("worldeditsync.test.jdbc.type");
        String url = System.getProperty("worldeditsync.test.jdbc.url");
        Assume.assumeTrue(configuredType != null && url != null);

        StorageType type = StorageType.parse(configuredType);
        String username = System.getProperty("worldeditsync.test.jdbc.username", "");
        String password = System.getProperty("worldeditsync.test.jdbc.password", "");
        JdbcClipboardStorage storage = new JdbcClipboardStorage(
                type, url, username, password, "worldeditsync_test_clipboards",
                2, 10_000L, 0L, new MessageCipher("integration-token"), 1_048_576);
        try {
            assertTrue(storage.initialize());
            String playerId = UUID.randomUUID().toString();
            byte[] first = new byte[128 * 1_024];
            new java.security.SecureRandom().nextBytes(first);
            storage.upload(playerId, first, HashUtil.sha256Hex(first), System.currentTimeMillis());
            StoredClipboard firstMetadata = storage.inspect(playerId);
            assertArrayEquals(first, storage.download(playerId, firstMetadata));

            byte[] second = new byte[] {4, 3, 2, 1};
            storage.upload(playerId, second, HashUtil.sha256Hex(second),
                    System.currentTimeMillis() + 1L);
            assertArrayEquals(second, storage.download(playerId, storage.inspect(playerId)));
        } finally {
            storage.close();
        }
    }
}
