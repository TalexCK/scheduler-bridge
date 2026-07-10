package com.schedulerbridge.velocity;

import com.schedulerbridge.common.BridgeHttpClient;
import com.schedulerbridge.common.SchedulerGameDefinition;
import com.schedulerbridge.common.ServerInstance;
import com.schedulerbridge.common.ServerInstanceState;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

final class SoloAccessRegistry {
  enum Decision {
    NORMAL,
    ALLOWED,
    DENIED,
    UNKNOWN
  }

  enum DenialReason {
    NONE,
    UNAUTHORIZED,
    INACTIVE_SOLO,
    STALE_INSTANCE,
    UNKNOWN
  }

  record Evaluation(Decision decision, DenialReason reason, boolean retryable) {}

  private final Set<String> fallbackNormalIds;
  private final AtomicReference<Snapshot> snapshot;

  SoloAccessRegistry(String... fallbackNormalIds) {
    Set<String> fallback = new HashSet<>();
    for (String serverId : fallbackNormalIds) {
      if (serverId != null && !serverId.isBlank()) {
        fallback.add(normalize(serverId));
      }
    }
    this.fallbackNormalIds = immutableSet(fallback);
    this.snapshot =
        new AtomicReference<>(
            new Snapshot(
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                this.fallbackNormalIds));
  }

  void beginReconciliation() {
    snapshot.updateAndGet(Snapshot::quarantine);
  }

  Snapshot prepare(
      Collection<String> definitions,
      Collection<SchedulerGameDefinition> games,
      Collection<BridgeHttpClient.SoloAccessRecord> accessRecords,
      Collection<ServerInstance> instances) {
    Snapshot previous = snapshot.get();
    Set<String> soloDefinitions = new HashSet<>();
    for (SchedulerGameDefinition game : games) {
      if (game.solo()) {
        soloDefinitions.add(normalize(game.serverId()));
      }
    }

    Set<String> normalDefinitions = new HashSet<>();
    for (String definition : definitions) {
      String normalized = normalize(definition);
      if (!soloDefinitions.contains(normalized)) {
        normalDefinitions.add(normalized);
      }
    }

    Map<String, InstanceBinding> readyInstances = new HashMap<>();
    for (ServerInstance instance : instances) {
      if (instance.state() != ServerInstanceState.READY) {
        continue;
      }
      String normalized = normalize(instance.serverId());
      InstanceBinding binding =
          new InstanceBinding(
              instance.serverId(),
              instance.instanceId(),
              "127.0.0.1:" + instance.port());
      InstanceBinding previousBinding = readyInstances.putIfAbsent(normalized, binding);
      if (previousBinding != null
          && !previousBinding.instanceId().equalsIgnoreCase(binding.instanceId())) {
        throw new IllegalArgumentException("scheduler returned duplicate ready server IDs");
      }
    }

    Set<String> knownSoloIds = new HashSet<>(previous.knownSoloIds);
    knownSoloIds.addAll(soloDefinitions);
    Map<String, AccessBinding> activeAccess = new HashMap<>();
    for (BridgeHttpClient.SoloAccessRecord record : accessRecords) {
      String normalized = normalize(record.serverId());
      knownSoloIds.add(normalized);
      if (record.instanceId().isEmpty()) {
        continue;
      }
      InstanceBinding instance = readyInstances.get(normalized);
      if (instance == null
          || !instance.instanceId().equalsIgnoreCase(record.instanceId().get().toString())) {
        continue;
      }
      activeAccess.put(
          normalized,
          new AccessBinding(
              record.serverId(),
              record.instanceId().get(),
              instance.address(),
              record.policy(),
              immutableSet(record.players())));
    }

    return new Snapshot(
        immutableMap(activeAccess),
        immutableMap(readyInstances),
        immutableSet(normalDefinitions),
        immutableSet(soloDefinitions),
        immutableSet(knownSoloIds),
        fallbackNormalIds);
  }

  void publish(Snapshot next) {
    snapshot.set(next);
  }

  Decision connectionDecision(String serverId, UUID playerId, String address) {
    return snapshot.get().connectionDecision(serverId, playerId, address);
  }

  Evaluation transferEvaluation(String serverId, UUID playerId, String address) {
    return snapshot.get().transferEvaluation(serverId, playerId, address);
  }

  boolean isKnownSolo(String serverId) {
    return snapshot.get().isKnownSolo(serverId);
  }

  Snapshot current() {
    return snapshot.get();
  }

  static final class Snapshot {
    private final Map<String, AccessBinding> activeAccess;
    private final Map<String, InstanceBinding> readyInstances;
    private final Set<String> normalDefinitions;
    private final Set<String> soloDefinitions;
    private final Set<String> knownSoloIds;
    private final Set<String> fallbackNormalIds;

