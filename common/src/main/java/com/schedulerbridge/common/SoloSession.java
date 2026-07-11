package com.schedulerbridge.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class SoloSession {
  private final String gameId;
  private final String sessionId;
  private final UUID owner;
  private final List<UUID> players;

  public SoloSession(String gameId, String sessionId, UUID owner, List<UUID> players) {
    this.gameId = Objects.requireNonNull(gameId, "gameId");
    this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    this.owner = Objects.requireNonNull(owner, "owner");
    this.players =
        Collections.unmodifiableList(
            new ArrayList<>(Objects.requireNonNull(players, "players")));
    if (!this.players.contains(owner)) {
      throw new IllegalArgumentException("solo session players must contain the owner");
    }
  }

  public String gameId() {
    return gameId;
  }

  public String sessionId() {
    return sessionId;
  }

  public UUID owner() {
    return owner;
  }

  public List<UUID> players() {
    return players;
  }
}
