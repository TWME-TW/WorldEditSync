package dev.twme.worldeditsync.paper.storage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

import dev.twme.worldeditsync.common.crypto.MessageCipher;
import dev.twme.worldeditsync.common.protocol.ProtocolValidation;
import dev.twme.worldeditsync.common.storage.ClipboardStorage;
import dev.twme.worldeditsync.common.storage.StoredClipboard;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.BinaryJedisPubSub;

/** Redis-compatible storage for Redis, Valkey, and KeyDB. */
public final class RedisClipboardStorage implements ClipboardStorage {

    private static final byte[] HASH_FIELD = bytes("hash");
    private static final byte[] DATA_FIELD = bytes("data");
    private static final byte[] UPDATED_AT_FIELD = bytes("updated_at");
    private static final byte[] SIZE_FIELD = bytes("size");
    private static final byte[] WRITE_SCRIPT = bytes(
            "redis.call('HSET', KEYS[1], 'hash', ARGV[1], 'updated_at', ARGV[2], "
                    + "'size', ARGV[3], 'data', ARGV[4]); "
                    + "if tonumber(ARGV[5]) > 0 then redis.call('PEXPIRE', KEYS[1], ARGV[5]) "
                    + "else redis.call('PERSIST', KEYS[1]) end; "
                    + "redis.call('PUBLISH', KEYS[2], ARGV[6]); return 1");
    private static final byte[] READ_SCRIPT = bytes(
            "local h = redis.call('HGET', KEYS[1], 'hash'); "
                    + "local t = redis.call('HGET', KEYS[1], 'updated_at'); "
                    + "local s = redis.call('HGET', KEYS[1], 'size'); "
                    + "if not h or h ~= ARGV[1] or t ~= ARGV[2] or s ~= ARGV[3] then return nil end; "
                    + "local d = redis.call('HGET', KEYS[1], 'data'); "
                    + "if not d or string.len(d) ~= tonumber(ARGV[3]) then return nil end; return d");

    private final String url;
    private final String keyPrefix;
    private final int poolSize;
    private final int connectionTimeoutMs;
    private final long ttlMillis;
    private final MessageCipher cipher;
    private final int maxClipboardSize;
    private final Logger logger;
    private final AtomicBoolean subscriberRunning = new AtomicBoolean();
    private volatile Consumer<String> updateListener = ignored -> { };
    private volatile CountDownLatch subscriberReady = new CountDownLatch(1);
    private volatile ExecutorService subscriberExecutor;
    private volatile Jedis subscriberClient;
    private volatile BinaryJedisPubSub subscriber;
    private volatile JedisPool pool;

    public RedisClipboardStorage(String url, String keyPrefix, int poolSize,
                                 long connectionTimeoutMs, long ttlMinutes,
                                 MessageCipher cipher, int maxClipboardSize, Logger logger) {
        if (keyPrefix == null || !keyPrefix.matches("[A-Za-z0-9:_-]{1,64}")) {
            throw new IllegalArgumentException(
                    "Redis key-prefix must contain 1-64 letters, numbers, colons, underscores, or hyphens");
        }
        java.net.URI redisUri = java.net.URI.create(url);
        if (!"redis".equalsIgnoreCase(redisUri.getScheme())
                && !"rediss".equalsIgnoreCase(redisUri.getScheme())) {
            throw new IllegalArgumentException("Redis URL must use redis:// or rediss://");
        }
        this.url = redisUri.toString();
        this.keyPrefix = keyPrefix;
        this.poolSize = Math.max(1, Math.min(16, poolSize));
        this.connectionTimeoutMs = (int) Math.max(1_000L,
                Math.min(Integer.MAX_VALUE, connectionTimeoutMs));
        this.ttlMillis = ttlMinutes <= 0 ? 0L
                : Math.min(ttlMinutes, Long.MAX_VALUE / 60_000L) * 60_000L;
        this.cipher = cipher;
        this.maxClipboardSize = maxClipboardSize;
        this.logger = logger;
    }

    @Override
    public boolean initialize() {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(poolSize);
        config.setMaxIdle(Math.min(2, poolSize));
        config.setMinIdle(0);
        config.setTestOnBorrow(true);
        JedisPool initialized = new JedisPool(
                config, java.net.URI.create(url), connectionTimeoutMs, 30_000);
        try (Jedis jedis = initialized.getResource()) {
            jedis.ping();
        } catch (RuntimeException e) {
            initialized.close();
            throw e;
        }
        pool = initialized;
        startSubscriber();
        return true;
    }

    @Override
    public StoredClipboard inspect(String playerId) throws Exception {
        try (Jedis jedis = requirePool().getResource()) {
            List<byte[]> fields = jedis.hmget(key(playerId), HASH_FIELD, SIZE_FIELD, UPDATED_AT_FIELD);
            if (fields.size() != 3 || fields.get(0) == null) {
                return StoredClipboard.missing();
            }
            String hash = string(fields.get(0));
            long size = parsePositiveLong(fields.get(1), "stored size");
            long updatedAt = parsePositiveLong(fields.get(2), "updated timestamp");
            validateMetadata(hash, size);
            return new StoredClipboard(true, hash, size, updatedAt);
        }
    }

