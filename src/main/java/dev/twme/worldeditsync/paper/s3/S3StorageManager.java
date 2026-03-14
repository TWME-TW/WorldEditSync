package dev.twme.worldeditsync.paper.s3;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.logging.Logger;

import dev.twme.worldeditsync.common.crypto.MessageCipher;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;

/**
 * Encapsulates MinIO/S3 operations for clipboard storage.
 */
public class S3StorageManager {

    private static final String HASH_METADATA_KEY = "clipboard-hash";
    private static final String OBJECT_PREFIX = "clipboards/";

    private final MinioClient client;
    private final String bucket;
    private final MessageCipher cipher;
    private final Logger logger;

    public S3StorageManager(String endpoint, String accessKey, String secretKey,
                            String bucket, String region, MessageCipher cipher, Logger logger) {
        this.bucket = bucket;
        this.cipher = cipher;
        this.logger = logger;

        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey);
        if (region != null && !region.isBlank()) {
            builder.region(region);
        }
        this.client = builder.build();
    }

    /**
     * Initialize the S3 connection: check/create bucket.
     * Returns true if ready.
     */
    public boolean initialize() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                logger.info("Created S3 bucket: " + bucket);
            }
            return true;
        } catch (Exception e) {
            logger.severe("Failed to initialize S3: " + e.getMessage());
            return false;
        }
    }

    /**
     * Upload clipboard data to S3 with the hash stored in object metadata.
     */
    public boolean uploadClipboard(String playerId, byte[] data, String hash) {
        try {
            byte[] encrypted = cipher.encrypt(data);
            String objectName = OBJECT_PREFIX + playerId + ".schem";

            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(new ByteArrayInputStream(encrypted), encrypted.length, -1)
                    .userMetadata(Collections.singletonMap(HASH_METADATA_KEY, hash))
                    .build());

            return true;
        } catch (Exception e) {
            logger.severe("Failed to upload clipboard to S3 for " + playerId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Get the remote clipboard hash from S3 object metadata.
     * Returns empty string if not found.
     */
    public String getRemoteHash(String playerId) {
        try {
            String objectName = OBJECT_PREFIX + playerId + ".schem";
            StatObjectResponse stat = client.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(objectName).build());
            String hash = stat.userMetadata().get(HASH_METADATA_KEY);
            return hash != null ? hash : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Download and decrypt clipboard data from S3.
     * Returns null on error.
     */
    public byte[] downloadClipboard(String playerId) {
        try {
            String objectName = OBJECT_PREFIX + playerId + ".schem";
            try (var response = client.getObject(
                    GetObjectArgs.builder().bucket(bucket).object(objectName).build())) {
                byte[] encrypted = response.readAllBytes();
                return cipher.decrypt(encrypted);
            }
        } catch (Exception e) {
            logger.severe("Failed to download clipboard from S3 for " + playerId + ": " + e.getMessage());
            return null;
        }
    }
}
