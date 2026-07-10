package com.schedulerbridge.common;

import java.util.Objects;

public final class SchedulerBridge {
  private final SchedulerFacade scheduler;

  private SchedulerBridge(SchedulerFacade scheduler) {
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
  }

  public static SchedulerBridge create(SchedulerFacade scheduler) {
    return new SchedulerBridge(scheduler);
  }

  public TaskHandle runNow(Runnable task) {
    return scheduler.run(Objects.requireNonNull(task, "task"));
  }

  public TaskHandle runLater(Runnable task, long delayTicks) {
    return scheduler.runLater(Objects.requireNonNull(task, "task"), delayTicks);
  }

  public TaskHandle runRepeating(Runnable task, long delayTicks, long periodTicks) {
    return scheduler.runRepeating(Objects.requireNonNull(task, "task"), delayTicks, periodTicks);
  }
}
