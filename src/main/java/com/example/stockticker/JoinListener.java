package com.example.stockticker;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scoreboard.Scoreboard;

public class JoinListener implements Listener {

    private final Scoreboard scoreboard;

    public JoinListener(Scoreboard scoreboard) {
        this.scoreboard = scoreboard;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().setScoreboard(scoreboard);
    }
}
