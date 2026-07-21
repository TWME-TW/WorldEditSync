package dev.twme.worldeditsync.paper.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.Test;

public class PaperConfigTest {

    @Test
    public void rejectsUnknownSyncMode() {
        PaperConfig config = loadWithMode(" ftp ");

        assertFalse(config.isSupportedMode());
        assertFalse(config.isProxyMode());
        assertFalse(config.isS3Mode());
    }

    @Test
    public void trimsAndAcceptsSupportedSyncMode() {
        PaperConfig config = loadWithMode(" S3 ");

        assertTrue(config.isSupportedMode());
        assertTrue(config.isS3Mode());
    }

    private PaperConfig loadWithMode(String mode) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        FileConfiguration fileConfig = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(fileConfig);
        when(fileConfig.getString("sync-mode", "proxy")).thenReturn(mode);
        when(fileConfig.getString("token", "")).thenReturn("");

        PaperConfig config = new PaperConfig();
        config.load(plugin);
        return config;
    }
}
