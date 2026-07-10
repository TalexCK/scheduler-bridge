package com.schedulerbridge.common;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class GameBridgeReporter implements AutoCloseable {
  private final BridgeHttpClient client;
  private final String serverId;
  private final String instanceId;
  private final Consumer<String> errorLogger;
  private final ScheduledExecutorService executor;
  private final long idleTimeoutNanos;
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean ready = new AtomicBoolean();
  private final AtomicBoolean idleReported = new AtomicBoolean();
  private final AtomicInteger onlinePlayers = new AtomicInteger();
  private final AtomicLong emptySinceNanos = new AtomicLong();

  private GameBridgeReporter(
      BridgeHttpClient client,
      String serverId,
      String instanceId,
      long idleTimeoutSeconds,
      Consumer<String> errorLogger) {
    this.client = client;
    this.serverId = require(serverId, "SCHEDULER_SERVER_ID");
    this.instanceId = require(instanceId, "SCHEDULER_INSTANCE_ID");
    this.idleTimeoutNanos = TimeUnit.SECONDS.toNanos(idleTimeoutSeconds);
    this.errorLogger = errorLogger;
    this.executor =
        Executors.newSingleThreadScheduledExecutor(
            task -> {
              Thread thread = new Thread(task, "scheduler-bridge-reporter");
              thread.setDaemon(true);
              return thread;
            });
  }

  public static GameBridgeReporter fromEnvironment(Consumer<String> errorLogger) {
    return new GameBridgeReporter(
        BridgeHttpClient.fromEnvironment(),
        System.getenv("SCHEDULER_SERVER_ID"),
        System.getenv("SCHEDULER_INSTANCE_ID"),
        idleTimeoutSeconds(System.getenv("SCHEDULER_IDLE_TIMEOUT_SECONDS")),
        errorLogger);
  }

  public void start() {
    if (!started.compareAndSet(false, true)) {
      return;
    }
    executor.execute(this::send);
    executor.scheduleAtFixedRate(this::send, 10, 10, TimeUnit.SECONDS);
    if (idleTimeoutNanos > 0) {
      beginEmptyPeriod();
      executor.scheduleAtFixedRate(this::checkIdle, 1, 1, TimeUnit.SECONDS);
    }
  }

  public void updatePlayerCount(int count) {
    if (count < 0) {
      throw new IllegalArgumentException("player count must not be negative");
    }
    onlinePlayers.set(count);
    if (count == 0) {
      beginEmptyPeriod();
    } else {
      clearEmptyPeriod();
    }
  }

  public void playerJoined() {
    onlinePlayers.incrementAndGet();
    clearEmptyPeriod();
  }

  public void playerLeft() {
    int remaining = onlinePlayers.updateAndGet(value -> Math.max(0, value - 1));
    if (remaining == 0) {
      beginEmptyPeriod();
    }
  }

  private void send() {
    try {
      if (ready.compareAndSet(false, true)) {
        client.ready(serverId, instanceId);
      } else {
        client.heartbeat(serverId, instanceId);
      }
    } catch (IOException error) {
      ready.set(false);
      errorLogger.accept(error.getMessage());
    }
  }

  private void checkIdle() {
    if (onlinePlayers.get() > 0) {
      clearEmptyPeriod();
      return;
    }
    long emptySince = emptySinceNanos.get();
    if (emptySince == 0) {
      beginEmptyPeriod();
      return;
    }
    if (System.nanoTime() - emptySince < idleTimeoutNanos
        || !idleReported.compareAndSet(false, true)) {
      return;
    }
    try {
      client.idle(serverId, instanceId);
    } catch (IOException error) {
      idleReported.set(false);
      errorLogger.accept(error.getMessage());
    }
  }

  private void beginEmptyPeriod() {
    if (idleTimeoutNanos > 0) {
      emptySinceNanos.compareAndSet(0, System.nanoTime());
    }
  }

  private void clearEmptyPeriod() {
    emptySinceNanos.set(0);
    idleReported.set(false);
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }

  private static String require(String value, String name) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalStateException(name + " must be set");
    }
    return value;
  }

  private static long idleTimeoutSeconds(String value) {
    if (value == null || value.trim().isEmpty()) {
      return 0;
    }
    try {
      long timeout = Long.parseLong(value);
      if (timeout < 0) {
        throw new IllegalStateException("SCHEDULER_IDLE_TIMEOUT_SECONDS must not be negative");
      }
      return timeout;
    } catch (NumberFormatException error) {
      throw new IllegalStateException("SCHEDULER_IDLE_TIMEOUT_SECONDS must be an integer", error);
    }
  }
}
