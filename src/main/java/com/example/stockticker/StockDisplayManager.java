package com.example.stockticker;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks floating "big screen" TextDisplay entities showing live stock/futures
 * data with a solid black background and white text, at a scale the creator picks.
 */
public class StockDisplayManager {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final JavaPlugin plugin;
    private final File file;
    // key: entity UUID -> ticker symbol
    private final Map<UUID, String> displays = new ConcurrentHashMap<>();

    public StockDisplayManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "displays.yml");
        load();
    }

    /**
     * Spawns a new black-background/white-text display at the given location showing
     * "..." until the next refresh, tracked under the given ticker.
     */
    public TextDisplay create(Location location, String ticker, double scale) {
        TextDisplay display = location.getWorld().spawn(location, TextDisplay.class, entity -> {
            // FIXED = orientation is locked to the entity's yaw/pitch at spawn time and
            // never rotates to face viewers, i.e. a static "wall screen" rather than a
            // billboard. `location` already carries the yaw the creator was facing.
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(Color.fromARGB(255, 0, 0, 0)); // solid opaque black
            entity.setSeeThrough(false);
            entity.setShadowed(false);
            entity.setLineWidth(200);
            entity.setText(ChatColor.WHITE + ticker + "\n" + ChatColor.GRAY + "...");

            Transformation transform = new Transformation(
                    new Vector3f(0f, 0f, 0f),
                    new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f((float) scale, (float) scale, (float) scale),
                    new AxisAngle4f(0f, 0f, 0f, 1f)
            );
            entity.setTransformation(transform);
        });

        displays.put(display.getUniqueId(), ticker.toUpperCase());
        save();
        return display;
    }

    public boolean removeIfTracked(Entity entity) {
        if (displays.remove(entity.getUniqueId()) != null) {
            entity.remove();
            save();
            return true;
        }
        return false;
    }

    /**
     * Finds the tracked display closest to the given location, within radius blocks,
     * in the same world. Used so displays can be removed by standing near them rather
     * than needing to look directly at them (useful now that they're static and may
     * be facing away from wherever the player approaches from). Returns null if none
     * are within range.
     */
    public Entity findNearestTrackedDisplay(Location location, double radius) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }

        Entity nearest = null;
        double nearestDistSq = radius * radius;

        for (UUID id : displays.keySet()) {
            Entity entity = Bukkit.getEntity(id);
            if (entity == null || !entity.getWorld().equals(world)) {
                continue; // not currently loaded, or in a different world
            }
            double distSq = entity.getLocation().distanceSquared(location);
            if (distSq <= nearestDistSq) {
                nearest = entity;
                nearestDistSq = distSq;
            }
        }

        return nearest;
    }

    public int size() {
        return displays.size();
    }

    public Set<String> getTrackedTickers() {
        return new LinkedHashSet<>(displays.values());
    }

    /** Rewrites every tracked display's text using the latest quotes available. Call from the main thread. */
    public void updateDisplays(Map<String, Quote> quotes) {
        for (Map.Entry<UUID, String> entry : displays.entrySet()) {
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (!(entity instanceof TextDisplay display)) {
                continue; // not currently loaded, or was removed some other way
            }

            String ticker = entry.getValue();
            Quote q = quotes.get(ticker);

            if (q == null) {
                display.setText(ChatColor.WHITE + ticker + "\n" + ChatColor.GRAY + "...");
                continue;
            }

            ChatColor color = q.change() >= 0 ? ChatColor.GREEN : ChatColor.RED;
            String arrow = q.change() >= 0 ? "▲" : "▼";
            String time = TIME_FORMAT.format(Instant.ofEpochMilli(q.timestampMillis())
                    .atZone(ZoneId.systemDefault()));

            String text = ChatColor.WHITE + "" + ChatColor.BOLD + ticker + "\n"
                    + color + String.format("$%.2f", q.price()) + "\n"
                    + color + String.format("%s %.2f%%", arrow, Math.abs(q.percentChange())) + "\n"
                    + ChatColor.GRAY + "as of " + time;

            display.setText(text);
        }
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        var section = yaml.getConfigurationSection("displays");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            String ticker = section.getString(key);
            if (ticker == null) {
                continue;
            }
            try {
                displays.put(UUID.fromString(key), ticker.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // corrupt entry, skip
            }
        }
        plugin.getLogger().info("Loaded " + displays.size() + " tracked stock display(s).");
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, String> entry : displays.entrySet()) {
            yaml.set("displays." + entry.getKey(), entry.getValue());
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save displays.yml: " + e.getMessage());
        }
    }
}
