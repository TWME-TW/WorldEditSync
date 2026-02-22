package dev.twme.worldeditsync.paper.s3;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import dev.twme.worldeditsync.common.crypto.MessageCipher;
import dev.twme.worldeditsync.paper.WorldEditSyncPaper;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;

/**
 * S3 儲存管理器，使用 MinIO SDK 與 S3 相容的儲存服務互動。
 * 負責：
 * - 初始化 MinIO 客戶端
 * - 上傳剪貼簿資料到 S3
 * - 從 S3 下載剪貼簿資料
 * - 取得 S3 上剪貼簿的 hash (存於 user-metadata)
 */
public class S3StorageManager {
    private final WorldEditSyncPaper plugin;
    private final MinioClient minioClient;
    private final String bucket;

    private static final String CLIPBOARD_PREFIX = "clipboards/";
    private static final String HASH_METADATA_KEY = "clipboard-hash";

    public S3StorageManager(WorldEditSyncPaper plugin, String endpoint, String accessKey, String secretKey, String bucket, String region) {
        this.plugin = plugin;
        this.bucket = bucket;

        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey);

        if (region != null && !region.isEmpty()) {
            builder.region(region);
        }

        this.minioClient = builder.build();
    }

    /**
     * 初始化 S3 連線，確認 bucket 存在，不存在則建立。
     *
     * @return true 如果初始化成功
     */
    public boolean initialize() {
        try {
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                plugin.getLogger().info("Created S3 bucket: " + bucket);
            }
            plugin.getLogger().info("S3 storage connected successfully (bucket: " + bucket + ")");
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialize S3 storage: " + e.getMessage());
            return false;
        }
    }

    /**
     * 上傳剪貼簿資料到 S3。
     * 若 token 有設定，資料會先以 AES-256-GCM 加密再上傳。
     *
     * @param playerUuid 玩家 UUID
     * @param data       序列化後的剪貼簿資料
     * @param hash       資料的 SHA-256 hash（明文資料的 hash）
     */
    public void uploadClipboard(UUID playerUuid, byte[] data, String hash) {
        String objectName = CLIPBOARD_PREFIX + playerUuid.toString() + ".schem";

        // 若有 token，加密資料
        MessageCipher cipher = plugin.getMessageCipher();
        byte[] payload = cipher.encrypt(data);

        try (ByteArrayInputStream bais = new ByteArrayInputStream(payload)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .stream(bais, payload.length, -1)
                            .contentType("application/octet-stream")
                            .userMetadata(java.util.Map.of(HASH_METADATA_KEY, hash))
                            .build()
            );
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to upload clipboard for " + playerUuid + ": " + e.getMessage());
        }
    }

    /**
     * 從 S3 取得剪貼簿的 hash (存於 user-metadata)。
     *
     * @param playerUuid 玩家 UUID
     * @return hash 字串，若不存在回傳空字串
     */
    public String getRemoteHash(UUID playerUuid) {
        String objectName = CLIPBOARD_PREFIX + playerUuid.toString() + ".schem";

        try {
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .build()
            );
            String hash = stat.userMetadata().get(HASH_METADATA_KEY);
            return hash != null ? hash : "";
        } catch (ErrorResponseException e) {
            // 物件不存在
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return "";
            }
            plugin.getLogger().warning("Failed to get remote hash for " + playerUuid + ": " + e.getMessage());
            return "";
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to get remote hash for " + playerUuid + ": " + e.getMessage());
            return "";
        }
    }

    /**
     * 從 S3 下載剪貼簿資料。
     * 若 token 有設定，下載後會自動解密。
     *
     * @param playerUuid 玩家 UUID
     * @return 解密後的序列化剪貼簿資料，若失敗回傳 null
     */
    public byte[] downloadClipboard(UUID playerUuid) {
        String objectName = CLIPBOARD_PREFIX + playerUuid.toString() + ".schem";

        try (GetObjectResponse response = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectName)
                        .build()
        )) {
            byte[] raw = response.readAllBytes();

            // 若有 token，解密資料
            MessageCipher cipher = plugin.getMessageCipher();
            return cipher.decrypt(raw);
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return null;
            }
            plugin.getLogger().warning("Failed to download clipboard for " + playerUuid + ": " + e.getMessage());
            return null;
        } catch (SecurityException e) {
            plugin.getLogger().warning("Failed to decrypt clipboard for " + playerUuid + ": " + e.getMessage());
            return null;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to download clipboard for " + playerUuid + ": " + e.getMessage());
            return null;
        }
    }
}
