package com.schedulerbridge.neoforge;

import com.schedulerbridge.common.SchedulerFacade;
import com.schedulerbridge.common.TaskHandle;

import java.util.Objects;
import java.util.concurrent.Executor;

public final class NeoForgeSchedulerAdapter implements SchedulerFacade {
    private final Executor executor;

    public NeoForgeSchedulerAdapter(Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public TaskHandle run(Runnable task) {
        executor.execute(task);
        return TaskHandle.noop();
    }

    @Override
    public TaskHandle runLater(Runnable task, long delayTicks) {
        return run(task);
    }

    @Override
    public TaskHandle runRepeating(Runnable task, long delayTicks, long periodTicks) {
        return run(task);
    }
}
