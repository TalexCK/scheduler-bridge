package com.schedulerbridge.spigot;

import com.schedulerbridge.common.GameBridgeReporter;
import com.schedulerbridge.common.SchedulerBridge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class SchedulerBridgeSpigotPlugin extends JavaPlugin implements Listener {
  private SchedulerBridge bridge;
  private GameBridgeReporter reporter;

  @Override
  public void onEnable() {
    bridge = SchedulerBridge.create(new SpigotSchedulerAdapter(this));
    try {
      reporter = GameBridgeReporter.fromEnvironment(message -> getLogger().warning(message));
      reporter.updatePlayerCount(getServer().getOnlinePlayers().size());
      reporter.start();
      getServer().getPluginManager().registerEvents(this, this);
    } catch (RuntimeException error) {
      getLogger().severe(error.getMessage());
    }
  }

  @EventHandler
  public void onPlayerJoin(PlayerJoinEvent event) {
    if (reporter != null) {
      reporter.playerJoined();
    }
  }

  @EventHandler
  public void onPlayerQuit(PlayerQuitEvent event) {
    if (reporter != null) {
      reporter.playerLeft();
    }
  }

  @Override
  public void onDisable() {
    if (reporter != null) {
      reporter.close();
    }
  }

  public SchedulerBridge bridge() {
    return bridge;
  }
}
