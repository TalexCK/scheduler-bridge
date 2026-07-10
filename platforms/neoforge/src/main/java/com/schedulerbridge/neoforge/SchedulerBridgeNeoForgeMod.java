package com.schedulerbridge.neoforge;

import com.schedulerbridge.common.GameBridgeReporter;
import com.schedulerbridge.common.SchedulerBridge;
import java.util.concurrent.Executor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@Mod(SchedulerBridgeNeoForgeMod.MOD_ID)
public final class SchedulerBridgeNeoForgeMod {
  public static final String MOD_ID = "scheduler_bridge";
  private GameBridgeReporter reporter;

  public SchedulerBridgeNeoForgeMod() {
    NeoForge.EVENT_BUS.register(this);
    try {
      reporter = GameBridgeReporter.fromEnvironment(System.err::println);
    } catch (RuntimeException error) {
      System.err.println(error.getMessage());
    }
  }

  @SubscribeEvent
  public void onServerStarted(ServerStartedEvent event) {
    if (reporter != null) {
      reporter.updatePlayerCount(0);
      reporter.start();
    }
  }

  @SubscribeEvent
  public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
    if (reporter != null) {
      reporter.playerJoined();
    }
  }

  @SubscribeEvent
  public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
    if (reporter != null) {
      reporter.playerLeft();
    }
  }

  @SubscribeEvent
  public void onServerStopped(ServerStoppedEvent event) {
    if (reporter != null) {
      reporter.close();
    }
  }

  public static SchedulerBridge createBridge(Executor serverExecutor) {
    return SchedulerBridge.create(new NeoForgeSchedulerAdapter(serverExecutor));
  }
}
