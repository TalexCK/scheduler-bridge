package com.schedulerbridge.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.schedulerbridge.common.BridgeHttpClient;
import com.schedulerbridge.common.SchedulerGameDefinition;
import com.schedulerbridge.common.ServerInstance;
import com.schedulerbridge.common.ServerInstanceState;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SoloAccessRegistryTest {
  private static final UUID PLAYER_ONE =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID PLAYER_TWO =
      UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID INSTANCE_ONE =
      UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID INSTANCE_TWO =
      UUID.fromString("10000000-0000-0000-0000-000000000002");

  @Test
  void allowsOnlyPlayersBoundToTheCurrentReadyInstance() {
    SoloAccessRegistry registry = registry();
    ServerInstance bingo = instance("Bingo", INSTANCE_ONE, 50001);
    SoloAccessRegistry.Snapshot snapshot =
        registry.prepare(
            List.of("Lobby", "Bingo"),
            List.of(game("Bingo", true)),
            List.of(access("Bingo", INSTANCE_ONE, PLAYER_ONE)),
            List.of(bingo));
    registry.publish(snapshot);

    assertEquals(
        SoloAccessRegistry.Decision.ALLOWED,
        registry.connectionDecision("bingo", PLAYER_ONE, "127.0.0.1:50001"));
    assertEquals(
        SoloAccessRegistry.Decision.DENIED,
        registry.connectionDecision("Bingo", PLAYER_TWO, "127.0.0.1:50001"));
    assertEquals(
        SoloAccessRegistry.Decision.NORMAL,
        registry.connectionDecision("Lobby", PLAYER_TWO, "127.0.0.1:25566"));
    assertEquals(
        SoloAccessRegistry.Decision.UNKNOWN,
        registry.connectionDecision("Other", PLAYER_ONE, "127.0.0.1:50002"));
    assertTrue(snapshot.canRegister(bingo));
    assertFalse(snapshot.canRegister(instance("Bingo", INSTANCE_TWO, 50001)));
    assertEquals(
        SoloAccessRegistry.Decision.ALLOWED,
        registry.transferEvaluation("Bingo", PLAYER_ONE, "127.0.0.1:50001").decision());
    assertEquals(
        SoloAccessRegistry.DenialReason.STALE_INSTANCE,
        registry.transferEvaluation("Bingo", PLAYER_ONE, "127.0.0.1:50002").reason());
  }

  @Test
  void openPolicyAllowsEveryPlayerOnTheCurrentReadyInstance() {
    SoloAccessRegistry registry = registry();
    ServerInstance bingo = instance("Bingo", INSTANCE_ONE, 50001);
    SoloAccessRegistry.Snapshot snapshot =
        registry.prepare(
            List.of("Lobby", "Bingo"),
            List.of(game("Bingo", true)),
            List.of(
                access(
                    "Bingo",
                    INSTANCE_ONE,
                    BridgeHttpClient.SoloAccessPolicy.OPEN,
                    PLAYER_ONE)),
            List.of(bingo));
    registry.publish(snapshot);

    assertEquals(
        SoloAccessRegistry.Decision.ALLOWED,
        registry.connectionDecision("Bingo", PLAYER_ONE, "127.0.0.1:50001"));
    assertEquals(
        SoloAccessRegistry.Decision.ALLOWED,
        registry.connectionDecision("Bingo", PLAYER_TWO, "127.0.0.1:50001"));
    assertEquals(
        SoloAccessRegistry.Decision.ALLOWED,
        registry.transferEvaluation("Bingo", PLAYER_TWO, "127.0.0.1:50001").decision());
    assertTrue(snapshot.canRegister(bingo));
  }

  @Test
  void legacyAndMismatchedAccessNeverBecomeActive() {
    SoloAccessRegistry registry = registry();
    ServerInstance bingo = instance("Bingo", INSTANCE_TWO, 50001);

    SoloAccessRegistry.Snapshot legacy =
        registry.prepare(
            List.of("Bingo"),
            List.of(game("Bingo", true)),
            List.of(new BridgeHttpClient.SoloAccessRecord("Bingo", null, Set.of(PLAYER_ONE))),
            List.of(bingo));
    registry.publish(legacy);
    assertEquals(
        SoloAccessRegistry.Decision.DENIED,
        registry.connectionDecision("Bingo", PLAYER_ONE, "127.0.0.1:50001"));
    assertFalse(legacy.canRegister(bingo));

    SoloAccessRegistry.Snapshot stale =
        registry.prepare(
            List.of("Bingo"),
            List.of(game("Bingo", true)),
            List.of(access("Bingo", INSTANCE_ONE, PLAYER_ONE)),
            List.of(bingo));
    registry.publish(stale);
    assertEquals(
        SoloAccessRegistry.Decision.DENIED,
        registry.connectionDecision("Bingo", PLAYER_ONE, "127.0.0.1:50001"));
    assertFalse(stale.canRegister(bingo));
  }

  @Test
  void replacingAnInstanceCannotReuseThePreviousPlayerList() {
    SoloAccessRegistry registry = registry();
    registry.publish(
        registry.prepare(
            List.of("Bingo"),
            List.of(game("Bingo", true)),
            List.of(access("Bingo", INSTANCE_ONE, PLAYER_ONE)),
            List.of(instance("Bingo", INSTANCE_ONE, 50001))));

    registry.beginReconciliation();
    registry.publish(
        registry.prepare(
            List.of("Bingo"),
            List.of(game("Bingo", true)),
            List.of(access("Bingo", INSTANCE_TWO, PLAYER_TWO)),
            List.of(instance("Bingo", INSTANCE_TWO, 50001))));

    assertEquals(
        SoloAccessRegistry.Decision.DENIED,
        registry.connectionDecision("Bingo", PLAYER_ONE, "127.0.0.1:50001"));
    assertEquals(
        SoloAccessRegistry.Decision.ALLOWED,
        registry.connectionDecision("Bingo", PLAYER_TWO, "127.0.0.1:50001"));
  }

  @Test
  void quarantineAndTombstonesFailClosedWhileNormalServersRemainAllowed() {
    SoloAccessRegistry registry = registry();
    ServerInstance bingo = instance("Bingo-dynamic", INSTANCE_ONE, 50001);
    ServerInstance normal = instance("SkyWars", INSTANCE_TWO, 50002);
    SoloAccessRegistry.Snapshot active =
        registry.prepare(
            List.of("Lobby", "SkyWars", "Bingo"),
            List.of(game("Bingo", true), game("SkyWars", false)),
            List.of(access("Bingo-dynamic", INSTANCE_ONE, PLAYER_ONE)),
            List.of(bingo, normal));
    registry.publish(active);

    registry.beginReconciliation();

    assertEquals(
        SoloAccessRegistry.Decision.DENIED,
        registry.connectionDecision("Bingo-dynamic", PLAYER_ONE, "127.0.0.1:50001"));
    assertEquals(
        SoloAccessRegistry.Decision.NORMAL,
        registry.connectionDecision("SkyWars", PLAYER_TWO, "127.0.0.1:50002"));
    assertTrue(registry.isKnownSolo("Bingo-dynamic"));

    SoloAccessRegistry.Snapshot inactive =
        registry.prepare(
            List.of("Lobby", "SkyWars", "Bingo"),
            List.of(game("Bingo", true), game("SkyWars", false)),
            List.of(),
            List.of(normal));
    registry.publish(inactive);

    assertEquals(
        SoloAccessRegistry.Decision.DENIED,
        registry.connectionDecision("Bingo-dynamic", PLAYER_ONE, "127.0.0.1:50001"));
    assertTrue(
        registry.transferEvaluation("Bingo-dynamic", PLAYER_ONE, "127.0.0.1:50001")
            .retryable());
  }

  @Test
  void preConnectGateDeniesUnknownDeniedAndRedirectedSoloTargets() {
    SoloAccessRegistry registry = registry();
    registry.publish(
        registry.prepare(
            List.of("Lobby", "Bingo"),
            List.of(game("Bingo", true)),
            List.of(access("Bingo", INSTANCE_ONE, PLAYER_ONE)),
            List.of(instance("Bingo", INSTANCE_ONE, 50001))));

    assertTrue(
        SoloPreConnectGate.shouldDeny(
            registry, true, "Bingo", "127.0.0.1:50001", PLAYER_TWO));
    assertTrue(
        SoloPreConnectGate.shouldDeny(
            registry, true, "Unknown", "127.0.0.1:50002", PLAYER_ONE));
    assertFalse(
        SoloPreConnectGate.shouldDeny(
            registry, true, "Bingo", "127.0.0.1:50001", PLAYER_ONE));
    assertFalse(
        SoloPreConnectGate.shouldDeny(
            registry, true, "Lobby", "127.0.0.1:25566", PLAYER_TWO));
    assertFalse(
        SoloPreConnectGate.shouldDeny(
            registry, false, "Bingo", "127.0.0.1:50001", PLAYER_TWO));
    assertTrue(
        SoloConnectedGate.shouldRedirect(
            registry, "Bingo", "127.0.0.1:50001", PLAYER_TWO));
    assertTrue(
        SoloConnectedGate.shouldRedirect(
            registry, "Unknown", "127.0.0.1:50002", PLAYER_ONE));
    assertFalse(
        SoloConnectedGate.shouldRedirect(
            registry, "Lobby", "127.0.0.1:25566", PLAYER_TWO));
    assertEquals(List.of("Proxy", "Lobby"), SoloConnectedGate.fallbackIds("Proxy", "Bingo"));
    assertEquals(List.of(), SoloConnectedGate.fallbackIds("Lobby", "Lobby"));
  }

  @Test
  void concurrentReadersNeverFailOpenKnownSoloIds() throws Exception {
    SoloAccessRegistry registry = registry();
    registry.publish(
        registry.prepare(
            List.of("Bingo"),
            List.of(game("Bingo", true)),
            List.of(access("Bingo", INSTANCE_ONE, PLAYER_ONE)),
            List.of(instance("Bingo", INSTANCE_ONE, 50001))));
    ExecutorService executor = Executors.newFixedThreadPool(5);
    CountDownLatch complete = new CountDownLatch(5);
    AtomicBoolean failedOpen = new AtomicBoolean();

    for (int worker = 0; worker < 4; worker++) {
      executor.execute(
          () -> {
            for (int index = 0; index < 3000; index++) {
              SoloAccessRegistry.Decision decision =
                  registry.connectionDecision("Bingo", PLAYER_TWO, "127.0.0.1:50001");
              if (decision == SoloAccessRegistry.Decision.NORMAL
                  || decision == SoloAccessRegistry.Decision.UNKNOWN
                  || decision == SoloAccessRegistry.Decision.ALLOWED) {
                failedOpen.set(true);
              }
            }
            complete.countDown();
          });
    }
    executor.execute(
        () -> {
          for (int index = 0; index < 1000; index++) {
            UUID instanceId = index % 2 == 0 ? INSTANCE_ONE : INSTANCE_TWO;
            registry.beginReconciliation();
            registry.publish(
                registry.prepare(
                    List.of("Bingo"),
                    List.of(game("Bingo", true)),
                    List.of(access("Bingo", instanceId, PLAYER_ONE)),
                    List.of(instance("Bingo", instanceId, 50001))));
          }
          complete.countDown();
        });

    assertTrue(complete.await(10, TimeUnit.SECONDS));
    assertFalse(failedOpen.get());
    executor.shutdownNow();
  }

  private static SoloAccessRegistry registry() {
    return new SoloAccessRegistry("Proxy", "Lobby");
  }

  private static BridgeHttpClient.SoloAccessRecord access(
      String serverId, UUID instanceId, UUID... players) {
    return new BridgeHttpClient.SoloAccessRecord(serverId, instanceId, List.of(players));
  }

  private static BridgeHttpClient.SoloAccessRecord access(
      String serverId,
      UUID instanceId,
      BridgeHttpClient.SoloAccessPolicy policy,
      UUID... players) {
    return new BridgeHttpClient.SoloAccessRecord(
        serverId, instanceId, policy, List.of(players));
  }

  private static ServerInstance instance(String serverId, UUID instanceId, int port) {
    return new ServerInstance(
        serverId, instanceId.toString(), ServerInstanceState.READY, 1L, port);
  }

  private static SchedulerGameDefinition game(String serverId, boolean solo) {
    return new SchedulerGameDefinition(
        1,
        serverId.toLowerCase(),
        serverId,
        serverId,
        List.of(),
        "STONE",
        0,
        null,
        null,
        null,
        1,
        2,
        solo,
        "shared",
        "on_demand",
        2,
        10);
  }
}
