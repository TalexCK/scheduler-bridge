package com.schedulerbridge.velocity;

import com.schedulerbridge.common.SchedulerFacade;
import com.schedulerbridge.common.TaskHandle;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;

import java.time.Duration;
import java.util.Objects;

public final class VelocitySchedulerAdapter implements SchedulerFacade {
    private static final long MILLIS_PER_TICK = 50L;

    private final ProxyServer proxyServer;
    private final Object plugin;

    public VelocitySchedulerAdapter(ProxyServer proxyServer, Object plugin) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "proxyServer");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public TaskHandle run(Runnable task) {
        ScheduledTask handle = proxyServer.getScheduler().buildTask(plugin, task).schedule();
        return handle::cancel;
    }

    @Override
    public TaskHandle runLater(Runnable task, long delayTicks) {
        ScheduledTask handle = proxyServer.getScheduler()
            .buildTask(plugin, task)
            .delay(toDuration(delayTicks))
            .schedule();
        return handle::cancel;
    }

    @Override
    public TaskHandle runRepeating(Runnable task, long delayTicks, long periodTicks) {
        ScheduledTask handle = proxyServer.getScheduler()
            .buildTask(plugin, task)
            .delay(toDuration(delayTicks))
            .repeat(toDuration(periodTicks))
            .schedule();
        return handle::cancel;
    }

    private static Duration toDuration(long ticks) {
        return Duration.ofMillis(Math.max(0L, ticks) * MILLIS_PER_TICK);
    }
}
