package dev.twme.worldeditsync.common.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import dev.twme.worldeditsync.common.Constants;
import dev.twme.worldeditsync.common.protocol.ProtocolCodec.ParsedMessage;

/**
 * Encrypts and authenticates every plugin message with a key separate from the
 * stored clipboard encryption key. This also hides control metadata from a
 * player who can connect directly to a misconfigured backend server.
 */
public final class PluginMessageCodec {

    private static final byte[] MAGIC = {'W', 'E', 'S', Constants.PROTOCOL_VERSION};
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final byte[] KEY_CONTEXT = "WorldEditSync/plugin-message/v2\0"
            .getBytes(StandardCharsets.US_ASCII);

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public PluginMessageCodec(String token) {
        if (token == null || token.isBlank()) {
            key = null;
            return;
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(KEY_CONTEXT);
            key = new SecretKeySpec(
                    digest.digest(token.getBytes(StandardCharsets.UTF_8)),
                    "AES");
        } catch (Exception e) {
            throw new SecurityException("Failed to derive plugin-message encryption key", e);
        }
    }

    public boolean isEnabled() {
        return key != null;
    }

    public byte[] encode(byte[] protocolMessage) {
        if (protocolMessage == null) {
            throw new IllegalArgumentException("protocolMessage must not be null");
        }
        if (!isEnabled()) {
            if (protocolMessage.length > Constants.MAX_PLUGIN_MESSAGE_SIZE) {
                throw new IllegalArgumentException("Plugin message exceeds transport limit");
            }
            return protocolMessage;
        }

        int envelopeLength = MAGIC.length + IV_LENGTH + protocolMessage.length + TAG_BITS / 8;
        if (envelopeLength > Constants.MAX_PLUGIN_MESSAGE_SIZE) {
            throw new IllegalArgumentException("Authenticated plugin message exceeds transport limit");
        }

        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(MAGIC);
            byte[] ciphertext = cipher.doFinal(protocolMessage);

            byte[] result = new byte[envelopeLength];
            System.arraycopy(MAGIC, 0, result, 0, MAGIC.length);
            System.arraycopy(iv, 0, result, MAGIC.length, IV_LENGTH);
            System.arraycopy(ciphertext, 0, result, MAGIC.length + IV_LENGTH, ciphertext.length);
            return result;
        } catch (Exception e) {
            throw new SecurityException("Failed to encrypt plugin message", e);
        }
    }

    public ParsedMessage decode(byte[] wireMessage) {
        if (wireMessage == null || wireMessage.length > Constants.MAX_PLUGIN_MESSAGE_SIZE) {
            return null;
        }
        if (!isEnabled()) {
            return ProtocolCodec.decode(wireMessage);
        }
        if (wireMessage.length < MAGIC.length + IV_LENGTH + TAG_BITS / 8 + 2) {
            return null;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (wireMessage[i] != MAGIC[i]) {
                return null;
            }
        }

        try {
            byte[] iv = Arrays.copyOfRange(wireMessage, MAGIC.length, MAGIC.length + IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(
                    wireMessage, MAGIC.length + IV_LENGTH, wireMessage.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(MAGIC);
            return ProtocolCodec.decode(cipher.doFinal(ciphertext));
        } catch (Exception e) {
            return null;
        }
    }
}
