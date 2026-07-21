package dev.twme.worldeditsync.paper.update;

import static org.junit.Assert.assertFalse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class UpdateCheckerCompatibilityTest {

    @Test
    public void updateCheckerDoesNotLinkPaperPluginMetadataApi() throws IOException {
        String resourceName = "/" + UpdateChecker.class.getName().replace('.', '/') + ".class";
        byte[] bytecode;
        try (InputStream input = UpdateChecker.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Could not read " + resourceName);
            }
            bytecode = input.readAllBytes();
        }

        String constantPool = new String(bytecode, StandardCharsets.ISO_8859_1);
        assertFalse(constantPool.contains("getPluginMeta"));
        assertFalse(constantPool.contains("io/papermc/paper/plugin/configuration"));
    }
}
