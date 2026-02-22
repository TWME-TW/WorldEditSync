package dev.twme.worldeditsync.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 使用 AES-256-GCM 對 Plugin Message 進行加密與解密。
 * <p>
 * 加密格式：[12 bytes IV][encrypted payload + 16 bytes GCM tag]
 * <p>
 * 使用方式：雙方使用相同的 token 建立 MessageCipher，
 * 發送方呼叫 {@link #encrypt(byte[])}，接收方呼叫 {@link #decrypt(byte[])}。
 * 若 token 為空或 null，則不進行加密（明文模式）。
 */
public class MessageCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKey secretKey;
    private final boolean enabled;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 建立一個 MessageCipher。
     *
     * @param token 共用密鑰 token。若為 null 或空字串，加密功能將被停用。
     */
    public MessageCipher(String token) {
        if (token == null || token.isBlank()) {
            this.secretKey = null;
            this.enabled = false;
        } else {
            this.secretKey = deriveKey(token);
            this.enabled = true;
        }
    }

    /**
     * 加密訊息。
     *
     * @param plainData 明文資料
     * @return 加密後的資料（IV + 密文 + GCM tag），或在未啟用時回傳原始資料
     */
    public byte[] encrypt(byte[] plainData) {
        if (!enabled) return plainData;

        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            byte[] encrypted = cipher.doFinal(plainData);

            // [IV (12 bytes)][encrypted + tag]
            ByteBuffer buffer = ByteBuffer.allocate(IV_LENGTH + encrypted.length);
            buffer.put(iv);
            buffer.put(encrypted);
            return buffer.array();
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt message", e);
        }
    }

    /**
     * 解密訊息。
     *
     * @param encryptedData 加密後的資料
     * @return 明文資料，或在未啟用時回傳原始資料
     * @throws SecurityException 若解密失敗（token 不匹配或資料被篡改）
     */
    public byte[] decrypt(byte[] encryptedData) {
        if (!enabled) return encryptedData;

        try {
            if (encryptedData.length < IV_LENGTH + TAG_LENGTH_BITS / 8) {
                throw new SecurityException("Message too short to be a valid encrypted message");
            }

            ByteBuffer buffer = ByteBuffer.wrap(encryptedData);
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);

            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            return cipher.doFinal(ciphertext);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("Failed to decrypt message - invalid token or corrupted data", e);
        }
    }

    /**
     * 是否已啟用加密。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 從 token 字串衍生 AES-256 金鑰。
     * 使用 SHA-256 確保金鑰長度固定為 32 bytes。
     */
    private static SecretKey deriveKey(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive encryption key", e);
        }
    }
}
