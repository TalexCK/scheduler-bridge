package com.schedulerbridge.common;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class HttpServerScheduler implements ServerScheduler, AutoCloseable {
  private final BridgeHttpClient client;
  private final Consumer<String> errorLogger;
  private final ExecutorService executor;

  public HttpServerScheduler(BridgeHttpClient client, Consumer<String> errorLogger) {
    this.client = client;
    this.errorLogger = errorLogger;
    this.executor =
        Executors.newCachedThreadPool(
            task -> {
              Thread thread = new Thread(task, "scheduler-bridge-control");
              thread.setDaemon(true);
              return thread;
            });
  }

  @Override
  public CompletableFuture<ServerInstance> launch(String serverId, Collection<UUID> players) {
    return supply(() -> client.launchServer(serverId, playerIds(players)));
  }

  @Override
  public CompletableFuture<ServerInstance> launchSolo(
      String gameId, UUID ownerUuid, Collection<UUID> players) {
    return supply(() -> client.launchSolo(gameId, ownerUuid, players));
  }

  @Override
  public CompletableFuture<Void> destroySolo(String gameId, UUID playerUuid) {
    return supply(
        () -> {
          client.destroySolo(gameId, playerUuid);
          return null;
        });
  }

  @Override
  public CompletableFuture<Void> queueTransfers(String serverId, Collection<UUID> players) {
    return supply(
        () -> {
          client.queueTransfers(serverId, playerIds(players));
          return null;
        });
  }

  @Override
  public CompletableFuture<ServerInstance> stop(String serverId) {
    return supply(() -> client.terminateServer(serverId));
  }

  @Override
  public CompletableFuture<List<ServerInstance>> list() {
    return supply(client::servers);
  }

  @Override
  public CompletableFuture<List<SchedulerGameDefinition>> games() {
    return supply(client::games);
  }

  private <T> CompletableFuture<T> supply(IoSupplier<T> supplier) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return supplier.get();
          } catch (IOException error) {
            errorLogger.accept(error.getMessage());
            throw new CompletionException(error);
          }
        },
        executor);
  }

  private static List<String> playerIds(Collection<UUID> players) {
    return players.stream().map(UUID::toString).collect(Collectors.toList());
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }

  private interface IoSupplier<T> {
    T get() throws IOException;
  }
}