    private Snapshot(
        Map<String, AccessBinding> activeAccess,
        Map<String, InstanceBinding> readyInstances,
        Set<String> normalDefinitions,
        Set<String> soloDefinitions,
        Set<String> knownSoloIds,
        Set<String> fallbackNormalIds) {
      this.activeAccess = activeAccess;
      this.readyInstances = readyInstances;
      this.normalDefinitions = normalDefinitions;
      this.soloDefinitions = soloDefinitions;
      this.knownSoloIds = knownSoloIds;
      this.fallbackNormalIds = fallbackNormalIds;
    }

    private Snapshot quarantine() {
      return new Snapshot(
          Collections.emptyMap(),
          readyInstances,
          normalDefinitions,
          soloDefinitions,
          knownSoloIds,
          fallbackNormalIds);
    }

    Decision connectionDecision(String serverId, UUID playerId, String address) {
      String normalized = normalize(serverId);
      AccessBinding access = activeAccess.get(normalized);
      if (access != null) {
        if (address != null && !access.address().equalsIgnoreCase(address)) {
          return Decision.DENIED;
        }
        return access.allows(playerId) ? Decision.ALLOWED : Decision.DENIED;
      }
      if (isNormal(normalized)) {
        return Decision.NORMAL;
      }
      if (isKnownSoloNormalized(normalized)) {
        return Decision.DENIED;
      }
      return Decision.UNKNOWN;
    }

    Evaluation transferEvaluation(String serverId, UUID playerId, String address) {
      String normalized = normalize(serverId);
      AccessBinding access = activeAccess.get(normalized);
      if (access != null) {
        if (!access.address().equalsIgnoreCase(address)) {
          return new Evaluation(Decision.DENIED, DenialReason.STALE_INSTANCE, false);
        }
        if (!access.allows(playerId)) {
          return new Evaluation(Decision.DENIED, DenialReason.UNAUTHORIZED, false);
        }
        return new Evaluation(Decision.ALLOWED, DenialReason.NONE, false);
      }
      if (isNormal(normalized)) {
        InstanceBinding instance = readyInstances.get(normalized);
        if (instance == null) {
          return new Evaluation(Decision.DENIED, DenialReason.STALE_INSTANCE, true);
        }
        if (!instance.address().equalsIgnoreCase(address)) {
          return new Evaluation(Decision.DENIED, DenialReason.STALE_INSTANCE, false);
        }
        return new Evaluation(Decision.NORMAL, DenialReason.NONE, false);
      }
      if (isKnownSoloNormalized(normalized)) {
        return new Evaluation(Decision.DENIED, DenialReason.INACTIVE_SOLO, true);
      }
      return new Evaluation(Decision.UNKNOWN, DenialReason.UNKNOWN, true);
    }

    boolean canRegister(ServerInstance instance) {
      if (instance.state() != ServerInstanceState.READY) {
        return false;
      }
      String normalized = normalize(instance.serverId());
      InstanceBinding ready = readyInstances.get(normalized);
      if (ready == null || !ready.instanceId().equalsIgnoreCase(instance.instanceId())) {
        return false;
      }
      AccessBinding access = activeAccess.get(normalized);
      if (access != null) {
        return access.instanceId().toString().equalsIgnoreCase(instance.instanceId());
      }
      return isNormal(normalized);
    }

    boolean isKnownSolo(String serverId) {
      return isKnownSoloNormalized(normalize(serverId));
    }

    boolean hasActiveAccess(String serverId) {
      return activeAccess.containsKey(normalize(serverId));
    }

    private boolean isKnownSoloNormalized(String normalizedServerId) {
      return soloDefinitions.contains(normalizedServerId)
          || knownSoloIds.contains(normalizedServerId);
    }

    private boolean isNormal(String normalizedServerId) {
      return normalDefinitions.contains(normalizedServerId)
          || fallbackNormalIds.contains(normalizedServerId);
    }
  }

  private record AccessBinding(
      String serverId,
      UUID instanceId,
      String address,
      BridgeHttpClient.SoloAccessPolicy policy,
      Set<UUID> players) {
    private boolean allows(UUID playerId) {
      return policy == BridgeHttpClient.SoloAccessPolicy.OPEN || players.contains(playerId);
    }
  }

  private record InstanceBinding(String serverId, String instanceId, String address) {}

  private static String normalize(String value) {
    return value.toLowerCase(Locale.ROOT);
  }

  private static <T> Set<T> immutableSet(Collection<T> values) {
    return Collections.unmodifiableSet(new HashSet<>(values));
  }

  private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
    return Collections.unmodifiableMap(new HashMap<>(values));
  }
}
