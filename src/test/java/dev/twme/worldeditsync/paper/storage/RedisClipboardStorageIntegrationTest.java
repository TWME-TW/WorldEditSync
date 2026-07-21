package dev.twme.worldeditsync.paper.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Assume;
import org.junit.Test;

import dev.twme.worldeditsync.common.crypto.MessageCipher;
import dev.twme.worldeditsync.common.storage.StoredClipboard;
import dev.twme.worldeditsync.common.util.HashUtil;
import redis.clients.jedis.Jedis;

/** Opt-in test used against Redis, Valkey, or KeyDB. */
public class RedisClipboardStorageIntegrationTest {

    @Test
    public void redisRoundTripTtlAndStaleReadProtection() throws Exception {
        String url = System.getProperty("worldeditsync.test.redis.url");
        Assume.assumeTrue(url != null);

        String keyPrefix = "worldeditsync:test:" + UUID.randomUUID();
        RedisClipboardStorage storage = new RedisClipboardStorage(
                url, keyPrefix, 2, 5_000L,
                1L, new MessageCipher("integration-token"), 1_048_576,
                java.util.logging.Logger.getAnonymousLogger());
        try {
            CountDownLatch notification = new CountDownLatch(1);
            storage.setUpdateListener(changedPlayer -> notification.countDown());
            assertTrue(storage.initialize());
            String playerId = UUID.randomUUID().toString();
            assertFalse(storage.inspect(playerId).exists());
            byte[] first = new byte[128 * 1_024];
            new java.security.SecureRandom().nextBytes(first);
            storage.upload(playerId, first, HashUtil.sha256Hex(first), System.currentTimeMillis());
            assertTrue(notification.await(2L, TimeUnit.SECONDS));
            StoredClipboard stale = storage.inspect(playerId);
            assertArrayEquals(first, storage.download(playerId, stale));

            byte[] second = new byte[] {7, 8, 9};
            storage.upload(playerId, second, HashUtil.sha256Hex(second),
                    System.currentTimeMillis() + 1L);
            assertThrows(IOException.class, () -> storage.download(playerId, stale));
            StoredClipboard current = storage.inspect(playerId);
            assertArrayEquals(second, storage.download(playerId, current));

            try (Jedis jedis = new Jedis(URI.create(url))) {
                jedis.hset(
                        (keyPrefix + ":clipboard:" + playerId).getBytes(StandardCharsets.UTF_8),
                        "data".getBytes(StandardCharsets.UTF_8),
                        new byte[(int) current.storedSize() + 1]);
            }
            assertThrows(IOException.class, () -> storage.download(playerId, current));
        } finally {
            storage.close();
        }
    }
}
