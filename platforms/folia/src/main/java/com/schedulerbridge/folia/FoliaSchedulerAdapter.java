package com.schedulerbridge.folia;

import com.schedulerbridge.common.SchedulerFacade;
import com.schedulerbridge.common.TaskHandle;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Objects;
import org.bukkit.plugin.Plugin;

public final class FoliaSchedulerAdapter implements SchedulerFacade {
  private final Plugin plugin;

  public FoliaSchedulerAdapter(Plugin plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
  }

  @Override
  public TaskHandle run(Runnable task) {
    plugin.getServer().getGlobalRegionScheduler().execute(plugin, task);
    return TaskHandle.noop();
  }

  @Override
  public TaskHandle runLater(Runnable task, long delayTicks) {
    ScheduledTask handle =
        plugin
            .getServer()
            .getGlobalRegionScheduler()
            .runDelayed(plugin, scheduledTask -> task.run(), delayTicks);
    return handle::cancel;
  }

  @Override
  public TaskHandle runRepeating(Runnable task, long delayTicks, long periodTicks) {
    ScheduledTask handle =
        plugin
            .getServer()
            .getGlobalRegionScheduler()
            .runAtFixedRate(plugin, scheduledTask -> task.run(), delayTicks, periodTicks);
    return handle::cancel;
  }
}