    @Override
    public void upload(String playerId, byte[] data, String hash, long updatedAt) throws Exception {
        validatePlaintext(data, hash);
        byte[] encrypted = cipher.encrypt(data);
        validateStoredSize(encrypted.length);
        try (Jedis jedis = requirePool().getResource()) {
            jedis.eval(WRITE_SCRIPT,
                    List.of(key(playerId), updateChannel()),
                    List.of(bytes(hash), bytes(Long.toString(updatedAt)),
                            bytes(Integer.toString(encrypted.length)), encrypted,
                            bytes(Long.toString(ttlMillis)), bytes(playerId)));
        }
    }

    @Override
    public byte[] download(String playerId, StoredClipboard expected) throws Exception {
        validateMetadata(expected.hash(), expected.storedSize());
        Object result;
        try (Jedis jedis = requirePool().getResource()) {
            result = jedis.eval(READ_SCRIPT,
                    List.of(key(playerId)),
                    List.of(bytes(expected.hash()), bytes(Long.toString(expected.updatedAt())),
                            bytes(Long.toString(expected.storedSize()))));
        }
        if (!(result instanceof byte[] encrypted)) {
            throw new java.io.IOException("Redis clipboard changed while it was being downloaded");
        }
        if (encrypted.length != expected.storedSize()) {
            throw new java.io.IOException("Redis clipboard size does not match its metadata");
        }
        byte[] data = cipher.decrypt(encrypted);
        if (data.length <= 0 || data.length > maxClipboardSize) {
            throw new java.io.IOException("Redis clipboard exceeds configured size limit");
        }
        return data;
    }

    @Override
    public String description() {
        return "Redis-compatible";
    }

    @Override
    public void setUpdateListener(Consumer<String> listener) {
        updateListener = listener == null ? ignored -> { } : listener;
    }

    @Override
    public void close() {
        subscriberRunning.set(false);
        BinaryJedisPubSub activeSubscriber = subscriber;
        if (activeSubscriber != null) {
            try {
                activeSubscriber.unsubscribe();
            } catch (RuntimeException ignored) {
            }
        }
        Jedis activeSubscriberClient = subscriberClient;
        if (activeSubscriberClient != null) {
            activeSubscriberClient.close();
        }
        ExecutorService activeExecutor = subscriberExecutor;
        if (activeExecutor != null) {
            activeExecutor.shutdownNow();
            try {
                activeExecutor.awaitTermination(2L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        subscriber = null;
        subscriberClient = null;
        subscriberExecutor = null;
        JedisPool activePool = pool;
        pool = null;
        if (activePool != null) {
            activePool.close();
        }
    }

    private JedisPool requirePool() {
        JedisPool activePool = pool;
        if (activePool == null || activePool.isClosed()) {
            throw new IllegalStateException("Redis storage is not initialized");
        }
        return activePool;
    }

    private byte[] key(String playerId) {
        return bytes(keyPrefix + ":clipboard:" + playerId);
    }

    private byte[] updateChannel() {
        return bytes(keyPrefix + ":updates");
    }

    private void startSubscriber() {
        if (!subscriberRunning.compareAndSet(false, true)) {
            return;
        }
        subscriberReady = new CountDownLatch(1);
        subscriberExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "WorldEditSync-Redis-Subscriber");
            thread.setDaemon(true);
            return thread;
        });
        subscriberExecutor.execute(this::runSubscriber);
        try {
            if (!subscriberReady.await(connectionTimeoutMs, TimeUnit.MILLISECONDS)) {
                logger.warning("Redis update subscriber did not become ready before the timeout; "
                        + "periodic polling remains active.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runSubscriber() {
        while (subscriberRunning.get()) {
            try (Jedis client = new Jedis(
                    java.net.URI.create(url), connectionTimeoutMs, 30_000)) {
                subscriberClient = client;
                BinaryJedisPubSub listener = new BinaryJedisPubSub() {
                    @Override
                    public void onMessage(byte[] channel, byte[] message) {
                        if (subscriberRunning.get()) {
                            try {
                                updateListener.accept(string(message));
                            } catch (RuntimeException e) {
                                logger.warning("Redis update listener failed: " + e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void onSubscribe(byte[] channel, int subscribedChannels) {
                        subscriberReady.countDown();
                    }
                };
                subscriber = listener;
                client.subscribe(listener, updateChannel());
            } catch (RuntimeException e) {
                if (subscriberRunning.get()) {
                    logger.fine("Redis update subscriber reconnecting: " + e.getMessage());
                    try {
                        Thread.sleep(1_000L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } finally {
                subscriber = null;
                subscriberClient = null;
            }
        }
    }

    private void validatePlaintext(byte[] data, String hash) throws java.io.IOException {
        if (data == null || data.length <= 0 || data.length > maxClipboardSize
                || !ProtocolValidation.isSha256(hash)) {
            throw new java.io.IOException("Clipboard data or hash is invalid");
        }
    }

    private void validateMetadata(String hash, long size) throws java.io.IOException {
        if (!ProtocolValidation.isSha256(hash)) {
            throw new java.io.IOException("Redis clipboard hash is invalid");
        }
        validateStoredSize(size);
    }

    private void validateStoredSize(long size) throws java.io.IOException {
        long maximum = (long) maxClipboardSize + MessageCipher.ENCRYPTION_OVERHEAD_BYTES;
        if (size <= 0 || size > maximum) {
            throw new java.io.IOException("Redis clipboard exceeds configured size limit");
        }
    }

    private long parsePositiveLong(byte[] value, String field) throws java.io.IOException {
        try {
            long parsed = Long.parseLong(string(value));
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (RuntimeException e) {
            throw new java.io.IOException("Redis clipboard " + field + " is invalid", e);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String string(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
