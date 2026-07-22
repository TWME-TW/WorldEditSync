package dev.twme.worldeditsync.paper.s3;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.logging.Logger;

import dev.twme.worldeditsync.common.crypto.MessageCipher;
import dev.twme.worldeditsync.common.protocol.ProtocolValidation;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;

/**
 * Encapsulates MinIO/S3 operations for clipboard storage.
 */
public class S3StorageManager {

    private static final String HASH_METADATA_KEY = "clipboard-hash";
    private static final String UPDATED_AT_METADATA_KEY = "updated-at";
    private static final String OBJECT_PREFIX = "clipboards/";

    private final String endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String region;
    private final String bucket;
    private final MessageCipher cipher;
    private final int maxClipboardSize;
    private final Logger logger;

    public S3StorageManager(String endpoint, String accessKey, String secretKey,
                            String bucket, String region, MessageCipher cipher,
                            int maxClipboardSize, Logger logger) {
        this.endpoint = endpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
        this.bucket = bucket;
        this.cipher = cipher;
        this.maxClipboardSize = maxClipboardSize;
        this.logger = logger;
    }

    /**
     * Initialize the S3 connection: check/create bucket.
     * Returns the ready client, or null on failure.
     */
    public MinioClient initialize() {
        MinioClient client = null;
        try {
            client = createClient();
            client.setTimeout(10_000L, 30_000L, 30_000L);
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                try {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                    logger.info("Created S3 bucket: " + bucket);
                } catch (Exception creationError) {
                    // Multiple Paper servers can race to create the shared bucket.
                    if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                        throw creationError;
                    }
                }
            }
            return client;
        } catch (Exception e) {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception closeError) {
                    e.addSuppressed(closeError);
                }
            }
            logger.severe("Failed to initialize S3: " + e.getMessage());
            return null;
        }
    }

    protected MinioClient createClient() {
        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey);
        if (region != null && !region.isBlank()) {
            builder.region(region);
        }
        return builder.build();
    }

    /**
     * Upload clipboard data to S3 with the hash stored in object metadata.
     */
    public void uploadClipboard(MinioClient client, String playerId,
                                byte[] data, String hash) throws Exception {
        uploadClipboard(client, playerId, data, hash, System.currentTimeMillis());
    }

    public void uploadClipboard(MinioClient client, String playerId,
                                byte[] data, String hash, long updatedAt) throws Exception {
        if (data.length <= 0 || data.length > maxClipboardSize
                || !ProtocolValidation.isSha256(hash)) {
            throw new IOException("Clipboard data or hash is invalid");
        }
        byte[] encrypted = cipher.encrypt(data);
        String objectName = objectName(playerId);

        client.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(objectName)
                .stream(new ByteArrayInputStream(encrypted), (long) encrypted.length, -1L)
                .userMetadata(java.util.Map.of(
                        HASH_METADATA_KEY, hash,
                        UPDATED_AT_METADATA_KEY, Long.toString(updatedAt)))
                .build());
    }

    /** Inspect the remote object without treating transport errors as missing data. */
    public RemoteObject getRemoteObject(MinioClient client, String playerId) throws Exception {
        try {
            StatObjectResponse stat = client.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(objectName(playerId)).build());
            String hash = stat.userMetadata().getFirst(HASH_METADATA_KEY);
            long maxPayloadSize = (long) maxClipboardSize + MessageCipher.ENCRYPTION_OVERHEAD_BYTES;
            if (hash == null || hash.isBlank()) {
                throw new IOException("S3 clipboard object has no hash metadata");
            }
            if (stat.size() <= 0 || stat.size() > maxPayloadSize) {
                throw new IOException("S3 clipboard object exceeds configured size limit");
            }
            long updatedAt = parseUpdatedAt(stat.userMetadata().getFirst(UPDATED_AT_METADATA_KEY));
            return new RemoteObject(true, hash, stat.size(), updatedAt);
        } catch (ErrorResponseException e) {
            if (isNotFound(e)) {
                return RemoteObject.missing();
            }
            throw e;
        }
    }

    /** Download and decrypt a size-bounded clipboard object. */
    public byte[] downloadClipboard(MinioClient client, String playerId,
                                    long expectedEncryptedSize) throws Exception {
        int maxPayloadSize = Math.addExact(
                maxClipboardSize, MessageCipher.ENCRYPTION_OVERHEAD_BYTES);
        try (var response = client.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectName(playerId)).build())) {
            byte[] encrypted = response.readNBytes(maxPayloadSize + 1);
            if (encrypted.length > maxPayloadSize || encrypted.length != expectedEncryptedSize) {
                throw new IOException("S3 clipboard size changed or exceeds configured limit");
            }
            byte[] decrypted = cipher.decrypt(encrypted);
            if (decrypted.length <= 0 || decrypted.length > maxClipboardSize) {
                throw new IOException("Decrypted clipboard exceeds configured size limit");
            }
            return decrypted;
        }
    }

    private String objectName(String playerId) {
        return OBJECT_PREFIX + playerId + ".schem";
    }

    private boolean isNotFound(ErrorResponseException exception) {
        if (exception.response() != null && exception.response().code() == 404) {
            return true;
        }
        if (exception.errorResponse() == null) {
            return false;
        }
        String code = exception.errorResponse().code();
        return "NoSuchKey".equals(code) || "NoSuchObject".equals(code) || "NotFound".equals(code);
    }

    private long parseUpdatedAt(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    public record RemoteObject(boolean exists, String hash, long encryptedSize, long updatedAt) {
        public static RemoteObject missing() {
            return new RemoteObject(false, "", 0L, 0L);
        }
    }
}
