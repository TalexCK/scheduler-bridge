package com.schedulerbridge.common;

import java.util.Objects;

public final class ServerInstance {
  private final String serverId;
  private final String instanceId;
  private final ServerInstanceState state;
  private final long processId;
  private final int port;

  public ServerInstance(
      String serverId, String instanceId, ServerInstanceState state, long processId, int port) {
    this.serverId = Objects.requireNonNull(serverId, "serverId");
    this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
    this.state = Objects.requireNonNull(state, "state");
    this.processId = processId;
    this.port = port;
  }

  public String serverId() {
    return serverId;
  }

  public String instanceId() {
    return instanceId;
  }

  public ServerInstanceState state() {
    return state;
  }

  public long processId() {
    return processId;
  }

  public int port() {
    return port;
  }

  public boolean active() {
    return state == ServerInstanceState.STARTING
        || state == ServerInstanceState.READY
        || state == ServerInstanceState.STOPPING;
  }
}
