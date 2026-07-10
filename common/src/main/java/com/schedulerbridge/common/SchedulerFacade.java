package com.schedulerbridge.common;

public interface SchedulerFacade {
  TaskHandle run(Runnable task);

  TaskHandle runLater(Runnable task, long delayTicks);

  TaskHandle runRepeating(Runnable task, long delayTicks, long periodTicks);
}
