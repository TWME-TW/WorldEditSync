package dev.twme.worldeditsync.paper;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Scanner;
import java.util.function.Consumer;

public class UpdateChecker {

    private final JavaPlugin plugin;
    private final int resourceId;

    public UpdateChecker(JavaPlugin plugin, int resourceId) {
        this.plugin = plugin;
        this.resourceId = resourceId;
    }

    public void getVersion(final Consumer<String> consumer) {
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            URI uri = URI.create("https://api.spigotmc.org/legacy/update.php?resource=" + this.resourceId);
            try (InputStream is = uri.toURL().openStream(); Scanner scann = new Scanner(is)) {
                if (scann.hasNext()) {
                    consumer.accept(scann.next());
                }
            } catch (IOException e) {
                plugin.getLogger().info("Unable to check for updates: " + e.getMessage());
            }
        });
    }
}
