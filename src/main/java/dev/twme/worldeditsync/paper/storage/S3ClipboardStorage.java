package dev.twme.worldeditsync.paper.storage;

import dev.twme.worldeditsync.common.storage.ClipboardStorage;
import dev.twme.worldeditsync.common.storage.StoredClipboard;
import dev.twme.worldeditsync.paper.s3.S3StorageManager;
import io.minio.MinioClient;

/** Adapts the S3 implementation to the shared storage contract. */
public final class S3ClipboardStorage implements ClipboardStorage {

    private final S3StorageManager storage;
    private volatile MinioClient client;

    public S3ClipboardStorage(S3StorageManager storage) {
        this.storage = storage;
    }

    @Override
    public boolean initialize() {
        client = storage.initialize();
        return client != null;
    }

    @Override
    public StoredClipboard inspect(String playerId) throws Exception {
        S3StorageManager.RemoteObject remote = storage.getRemoteObject(requireClient(), playerId);
        return remote.exists()
                ? new StoredClipboard(true, remote.hash(), remote.encryptedSize(), remote.updatedAt())
                : StoredClipboard.missing();
    }

    @Override
    public void upload(String playerId, byte[] data, String hash, long updatedAt) throws Exception {
        storage.uploadClipboard(requireClient(), playerId, data, hash, updatedAt);
    }

    @Override
    public byte[] download(String playerId, StoredClipboard expected) throws Exception {
        return storage.downloadClipboard(requireClient(), playerId, expected.storedSize());
    }

    @Override
    public String description() {
        return "S3";
    }

    @Override
    public void close() {
        client = null;
    }

    private MinioClient requireClient() {
        MinioClient activeClient = client;
        if (activeClient == null) {
            throw new IllegalStateException("S3 storage is not initialized");
        }
        return activeClient;
    }
}
