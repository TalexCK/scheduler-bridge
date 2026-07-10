package com.schedulerbridge.common;

@FunctionalInterface
public interface TaskHandle {
  TaskHandle NOOP =
      new TaskHandle() {
        @Override
        public void cancel() {}
      };

  void cancel();

  static TaskHandle noop() {
    return NOOP;
  }
}
