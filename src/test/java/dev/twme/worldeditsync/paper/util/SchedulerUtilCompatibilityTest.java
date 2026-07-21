package dev.twme.worldeditsync.paper.util;

import static org.junit.Assert.assertFalse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class SchedulerUtilCompatibilityTest {

    @Test
    public void commonSchedulerClassDoesNotLinkFoliaSchedulerApi() throws IOException {
        String resourceName = "/" + SchedulerUtil.class.getName().replace('.', '/') + ".class";
        byte[] bytecode;
        try (InputStream input = SchedulerUtil.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Could not read " + resourceName);
            }
            bytecode = input.readAllBytes();
        }

        String constantPool = new String(bytecode, StandardCharsets.ISO_8859_1);
        assertFalse(constantPool.contains("getAsyncScheduler"));
        assertFalse(constantPool.contains("getGlobalRegionScheduler"));
        assertFalse(constantPool.contains("threadedregions/scheduler"));
    }
}
