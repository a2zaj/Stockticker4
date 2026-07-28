package com.example.stockticker;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which in-world signs are "stock signs", persists that list to signs.yml,
 * and rewrites their text whenever fresh quotes come in.
 */
public class SignManager {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final JavaPlugin plugin;
    private final File file;
    // key: "world;x;y;z" -> ticker symbol
    private final Map<String, String> signs = new ConcurrentHashMap<>();

    public SignManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "signs.yml");
        load();
    }

    public void register(Location loc, String ticker) {
        signs.put(key(loc), ticker.toUpperCase());
        save();
    }

    public void unregister(Location loc) {
        if (signs.remove(key(loc)) != null) {
            save();
        }
    }

    public boolean isTracked(Location loc) {
        return signs.containsKey(key(loc));
    }

    public int size() {
        return signs.size();
    }

    /** Distinct set of every ticker currently displayed on any sign. */
    public Set<String> getTrackedTickers() {
        return new LinkedHashSet<>(signs.values());
    }

    /** Rewrites every tracked sign's text using the latest quotes available. Call from the main thread. */
    public void updateSigns(Map<String, Quote> quotes) {
        for (Map.Entry<String, String> entry : signs.entrySet()) {
            Location loc = parseKey(entry.getKey());
            if (loc == null) {
                continue;
            }
            World world = loc.getWorld();
            if (world == null) {
                continue;
            }

            int chunkX = loc.getBlockX() >> 4;
            int chunkZ = loc.getBlockZ() >> 4;
            // Don't force-load chunks just to update a sign no one is near.
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                continue;
            }

            BlockState state = loc.getBlock().getState();
            if (!(state instanceof Sign sign)) {
                continue; // sign was broken/replaced without going through BlockBreakEvent somehow
            }

            String ticker = entry.getValue();
            Quote q = quotes.get(ticker);

            SignSide front = sign.getSide(Side.FRONT);
            front.setLine(0, ChatColor.DARK_BLUE + "[stock]");
            front.setLine(1, ChatColor.BOLD.toString() + ChatColor.BLACK + ticker);

            if (q == null) {
                front.setLine(2, ChatColor.GRAY + "...");
                front.setLine(3, "");
            } else {
                ChatColor color = q.change() >= 0 ? ChatColor.DARK_GREEN : ChatColor.DARK_RED;
                String arrow = q.change() >= 0 ? "+" : "-";
                String time = TIME_FORMAT.format(Instant.ofEpochMilli(q.timestampMillis())
                        .atZone(ZoneId.systemDefault()));
                front.setLine(2, color + String.format("$%.2f", q.price()));
                front.setLine(3, color + String.format("%s%.1f%% ", arrow, Math.abs(q.percentChange()))
                        + ChatColor.GRAY + time);
            }

            sign.update();
        }
    }

    private String key(Location loc) {
        return loc.getWorld().getName() + ";" + loc.getBlockX() + ";" + loc.getBlockY() + ";" + loc.getBlockZ();
    }

    private Location parseKey(String key) {
        String[] parts = key.split(";");
        if (parts.length != 4) {
            return null;
        }
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return null;
        }
        try {
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            return new Location(world, x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("signs");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            String ticker = section.getString(key);
            if (ticker != null) {
                signs.put(key, ticker.toUpperCase());
            }
        }
        plugin.getLogger().info("Loaded " + signs.size() + " tracked stock sign(s).");
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, String> entry : signs.entrySet()) {
            yaml.set("signs." + entry.getKey(), entry.getValue());
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save signs.yml: " + e.getMessage());
        }
    }
}
