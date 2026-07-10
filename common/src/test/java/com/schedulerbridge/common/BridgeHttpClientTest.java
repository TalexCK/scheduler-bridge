package com.schedulerbridge.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BridgeHttpClientTest {
  @Test
  void parsesAndMergesSoloAccessRecords() throws Exception {
    UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
    String server = Base64.getEncoder().encodeToString("Bingo-1".getBytes(StandardCharsets.UTF_8));

    List<BridgeHttpClient.SoloAccessRecord> access =
        BridgeHttpClient.parseSoloAccess(
            server
                + "\t"
                + first
                + "\n"
                + Base64.getEncoder()
                    .encodeToString("bingo-1".getBytes(StandardCharsets.UTF_8))
                + "\t"
                + second
                + ","
                + first);

    assertEquals(1, access.size());
    assertEquals("Bingo-1", access.get(0).serverId());
    assertFalse(access.get(0).instanceId().isPresent());
    assertEquals(BridgeHttpClient.SoloAccessPolicy.ROSTER, access.get(0).policy());
    assertEquals(
        new HashSet<>(java.util.Arrays.asList(first, second)), access.get(0).players());
    assertThrows(UnsupportedOperationException.class, () -> access.add(access.get(0)));
    assertThrows(UnsupportedOperationException.class, () -> access.get(0).players().add(first));
  }

  @Test
  void rejectsInvalidSoloAccessRecords() {
    assertThrows(IOException.class, () -> BridgeHttpClient.parseSoloAccess("not-a-record"));
    String server = Base64.getEncoder().encodeToString("Bingo-1".getBytes(StandardCharsets.UTF_8));
    assertThrows(IOException.class, () -> BridgeHttpClient.parseSoloAccess(server + "\tinvalid"));
    assertThrows(IOException.class, () -> BridgeHttpClient.parseSoloAccess("***\t"));
    assertThrows(
        IOException.class,
        () -> BridgeHttpClient.parseSoloAccess(server + "\tnot-a-uuid\t"));
  }

  @Test
  void acceptsAnEmptySoloAccessList() throws Exception {
    String server = Base64.getEncoder().encodeToString("Puzzle-1".getBytes(StandardCharsets.UTF_8));

    List<BridgeHttpClient.SoloAccessRecord> access =
        BridgeHttpClient.parseSoloAccess(server + "\t");

    assertEquals(1, access.size());
    assertEquals("Puzzle-1", access.get(0).serverId());
    assertTrue(access.get(0).players().isEmpty());
    assertFalse(access.get(0).instanceId().isPresent());
    assertEquals(BridgeHttpClient.SoloAccessPolicy.ROSTER, access.get(0).policy());
  }

  @Test
  void parsesLegacyInstanceBoundRosterAccess() throws Exception {
    UUID instance = UUID.fromString("10000000-0000-0000-0000-000000000001");
    UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
    String server = encoded("Bingo-1");

    List<BridgeHttpClient.SoloAccessRecord> access =
        BridgeHttpClient.parseSoloAccess(server + "\t" + instance + "\t" + player);

    assertEquals(instance, access.get(0).instanceId().get());
    assertEquals(BridgeHttpClient.SoloAccessPolicy.ROSTER, access.get(0).policy());
    assertEquals(Collections.singleton(player), access.get(0).players());
  }

  @Test
  void parsesOpenAndRosterPolicies() throws Exception {
    UUID openInstance = UUID.fromString("10000000-0000-0000-0000-000000000001");
    UUID rosterInstance = UUID.fromString("10000000-0000-0000-0000-000000000002");
    UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");

    List<BridgeHttpClient.SoloAccessRecord> access =
        BridgeHttpClient.parseSoloAccess(
            encoded("Bingo")
                + "\t"
                + openInstance
                + "\topen\t"
                + first
                + "\n"
                + encoded("Puzzle")
                + "\t"
                + rosterInstance
                + "\troster\t"
                + second);

    assertEquals(2, access.size());
    assertEquals(BridgeHttpClient.SoloAccessPolicy.OPEN, access.get(0).policy());
    assertEquals(Collections.singleton(first), access.get(0).players());
    assertEquals(BridgeHttpClient.SoloAccessPolicy.ROSTER, access.get(1).policy());
    assertEquals(Collections.singleton(second), access.get(1).players());
  }

  @Test
  void rejectsInvalidPoliciesAndConflictingRecords() {
    UUID instance = UUID.fromString("10000000-0000-0000-0000-000000000001");
    UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
    String server = encoded("Bingo-1");

    assertThrows(
        IOException.class,
        () -> BridgeHttpClient.parseSoloAccess(server + "\t" + instance + "\tpublic\t"));
    assertThrows(
        IOException.class,
        () -> BridgeHttpClient.parseSoloAccess(server + "\t" + instance + "\tOPEN\t"));
    assertThrows(
        IOException.class, () -> BridgeHttpClient.parseSoloAccess(server + "\t\topen\t"));
    assertThrows(
        IOException.class,
        () ->
            BridgeHttpClient.parseSoloAccess(
                server
                    + "\t"
                    + instance
                    + "\t"
                    + player
                    + "\n"
                    + server
                    + "\t10000000-0000-0000-0000-000000000002\t"
                    + player));
    assertThrows(
        IOException.class,
        () ->
            BridgeHttpClient.parseSoloAccess(
                server
                    + "\t"
                    + instance
                    + "\t"
                    + player
                    + "\n"
                    + server
                    + "\t"
                    + instance
                    + "\topen\t"
                    + player));
  }

  @Test
  void parsesLegacyAndExtendedGameRecords() throws Exception {
    String legacy =
        gamePrefix()
            + "\t1\t8";
    SchedulerGameDefinition legacyGame = BridgeHttpClient.parseGame(legacy);

    assertFalse(legacyGame.solo());
    assertEquals("shared", legacyGame.soloMode());
    assertEquals("on_demand", legacyGame.soloStartup());
    assertEquals(8, legacyGame.soloMaxPlayers());
    assertEquals(10, legacyGame.soloRetentionDays());

    String extended =
        legacy
            + "\ttrue\t"
            + encoded("player_world")
            + "\t"
            + encoded("on_demand")
            + "\t2\t10";
    SchedulerGameDefinition soloGame = BridgeHttpClient.parseGame(extended);

    assertTrue(soloGame.solo());
    assertEquals("player_world", soloGame.soloMode());
    assertEquals("on_demand", soloGame.soloStartup());
    assertEquals(2, soloGame.soloMaxPlayers());
    assertEquals(10, soloGame.soloRetentionDays());
  }

  @Test
  void rejectsInvalidGameColumnsAndBoolean() {
    assertThrows(IOException.class, () -> BridgeHttpClient.parseGame("1\t2"));
    assertThrows(
        IOException.class,
        () ->
            BridgeHttpClient.parseGame(
                gamePrefix()
                    + "\t1\t8\tnot-boolean\t"
                    + encoded("shared")
                    + "\t"
                    + encoded("always")
                    + "\t2\t10"));
  }

  private static String gamePrefix() {
    return "1\t"
        + encoded("bingo")
        + "\t"
        + encoded("Bingo")
        + "\t"
        + encoded("&bBingo")
        + "\t"
        + encoded("line one\u001fline two")
        + "\t"
        + encoded("MAP")
        + "\t0\t"
        + encoded("26.2")
        + "\t"
        + encoded("26.2")
        + "\t"
        + encoded("26.2");
  }

  private static String encoded(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
