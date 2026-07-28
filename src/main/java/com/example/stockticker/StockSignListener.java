package com.example.stockticker;

import org.bukkit.ChatColor;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;

public class StockSignListener implements Listener {

    private final SignManager signManager;

    public StockSignListener(SignManager signManager) {
        this.signManager = signManager;
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        String firstLine = event.getLine(0) == null ? "" : ChatColor.stripColor(event.getLine(0)).trim();
        if (!firstLine.equalsIgnoreCase("[stock]")) {
            return; // not a stock sign, leave it alone
        }

        if (!event.getPlayer().hasPermission("stockticker.sign")) {
            event.getPlayer().sendMessage(ChatColor.RED + "You don't have permission to create stock signs.");
            event.setCancelled(true);
            return;
        }

        String rawTicker = event.getLine(1) == null ? "" : ChatColor.stripColor(event.getLine(1)).trim();
        if (rawTicker.isEmpty() || !rawTicker.matches("[A-Za-z0-9.=\\-]{1,15}")) {
            event.getPlayer().sendMessage(ChatColor.RED + "Line 2 must be a valid ticker symbol, e.g. AAPL or NQ=F");
            event.setCancelled(true);
            return;
        }

        String ticker = rawTicker.toUpperCase();

        event.setLine(0, ChatColor.DARK_BLUE + "[stock]");
        event.setLine(1, ChatColor.BOLD.toString() + ChatColor.BLACK + ticker);
        event.setLine(2, ChatColor.GRAY + "...");
        event.setLine(3, "");

        signManager.register(event.getBlock().getLocation(), ticker);
        event.getPlayer().sendMessage(ChatColor.GREEN + "Stock sign created for " + ticker
                + ". It'll show a live price on the next refresh.");
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!(event.getBlock().getState() instanceof Sign)) {
            return;
        }
        if (signManager.isTracked(event.getBlock().getLocation())) {
            signManager.unregister(event.getBlock().getLocation());
        }
    }
}
