package com.example.stockticker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class StockTicker extends JavaPlugin {

    private final Map<String, Quote> latestQuotes = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private Scoreboard sharedScoreboard;
    private Objective objective;
    private int taskId = -1;
    private SignManager signManager;
    private StockDisplayManager displayManager;

    @Override
    public void onEnable() {
        saveDefaultConfig(); // copies config.yml from resources on first run

        signManager = new SignManager(this);
        displayManager = new StockDisplayManager(this);

        sharedScoreboard = getServer().getScoreboardManager().getNewScoreboard();
        if (getConfig().getBoolean("enable-scoreboard", false)) {
            setupScoreboard();
        }
        startFetchLoop();

        getServer().getPluginManager().registerEvents(new JoinListener(sharedScoreboard), this);
        getServer().getPluginManager().registerEvents(new StockSignListener(signManager), this);

        getLogger().info("StockTicker enabled. Scoreboard: "
                + (getConfig().getBoolean("enable-scoreboard", false) ? "on" : "off")
                + ". Tracking " + signManager.size() + " sign(s) and " + displayManager.size() + " big display(s).");
    }

    @Override
    public void onDisable() {
        if (taskId != -1) {
            getServer().getScheduler().cancelTask(taskId);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("stocks")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                if (getConfig().getBoolean("enable-scoreboard", false)) {
                    setupScoreboard();
                }
                if (taskId != -1) {
                    getServer().getScheduler().cancelTask(taskId);
                }
                startFetchLoop();
                sender.sendMessage(ChatColor.GREEN + "StockTicker config reloaded.");
                return true;
            }
            sender.sendMessage(ChatColor.YELLOW + "Usage: /stocks reload");
            return true;
        }

        if (command.getName().equalsIgnoreCase("stockdisplay")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "This command can only be used in-game.");
                return true;
            }
            if (!player.hasPermission("stockticker.display")) {
                player.sendMessage(ChatColor.RED + "You don't have permission to do that.");
                return true;
            }

            if (args.length >= 2 && args[0].equalsIgnoreCase("create")) {
                String ticker = args[1].toUpperCase();
                double scale = 3.0;
                if (args.length >= 3) {
                    try {
                        scale = Double.parseDouble(args[2]);
                    } catch (NumberFormatException e) {
                        player.sendMessage(ChatColor.RED + "Scale must be a number, e.g. 3.0");
                        return true;
                    }
                }
                Location loc = player.getEyeLocation().add(
                        player.getEyeLocation().getDirection().normalize().multiply(2));
                // Displays are static now (no billboard rotation), so face the text back
                // toward whoever's creating it, the same way a sign would face you when placed.
                loc.setYaw(player.getEyeLocation().getYaw() + 180f);
                loc.setPitch(0f);
                displayManager.create(loc, ticker, scale);
                player.sendMessage(ChatColor.GREEN + "Created a big display for " + ticker
                        + " (scale " + scale + "). It'll populate on the next refresh.");
                return true;
            }

            if (args.length >= 1 && args[0].equalsIgnoreCase("remove")) {
                double radius = 5.0;
                if (args.length >= 2) {
                    try {
                        radius = Double.parseDouble(args[1]);
                    } catch (NumberFormatException e) {
                        player.sendMessage(ChatColor.RED + "Radius must be a number, e.g. 5.0");
                        return true;
                    }
                }

                Entity target = displayManager.findNearestTrackedDisplay(player.getLocation(), radius);
                if (target == null) {
                    player.sendMessage(ChatColor.RED + "No tracked stock display within "
                            + radius + " blocks of you.");
                    return true;
                }
                displayManager.removeIfTracked(target);
                player.sendMessage(ChatColor.GREEN + "Removed the nearest stock display.");
                return true;
            }

            if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
                player.sendMessage(ChatColor.YELLOW + "Tracking " + displayManager.size()
                        + " big stock display(s): " + String.join(", ", displayManager.getTrackedTickers()));
                return true;
            }

            player.sendMessage(ChatColor.YELLOW + "Usage: /stockdisplay create <TICKER> [scale] | /stockdisplay remove [radius] | /stockdisplay list");
            return true;
        }

        return false;
    }

    private void setupScoreboard() {
        // Remove any previous objective before re-registering (e.g. on /stocks reload).
        Objective old = sharedScoreboard.getObjective("stocks");
        if (old != null) {
            old.unregister();
        }

        String rawTitle = getConfig().getString("scoreboard-title", "&a&lStock Ticker");
        String title = ChatColor.translateAlternateColorCodes('&', rawTitle);

        objective = sharedScoreboard.registerNewObjective("stocks", "dummy", title);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        // Assign this scoreboard to every currently online player.
        for (Player p : getServer().getOnlinePlayers()) {
            p.setScoreboard(sharedScoreboard);
        }
    }

    private void startFetchLoop() {
        int refreshTicks = getConfig().getInt("refresh-seconds", 30) * 20;
        String apiKey = getConfig().getString("api-key", "");

        if (apiKey.isBlank() || apiKey.equals("YOUR_FINNHUB_API_KEY")) {
            getLogger().warning("No Finnhub API key set in config.yml — quotes will not update.");
        }

        // Run async so the HTTP calls never block the main server thread,
        // then hop back to the main thread to touch Bukkit API (scoreboard/signs).
        taskId = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            // Union of the scoreboard's configured tickers and whatever tickers are
            // currently displayed on in-world stock signs — fetched once per symbol,
            // however many places it's displayed.
            Set<String> allTickers = new LinkedHashSet<>();
            if (getConfig().getBoolean("enable-scoreboard", false)) {
                allTickers.addAll(getConfig().getStringList("tickers"));
            }
            allTickers.addAll(signManager.getTrackedTickers());
            allTickers.addAll(displayManager.getTrackedTickers());

            for (String ticker : allTickers) {
                if (ticker.toUpperCase().endsWith("=F")) {
                    fetchYahooFuturesQuote(ticker);
                } else {
                    fetchQuote(ticker, apiKey);
                }
            }
            // Back to main thread to safely touch Bukkit API.
            getServer().getScheduler().runTask(this, () -> {
                if (getConfig().getBoolean("enable-scoreboard", false)) {
                    renderScoreboard();
                }
                signManager.updateSigns(latestQuotes);
                displayManager.updateDisplays(latestQuotes);
            });
        }, 0L, refreshTicks).getTaskId();
    }

    private void fetchQuote(String ticker, String apiKey) {
        try {
            String url = "https://finnhub.io/api/v1/quote?symbol=" + ticker + "&token=" + apiKey;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                getLogger().warning("Finnhub returned HTTP " + response.statusCode() + " for " + ticker);
                return;
            }

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            // Finnhub /quote fields: c = current price, d = change, dp = percent change, t = unix seconds
            double current = json.get("c").getAsDouble();
            double change = json.get("d").isJsonNull() ? 0.0 : json.get("d").getAsDouble();
            double percent = json.get("dp").isJsonNull() ? 0.0 : json.get("dp").getAsDouble();
            long timestampMillis = (json.has("t") && !json.get("t").isJsonNull())
                    ? json.get("t").getAsLong() * 1000L
                    : System.currentTimeMillis();

            latestQuotes.put(ticker, new Quote(current, change, percent, timestampMillis));
        } catch (Exception e) {
            getLogger().warning("Failed to fetch quote for " + ticker + ": " + e.getMessage());
        }
    }

    /**
     * Fetches futures data (e.g. "NQ=F", "MNQ=F") from Yahoo Finance's unofficial
     * chart endpoint. This isn't a documented/supported API — no key, no rate-limit
     * guarantees, no SLA — so treat it as "best effort, latest available data" only.
     */
    private void fetchYahooFuturesQuote(String ticker) {
        try {
            String encoded = URLEncoder.encode(ticker, StandardCharsets.UTF_8);
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + encoded;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    // Yahoo's unofficial endpoint tends to reject requests with no User-Agent.
                    .header("User-Agent", "Mozilla/5.0 (compatible; StockTickerPlugin/1.0)")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                getLogger().warning("Yahoo Finance returned HTTP " + response.statusCode() + " for " + ticker);
                return;
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject chart = root.getAsJsonObject("chart");
            if (chart.has("error") && !chart.get("error").isJsonNull()) {
                getLogger().warning("Yahoo Finance error for " + ticker + ": " + chart.get("error"));
                return;
            }

            JsonObject meta = chart.getAsJsonArray("result").get(0).getAsJsonObject().getAsJsonObject("meta");

            double current = meta.get("regularMarketPrice").getAsDouble();
            double prevClose = meta.has("previousClose") && !meta.get("previousClose").isJsonNull()
                    ? meta.get("previousClose").getAsDouble()
                    : meta.get("chartPreviousClose").getAsDouble();
            double change = current - prevClose;
            double percent = prevClose != 0 ? (change / prevClose) * 100.0 : 0.0;
            long timestampMillis = (meta.has("regularMarketTime") && !meta.get("regularMarketTime").isJsonNull())
                    ? meta.get("regularMarketTime").getAsLong() * 1000L
                    : System.currentTimeMillis();

            latestQuotes.put(ticker, new Quote(current, change, percent, timestampMillis));
        } catch (Exception e) {
            getLogger().warning("Failed to fetch Yahoo futures quote for " + ticker + ": " + e.getMessage());
        }
    }

    private void renderScoreboard() {
        // Clear existing lines/teams before re-writing them.
        for (String entry : sharedScoreboard.getEntries()) {
            sharedScoreboard.resetScores(entry);
        }

        List<String> tickers = getConfig().getStringList("tickers");
        int score = tickers.size();

        // Use invisible-colour-coded "fake players" as sidebar lines, one per ticker.
        Map<String, String> lines = new LinkedHashMap<>();
        for (String ticker : tickers) {
            Quote q = latestQuotes.get(ticker);
            String line;
            if (q == null) {
                line = ChatColor.GRAY + ticker + ": ..." ;
            } else {
                ChatColor color = q.change() >= 0 ? ChatColor.GREEN : ChatColor.RED;
                String arrow = q.change() >= 0 ? "▲" : "▼";
                line = ChatColor.WHITE + ticker + ChatColor.GRAY + ": "
                        + color + String.format("$%.2f %s%.2f%%", q.price(), arrow, Math.abs(q.percentChange()));
            }
            lines.put(ticker, line);
        }

        for (String ticker : tickers) {
            String entryText = lines.get(ticker);
            // Bukkit scoreboard entries must be unique per line; use hidden color-code
            // suffixes per ticker index to guarantee uniqueness if two lines render the same text.
            registerLine(entryText, score);
            score--;
        }

        // Re-apply scoreboard to any players who joined after setup.
        for (Player p : getServer().getOnlinePlayers()) {
            if (p.getScoreboard() != sharedScoreboard) {
                p.setScoreboard(sharedScoreboard);
            }
        }
    }

    private void registerLine(String text, int score) {
        // Truncate to scoreboard line limits (Minecraft 1.13+ removed the old 40-char cap on
        // modern clients, but keep this conservative for compatibility).
        String safeText = text.length() > 64 ? text.substring(0, 64) : text;
        Team team = sharedScoreboard.getTeam("line" + score);
        if (team == null) {
            team = sharedScoreboard.registerNewTeam("line" + score);
        }
        String entry = ChatColor.values()[score % 15].toString() + ChatColor.RESET;
        team.addEntry(entry);
        team.setPrefix(safeText);
        objective.getScore(entry).setScore(score);
    }
}
