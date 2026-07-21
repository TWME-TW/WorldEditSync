package dev.twme.worldeditsync.paper.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.bukkit.plugin.java.JavaPlugin;

import dev.twme.worldeditsync.paper.util.SchedulerUtil;

public class UpdateChecker {

    private static final String SPIGOT_RESOURCE_ID = "121682";
    private static final String API_URL = "https://api.spigotmc.org/legacy/update.php?resource="
            + SPIGOT_RESOURCE_ID;
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_RESPONSE_BYTES = 64;

    private final JavaPlugin plugin;

    public UpdateChecker(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void checkAsync() {
        SchedulerUtil.runAsync(plugin, () -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) URI.create(API_URL).toURL().openConnection();
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                connection.setRequestMethod("GET");
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    throw new IOException("Update service returned HTTP " + status);
                }
                byte[] response;
                try (InputStream input = connection.getInputStream()) {
                    response = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                }
                if (response.length == 0 || response.length > MAX_RESPONSE_BYTES) {
                    throw new IOException("Update service returned an invalid response");
                }

                String latestVersion = new String(response, StandardCharsets.UTF_8).trim();
                String currentVersion = plugin.getDescription().getVersion();
                if (!currentVersion.equals(latestVersion)) {
                    plugin.getLogger().info("A new version is available: " + latestVersion
                            + " (current: " + currentVersion + ")");
                    plugin.getLogger().info("Download: https://www.spigotmc.org/resources/"
                            + SPIGOT_RESOURCE_ID);
                }
            } catch (IOException e) {
                plugin.getLogger().fine("Could not check for updates: " + e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }
}
