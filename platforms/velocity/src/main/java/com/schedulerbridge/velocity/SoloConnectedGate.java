package com.schedulerbridge.velocity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class SoloConnectedGate {
  private SoloConnectedGate() {}

  static boolean shouldRedirect(
      SoloAccessRegistry registry,
      String serverId,
      String address,
      UUID playerId) {
    SoloAccessRegistry.Decision decision =
        registry.connectionDecision(serverId, playerId, address);
    return decision == SoloAccessRegistry.Decision.DENIED
        || decision == SoloAccessRegistry.Decision.UNKNOWN;
  }

  static List<String> fallbackIds(String schedulerServerId, String currentServerId) {
    List<String> fallback = new ArrayList<>();
    if (schedulerServerId != null
        && !schedulerServerId.isBlank()
        && !schedulerServerId.equalsIgnoreCase(currentServerId)) {
      fallback.add(schedulerServerId);
    }
    if (!"Lobby".equalsIgnoreCase(currentServerId)
        && fallback.stream().noneMatch(id -> id.equalsIgnoreCase("Lobby"))) {
      fallback.add("Lobby");
    }
    return List.copyOf(fallback);
  }
}
