package com.schedulerbridge.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

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
  private final boolean solo;
  private final String soloMode;
  private final String soloStartup;
  private final int soloMaxPlayers;
  private final int soloRetentionDays;

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
    this(
        order,
        id,
        serverId,
        name,
        description,
        material,
        customModelData,
        version,
        minVersion,
        maxVersion,
        minPlayers,
        maxPlayers,
        false,
        "shared",
        "on_demand",
        maxPlayers,
        10);
  }

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
      int maxPlayers,
      boolean solo,
      String soloMode,
      String soloStartup,
      int soloMaxPlayers,
      int soloRetentionDays) {
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
    this.solo = solo;
    this.soloMode = requireChoice(soloMode, "solo mode", "shared", "player_world");
    this.soloStartup = requireChoice(soloStartup, "solo startup", "always", "on_demand");
    if (soloMaxPlayers < 1) {
      throw new IllegalArgumentException("solo max players must be positive");
    }
    if (soloRetentionDays < 1) {
      throw new IllegalArgumentException("solo retention days must be positive");
    }
    if (this.soloMode.equals("player_world") && !solo) {
      throw new IllegalArgumentException("player_world mode requires solo to be enabled");
    }
    if (this.soloMode.equals("player_world") && soloMaxPlayers > 2) {
      throw new IllegalArgumentException("player_world mode supports at most two players");
    }
    if (this.soloMode.equals("player_world") && !this.soloStartup.equals("on_demand")) {
      throw new IllegalArgumentException("player_world mode requires on_demand startup");
    }
    this.soloMaxPlayers = soloMaxPlayers;
    this.soloRetentionDays = soloRetentionDays;
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

  public boolean solo() {
    return solo;
  }

  public String soloMode() {
    return soloMode;
  }

  public String soloStartup() {
    return soloStartup;
  }

  public int soloMaxPlayers() {
    return soloMaxPlayers;
  }

  public int soloRetentionDays() {
    return soloRetentionDays;
  }

  private static String requireChoice(String value, String name, String first, String second) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must be " + first + " or " + second);
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    if (!normalized.equals(first) && !normalized.equals(second)) {
      throw new IllegalArgumentException(name + " must be " + first + " or " + second);
    }
    return normalized;
  }
}
