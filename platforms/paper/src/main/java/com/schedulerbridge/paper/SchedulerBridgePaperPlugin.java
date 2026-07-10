package com.schedulerbridge.paper;

import com.schedulerbridge.common.BridgeHttpClient;
import com.schedulerbridge.common.GameBridgeReporter;
import com.schedulerbridge.common.HttpServerScheduler;
import com.schedulerbridge.common.SchedulerBridge;
import com.schedulerbridge.common.ServerScheduler;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class SchedulerBridgePaperPlugin extends JavaPlugin implements Listener {
  private SchedulerBridge bridge;
  private GameBridgeReporter reporter;
  private HttpServerScheduler serverScheduler;

  @Override
  public void onEnable() {
    bridge = SchedulerBridge.create(new PaperSchedulerAdapter(this));
    try {
      BridgeHttpClient client = BridgeHttpClient.fromEnvironment();
      reporter = GameBridgeReporter.fromEnvironment(message -> getLogger().warning(message));
      serverScheduler = new HttpServerScheduler(client, message -> getLogger().warning(message));
      getServer()
          .getServicesManager()
          .register(ServerScheduler.class, serverScheduler, this, ServicePriority.Normal);
      getServer().getPluginManager().registerEvents(this, this);
    } catch (RuntimeException error) {
      getLogger().severe(error.getMessage());
    }
  }

  @EventHandler
  public void onServerLoad(ServerLoadEvent event) {
    if (reporter != null) {
      reporter.updatePlayerCount(getServer().getOnlinePlayers().size());
      reporter.start();
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
    getServer().getServicesManager().unregisterAll(this);
    if (serverScheduler != null) {
      serverScheduler.close();
    }
    if (reporter != null) {
      reporter.close();
    }
  }

  public SchedulerBridge bridge() {
    return bridge;
  }
}
