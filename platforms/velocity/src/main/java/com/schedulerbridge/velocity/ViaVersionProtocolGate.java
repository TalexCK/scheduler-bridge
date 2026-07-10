package com.schedulerbridge.velocity;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.platform.ProtocolDetectorService;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;

final class ViaVersionProtocolGate {
  private static final long PROBE_INTERVAL_NANOS = 250_000_000L;
  private final Logger logger;
  private final Map<String, RegisteredServer> servers = new ConcurrentHashMap<>();
  private final Map<String, Long> nextProbeAt = new ConcurrentHashMap<>();
  private final Set<String> probingServers = ConcurrentHashMap.newKeySet();
  private final Set<String> announcedServers = ConcurrentHashMap.newKeySet();
  private final Set<String> announcedFailures = ConcurrentHashMap.newKeySet();
  private final BedWarsRegistryCompatibility bedWarsRegistryCompatibility;

  ViaVersionProtocolGate(Logger logger) {
    this.logger = logger;
    this.bedWarsRegistryCompatibility = new BedWarsRegistryCompatibility(logger);
    this.bedWarsRegistryCompatibility.ensureInstalled();
  }

  void serverRegistered(String serverId, RegisteredServer server) {
    servers.put(serverId, server);
    nextProbeAt.remove(serverId);
    announcedServers.remove(serverId);
    announcedFailures.remove(serverId);
    if (!Via.isLoaded()) {
      return;
    }
    bedWarsRegistryCompatibility.ensureInstalled();
    try {
      detector().uncacheProtocolVersion(serverId);
      requestProbe(serverId);
    } catch (RuntimeException error) {
      logger.warn(
          "Unable to initialize ViaVersion protocol detection for {}: {}",
          serverId,
          error.getMessage());
    }
  }

  void serverUnregistered(String serverId) {
    servers.remove(serverId);
    nextProbeAt.remove(serverId);
    probingServers.remove(serverId);
    announcedServers.remove(serverId);
    announcedFailures.remove(serverId);
    if (!Via.isLoaded()) {
      return;
    }
    try {
      detector().uncacheProtocolVersion(serverId);
    } catch (RuntimeException error) {
      logger.warn(
          "Unable to clear ViaVersion protocol detection for {}: {}", serverId, error.getMessage());
    }
  }

  boolean ready(String serverId) {
    if (!Via.isLoaded()) {
      return false;
    }
    bedWarsRegistryCompatibility.ensureInstalled();
    try {
      boolean ready = detector().detectedProtocolVersions().containsKey(serverId);
      if (ready) {
        if (announcedServers.add(serverId)) {
          logger.info("ViaVersion detected the backend protocol for {}", serverId);
        }
        return true;
      }
      requestProbe(serverId);
      return false;
    } catch (RuntimeException error) {
      logger.warn(
          "Unable to read ViaVersion protocol detection for {}: {}", serverId, error.getMessage());
      return false;
    }
  }

  private void requestProbe(String serverId) {
    RegisteredServer server = servers.get(serverId);
    if (server == null || !Via.isLoaded()) {
      return;
    }
    long now = System.nanoTime();
    Long next = nextProbeAt.get(serverId);
    if (next != null && now < next) {
      return;
    }
    nextProbeAt.put(serverId, now + PROBE_INTERVAL_NANOS);
    if (!probingServers.add(serverId)) {
      return;
    }
    server
        .ping()
        .whenComplete(
            (ping, error) -> {
              probingServers.remove(serverId);
              if (error != null || ping == null || ping.getVersion() == null) {
                if (announcedFailures.add(serverId)) {
                  String message =
                      error == null || error.getMessage() == null
                          ? "backend ping returned no protocol version"
                          : error.getMessage();
                  logger.warn("ViaVersion protocol probe failed for {}: {}", serverId, message);
                }
                return;
              }
              try {
                detector().setProtocolVersion(serverId, ping.getVersion().getProtocol());
                announcedFailures.remove(serverId);
              } catch (RuntimeException detectionError) {
                if (announcedFailures.add(serverId)) {
                  logger.warn(
                      "Unable to store ViaVersion protocol detection for {}: {}",
                      serverId,
                      detectionError.getMessage());
                }
              }
            });
  }

  private ProtocolDetectorService detector() {
    return Via.proxyPlatform().protocolDetectorService();
  }
}
