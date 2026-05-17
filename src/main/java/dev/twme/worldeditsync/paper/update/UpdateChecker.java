package dev.twme.worldeditsync.paper.update;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.bukkit.plugin.java.JavaPlugin;

import dev.twme.worldeditsync.paper.util.SchedulerUtil;

public class UpdateChecker {

    private static final String SPIGOT_RESOURCE_ID = "121682";
    private static final String API_URL = "https://api.spigotmc.org/legacy/update.php?resource=" + SPIGOT_RESOURCE_ID;

    private final JavaPlugin plugin;

    public UpdateChecker(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void checkAsync() {
        SchedulerUtil.runAsync(plugin, () -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(API_URL))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                String latestVersion = response.body().trim();
                String currentVersion = plugin.getPluginMeta().getVersion();
                if (!currentVersion.equals(latestVersion)) {
                    plugin.getLogger().info("A new version is available: " + latestVersion
                            + " (current: " + currentVersion + ")");
                    plugin.getLogger().info("Download: https://www.spigotmc.org/resources/" + SPIGOT_RESOURCE_ID);
                }
            } catch (IOException | InterruptedException e) {
                plugin.getLogger().fine("Could not check for updates: " + e.getMessage());
            }
        });
    }
}
