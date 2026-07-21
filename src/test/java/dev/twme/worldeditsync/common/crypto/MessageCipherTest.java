package dev.twme.worldeditsync.common.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class MessageCipherTest {

    @Test
    public void encryptedPayloadRoundTripsWithDeclaredOverhead() {
        MessageCipher cipher = new MessageCipher("shared-token");
        byte[] plaintext = "clipboard-data".getBytes(StandardCharsets.UTF_8);

        byte[] encrypted = cipher.encrypt(plaintext);

        assertEquals(
                plaintext.length + MessageCipher.ENCRYPTION_OVERHEAD_BYTES,
                encrypted.length);
        assertArrayEquals(plaintext, cipher.decrypt(encrypted));
    }

    @Test
    public void rejectsMismatchedToken() {
        byte[] encrypted = new MessageCipher("first-token")
                .encrypt("clipboard-data".getBytes(StandardCharsets.UTF_8));

        assertThrows(SecurityException.class,
                () -> new MessageCipher("second-token").decrypt(encrypted));
    }
}
