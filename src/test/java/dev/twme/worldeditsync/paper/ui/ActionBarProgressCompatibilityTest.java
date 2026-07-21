package dev.twme.worldeditsync.paper.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class ActionBarProgressCompatibilityTest {

    @Test
    public void usesSpigotActionBarBridgeWithoutPaperAdventureApi() throws IOException {
        String resourceName = "/" + ActionBarProgress.class.getName().replace('.', '/') + ".class";
        byte[] bytecode;
        try (InputStream input = ActionBarProgress.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Could not read " + resourceName);
            }
            bytecode = input.readAllBytes();
        }

        String constantPool = new String(bytecode, StandardCharsets.ISO_8859_1);
        assertTrue(constantPool.contains("org/bukkit/entity/Player$Spigot"));
        assertFalse(constantPool.contains("net/kyori/adventure"));
        assertFalse(constantPool.contains("sendActionBar"));
    }
}
