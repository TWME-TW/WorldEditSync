package dev.twme.worldeditsync.common.crypto;

import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class MessageCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();
    private final boolean enabled;

    public MessageCipher(String token) {
        if (token == null || token.isBlank()) {
            this.secretKey = null;
            this.enabled = false;
        } else {
            this.secretKey = deriveKey(token);
            this.enabled = true;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public byte[] encrypt(byte[] plaintext) {
        if (!enabled) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);

            // [IV (12 bytes)] [ciphertext + GCM tag]
            byte[] result = new byte[GCM_IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, GCM_IV_LENGTH);
            System.arraycopy(ciphertext, 0, result, GCM_IV_LENGTH, ciphertext.length);
            return result;
        } catch (Exception e) {
            throw new SecurityException("Encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] data) {
        if (!enabled) {
            return data;
        }
        if (data.length < GCM_IV_LENGTH) {
            throw new SecurityException("Data too short for decryption");
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(data, 0, iv, 0, GCM_IV_LENGTH);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(data, GCM_IV_LENGTH, data.length - GCM_IV_LENGTH);
        } catch (Exception e) {
            throw new SecurityException("Decryption failed - token mismatch or data corrupted", e);
        }
    }

    private static SecretKeySpec deriveKey(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new SecurityException("Failed to derive encryption key", e);
        }
    }
}
