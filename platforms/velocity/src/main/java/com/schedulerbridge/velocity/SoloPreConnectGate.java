package com.schedulerbridge.velocity;

import java.util.UUID;

final class SoloPreConnectGate {
  private SoloPreConnectGate() {}

  static boolean shouldDeny(
      SoloAccessRegistry registry,
      boolean currentResultAllowed,
      String serverId,
      String address,
      UUID playerId) {
    if (!currentResultAllowed) {
      return false;
    }
    SoloAccessRegistry.Decision decision =
        registry.connectionDecision(serverId, playerId, address);
    return decision == SoloAccessRegistry.Decision.DENIED
        || decision == SoloAccessRegistry.Decision.UNKNOWN;
  }
}
