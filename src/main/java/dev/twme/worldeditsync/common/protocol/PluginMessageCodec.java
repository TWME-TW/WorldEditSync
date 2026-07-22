package dev.twme.worldeditsync.common.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

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
    private static final byte[] KEY_CONTEXT = "WorldEditSync/plugin-message/v3\0"
            .getBytes(StandardCharsets.US_ASCII);

    private final SecretKeySpec outboundKey;
    private final SecretKeySpec inboundKey;
    private final SecureRandom secureRandom = new SecureRandom();

    private PluginMessageCodec(String token, String outboundDirection, String inboundDirection) {
        if (token == null || token.isBlank()) {
            outboundKey = null;
            inboundKey = null;
            return;
        }

        try {
            outboundKey = deriveKey(token, outboundDirection);
            inboundKey = deriveKey(token, inboundDirection);
        } catch (Exception e) {
            throw new SecurityException("Failed to derive plugin-message encryption key", e);
        }
    }

    public static PluginMessageCodec forPaper(String token) {
        return new PluginMessageCodec(token, "paper-to-proxy", "proxy-to-paper");
    }

    public static PluginMessageCodec forProxy(String token) {
        return new PluginMessageCodec(token, "proxy-to-paper", "paper-to-proxy");
    }

    public boolean isEnabled() {
        return outboundKey != null;
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
            cipher.init(Cipher.ENCRYPT_MODE, outboundKey, new GCMParameterSpec(TAG_BITS, iv));
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
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, inboundKey,
                    new GCMParameterSpec(TAG_BITS, wireMessage, MAGIC.length, IV_LENGTH));
            cipher.updateAAD(MAGIC);
            int ciphertextOffset = MAGIC.length + IV_LENGTH;
            return ProtocolCodec.decode(cipher.doFinal(
                    wireMessage, ciphertextOffset, wireMessage.length - ciphertextOffset));
        } catch (Exception e) {
            return null;
        }
    }

    private static SecretKeySpec deriveKey(String token, String direction) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(KEY_CONTEXT);
        digest.update(direction.getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        return new SecretKeySpec(
                digest.digest(token.getBytes(StandardCharsets.UTF_8)), "AES");
    }
}
