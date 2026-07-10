package com.schedulerbridge.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SchedulerGameDefinition {
  private final int order;
  private final String id;
  private final String serverId;
  private final String name;
  private final List<String> description;
  private final String material;
  private final int customModelData;
  private final String version;
  private final String minVersion;
  private final String maxVersion;
  private final int minPlayers;
  private final int maxPlayers;

  public SchedulerGameDefinition(
      int order,
      String id,
      String serverId,
      String name,
      List<String> description,
      String material,
      int customModelData,
      String version,
      String minVersion,
      String maxVersion,
      int minPlayers,
      int maxPlayers) {
    this.order = order;
    this.id = id;
    this.serverId = serverId;
    this.name = name;
    this.description = Collections.unmodifiableList(new ArrayList<>(description));
    this.material = material;
    this.customModelData = customModelData;
    this.version = version;
    this.minVersion = minVersion;
    this.maxVersion = maxVersion;
    this.minPlayers = minPlayers;
    this.maxPlayers = maxPlayers;
  }

  public int order() {
    return order;
  }

  public String id() {
    return id;
  }

  public String serverId() {
    return serverId;
  }

  public String name() {
    return name;
  }

  public List<String> description() {
    return description;
  }

  public String material() {
    return material;
  }

  public int customModelData() {
    return customModelData;
  }

  public String version() {
    return version;
  }

  public String minVersion() {
    return minVersion;
  }

  public String maxVersion() {
    return maxVersion;
  }

  public int minPlayers() {
    return minPlayers;
  }

  public int maxPlayers() {
    return maxPlayers;
  }
}
