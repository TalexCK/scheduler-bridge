package com.schedulerbridge.spigot;

import com.schedulerbridge.common.SchedulerFacade;
import com.schedulerbridge.common.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;

public final class SpigotSchedulerAdapter implements SchedulerFacade {
    private final Plugin plugin;

    public SpigotSchedulerAdapter(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public TaskHandle run(Runnable task) {
        BukkitTask handle = Bukkit.getScheduler().runTask(plugin, task);
        return handle::cancel;
    }

    @Override
    public TaskHandle runLater(Runnable task, long delayTicks) {
        BukkitTask handle = Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        return handle::cancel;
    }

    @Override
    public TaskHandle runRepeating(Runnable task, long delayTicks, long periodTicks) {
        BukkitTask handle = Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        return handle::cancel;
    }
}
