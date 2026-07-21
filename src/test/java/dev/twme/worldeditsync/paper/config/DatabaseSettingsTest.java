package dev.twme.worldeditsync.paper.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;

import org.junit.Test;

public class DatabaseSettingsTest {

    @Test
    public void resolvesDefaultUrlsForEveryBackend() {
        Path dataFolder = Path.of("plugins", "WorldEditSync");

        assertEquals("jdbc:mysql://db.local:3306/builds",
                settings(StorageType.MYSQL, "", "db.local", 0, "builds").resolveUrl(dataFolder));
        assertEquals("jdbc:mariadb://db.local:3307/builds",
                settings(StorageType.MARIADB, "", "db.local", 3307, "builds").resolveUrl(dataFolder));
        assertEquals("jdbc:postgresql://db.local:5432/builds",
                settings(StorageType.POSTGRESQL, "", "db.local", 0, "builds").resolveUrl(dataFolder));
        assertEquals("redis://db.local:6379",
                settings(StorageType.REDIS, "", "db.local", 0, "builds").resolveUrl(dataFolder));
        assertTrue(settings(StorageType.SQLITE, "", "", 0, "")
                .resolveUrl(dataFolder).startsWith("jdbc:sqlite:"));
    }

    @Test
    public void explicitUrlOverridesGeneratedValues() {
        DatabaseSettings settings = settings(
                StorageType.POSTGRESQL, " jdbc:postgresql://custom/database ", "ignored", 1, "ignored");
        assertEquals("jdbc:postgresql://custom/database", settings.resolveUrl(Path.of("ignored")));
    }

    @Test
    public void recognizesDatabaseStorageTypes() {
        assertTrue(settings(StorageType.REDIS, "", "", 0, "").isSupported());
        assertTrue(settings(StorageType.SQLITE, "", "", 0, "").isSupported());
        assertFalse(settings(StorageType.S3, "", "", 0, "").isSupported());
        assertFalse(settings(null, "", "", 0, "").isSupported());
    }

    @Test
    public void parsesAliasesCaseInsensitively() {
        assertEquals(StorageType.POSTGRESQL, StorageType.parse(" Postgres "));
        assertEquals(StorageType.MARIADB, StorageType.parse("mariadb"));
        assertEquals(StorageType.REDIS, StorageType.parse("REDIS"));
        assertEquals(null, StorageType.parse("mongodb"));
    }

    private DatabaseSettings settings(StorageType type, String url, String host, int port, String database) {
        return new DatabaseSettings(type, url, host, port, database, "", "",
                "worldeditsync_clipboards", "worldeditsync", 4, 10_000L, 40, 60L);
    }
}
