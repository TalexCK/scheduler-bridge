package com.schedulerbridge.common;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface ServerScheduler {
  CompletableFuture<ServerInstance> launch(String serverId, Collection<UUID> players);

  CompletableFuture<ServerInstance> launchSolo(
      String gameId, UUID ownerUuid, Collection<UUID> players);

  CompletableFuture<Void> destroySolo(String gameId, UUID playerUuid);

  CompletableFuture<List<SoloSession>> soloSessions();

  CompletableFuture<ServerInstance> startSoloSession(
      String sessionId, Collection<UUID> players);

  CompletableFuture<Void> queueTransfers(String serverId, Collection<UUID> players);

  CompletableFuture<ServerInstance> stop(String serverId);

  CompletableFuture<List<ServerInstance>> list();

  CompletableFuture<List<SchedulerGameDefinition>> games();

  default CompletableFuture<Optional<SoloSession>> findSoloSession(
      String gameId, UUID playerUuid) {
    return soloSessions()
        .thenApply(
            sessions ->
                sessions.stream()
                    .filter(session -> session.gameId().equalsIgnoreCase(gameId))
                    .filter(session -> session.players().contains(playerUuid))
                    .findFirst());
  }

  default CompletableFuture<Optional<ServerInstance>> find(String serverId) {
    return list()
        .thenApply(
            instances ->
                instances.stream()
                    .filter(instance -> instance.serverId().equalsIgnoreCase(serverId))
                    .findFirst());
  }
}
