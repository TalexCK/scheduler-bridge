package com.schedulerbridge.velocity;

import com.google.inject.Inject;
import com.schedulerbridge.common.BridgeHttpClient;
import com.schedulerbridge.common.GameBridgeReporter;
import com.schedulerbridge.common.SchedulerBridge;
import com.schedulerbridge.common.ServerInstance;
import com.schedulerbridge.common.ServerInstanceState;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

@Plugin(
    id = "scheduler_bridge",
    name = "Scheduler Bridge",
    version = "0.1.0-SNAPSHOT",
    dependencies = {@Dependency(id = "viaversion")},
    description = "Scheduler bridge for Velocity and dynamically managed Minecraft servers.")
public final class SchedulerBridgeVelocityPlugin {
  private static final String NETWORK_PERMISSION = "scheduler.admin";
  private static final List<String> NETWORK_SUBCOMMANDS =
      Arrays.asList(
          "help", "list", "players", "start", "stop", "restart", "log", "command", "transfer");
  private final ProxyServer proxyServer;
  private final SchedulerBridge bridge;
  private final Logger logger;
  private BridgeHttpClient client;
  private ScheduledTask pollTask;
  private ScheduledTask transferDispatchTask;
  private ScheduledTask playerSyncTask;
  private ScheduledTask serverSyncTask;
  private CommandMeta pingCommandMeta;
  private CommandMeta hubCommandMeta;
  private CommandMeta networkCommandMeta;
  private GameBridgeReporter reporter;
  private final Set<String> managedServers = ConcurrentHashMap.newKeySet();
  private final Map<String, BridgeHttpClient.TransferRequest> deferredTransfers =
      new ConcurrentHashMap<>();
  private final Map<String, Long> deferredTransferExpiresAt = new ConcurrentHashMap<>();
  private final AtomicBoolean transferPollRunning = new AtomicBoolean();
  private final String schedulerServerId = System.getenv("SCHEDULER_SERVER_ID");
  private final long transferTimeoutMillis = resolveTransferTimeoutMillis();
  private final ViaVersionProtocolGate viaVersionGate;
  private volatile List<ServerInstance> schedulerInstances = Collections.emptyList();
  private volatile List<String> schedulerDefinitions = Collections.emptyList();

  @Inject
  public SchedulerBridgeVelocityPlugin(ProxyServer proxyServer, Logger logger) {
    this.proxyServer = proxyServer;
    this.logger = logger;
    this.bridge = SchedulerBridge.create(new VelocitySchedulerAdapter(proxyServer, this));
    this.viaVersionGate = new ViaVersionProtocolGate(logger);
  }

  @Subscribe
  public void onInitialize(ProxyInitializeEvent event) {
    CommandManager commandManager = proxyServer.getCommandManager();
    pingCommandMeta = commandManager.metaBuilder("ping").plugin(this).build();
    commandManager.register(pingCommandMeta, new PingCommand());
    hubCommandMeta = commandManager.metaBuilder("hub").plugin(this).build();
    commandManager.register(hubCommandMeta, new HubCommand());
    networkCommandMeta = commandManager.metaBuilder("network").plugin(this).build();
    commandManager.register(networkCommandMeta, new NetworkCommand());
    try {
      client = BridgeHttpClient.fromEnvironment();
      pollTask =
          proxyServer
              .getScheduler()
              .buildTask(this, this::pollTransfers)
              .delay(Duration.ofSeconds(1))
              .repeat(Duration.ofSeconds(1))
              .schedule();
      transferDispatchTask =
          proxyServer
              .getScheduler()
              .buildTask(this, this::dispatchTransfers)
              .delay(Duration.ofMillis(50))
              .repeat(Duration.ofMillis(50))
              .schedule();
      playerSyncTask =
          proxyServer
              .getScheduler()
              .buildTask(this, this::syncPlayers)
              .delay(Duration.ofSeconds(1))
              .repeat(Duration.ofSeconds(10))
              .schedule();
      serverSyncTask =
          proxyServer
              .getScheduler()
              .buildTask(this, this::syncServers)
              .delay(Duration.ofSeconds(1))
              .repeat(Duration.ofSeconds(2))
              .schedule();
      try {
        reporter = GameBridgeReporter.fromEnvironment(message -> logger.warn(message));
        reporter.start();
      } catch (RuntimeException error) {
        logger.warn(error.getMessage());
      }
    } catch (RuntimeException error) {
      logger.error(error.getMessage());
    }
  }

  @Subscribe
  public void onShutdown(ProxyShutdownEvent event) {
    if (client != null) {
      try {
        client.updatePlayers(Collections.emptyList());
      } catch (Exception error) {
        logger.warn(error.getMessage());
      }
    }
    if (pollTask != null) {
      pollTask.cancel();
    }
    if (transferDispatchTask != null) {
      transferDispatchTask.cancel();
    }
    if (playerSyncTask != null) {
      playerSyncTask.cancel();
    }
    if (serverSyncTask != null) {
      serverSyncTask.cancel();
    }
    if (pingCommandMeta != null) {
      proxyServer.getCommandManager().unregister(pingCommandMeta);
    }
    if (hubCommandMeta != null) {
      proxyServer.getCommandManager().unregister(hubCommandMeta);
    }
    if (networkCommandMeta != null) {
      proxyServer.getCommandManager().unregister(networkCommandMeta);
    }
    if (reporter != null) {
      reporter.close();
    }
    deferredTransfers.clear();
    deferredTransferExpiresAt.clear();
  }

  @Subscribe
  public void onPostLogin(PostLoginEvent event) {
    schedulePlayerSync();
  }

  @Subscribe
  public void onServerConnected(ServerConnectedEvent event) {
    schedulePlayerSync();
  }

  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    String uuid = event.getPlayer().getUniqueId().toString();
    String username = event.getPlayer().getUsername();
    for (BridgeHttpClient.TransferRequest transfer : deferredTransfers.values()) {
      if ((transfer.player().equalsIgnoreCase(uuid) || transfer.player().equalsIgnoreCase(username))
          && deferredTransfers.remove(transfer.transferId(), transfer)) {
        deferredTransferExpiresAt.remove(transfer.transferId());
        report(transfer.transferId(), false, "player is not connected to Velocity");
      }
    }
    schedulePlayerSync();
  }

  private void pollTransfers() {
    if (client == null || !transferPollRunning.compareAndSet(false, true)) {
      return;
    }
    try {
      for (BridgeHttpClient.TransferRequest transfer : client.pendingTransfers()) {
        if (deferredTransfers.putIfAbsent(transfer.transferId(), transfer) == null) {
          deferredTransferExpiresAt.put(
              transfer.transferId(), System.currentTimeMillis() + transferTimeoutMillis);
        }
      }
    } catch (Exception error) {
      logger.warn(error.getMessage());
    } finally {
      transferPollRunning.set(false);
    }
  }

  private void dispatchTransfers() {
    for (BridgeHttpClient.TransferRequest transfer : deferredTransfers.values()) {
      try {
        Long expiresAt = deferredTransferExpiresAt.get(transfer.transferId());
        if (expiresAt != null && System.currentTimeMillis() >= expiresAt) {
          if (deferredTransfers.remove(transfer.transferId(), transfer)) {
            deferredTransferExpiresAt.remove(transfer.transferId());
            report(transfer.transferId(), false, "transfer expired before dispatch");
          }
          continue;
        }
        RegisteredServer server = registerManagedServer(transfer.serverId(), transfer.address());
        if (!viaVersionGate.ready(transfer.serverId())) {
          continue;
        }
        if (deferredTransfers.remove(transfer.transferId(), transfer)) {
          deferredTransferExpiresAt.remove(transfer.transferId());
          transfer(transfer, server);
        }
      } catch (Exception error) {
        if (deferredTransfers.remove(transfer.transferId(), transfer)) {
          deferredTransferExpiresAt.remove(transfer.transferId());
          report(transfer.transferId(), false, error.getMessage());
        }
      }
    }
  }

  private void schedulePlayerSync() {
    proxyServer
        .getScheduler()
        .buildTask(this, this::syncPlayers)
        .delay(Duration.ofMillis(250))
        .schedule();
  }

  private void syncPlayers() {
    if (client == null) {
      return;
    }
    List<BridgeHttpClient.PlayerSnapshot> players = new ArrayList<>();
    for (Player player : proxyServer.getAllPlayers()) {
      String serverId =
          player
              .getCurrentServer()
              .map(connection -> connection.getServerInfo().getName())
              .orElse("");
      players.add(
          new BridgeHttpClient.PlayerSnapshot(
              player.getUniqueId().toString(), player.getUsername(), player.getPing(), serverId));
    }
    try {
      client.updatePlayers(players);
    } catch (Exception error) {
      logger.warn(error.getMessage());
    }
  }

  private void syncServers() {
    if (client == null) {
      return;
    }
    try {
      Set<String> readyServers = ConcurrentHashMap.newKeySet();
      List<ServerInstance> instances = client.servers();
      schedulerInstances = Collections.unmodifiableList(new ArrayList<>(instances));
      for (ServerInstance instance : instances) {
        if (instance.state() != ServerInstanceState.READY
            || instance.serverId().equalsIgnoreCase(schedulerServerId)) {
          continue;
        }
        registerManagedServer(instance.serverId(), "127.0.0.1:" + instance.port());
        readyServers.add(instance.serverId());
      }
      for (String serverId : new ArrayList<>(managedServers)) {
        if (readyServers.contains(serverId)) {
          continue;
        }
        proxyServer
            .getServer(serverId)
            .ifPresent(server -> proxyServer.unregisterServer(server.getServerInfo()));
        viaVersionGate.serverUnregistered(serverId);
      }
      managedServers.clear();
      managedServers.addAll(readyServers);
      try {
        schedulerDefinitions =
            Collections.unmodifiableList(new ArrayList<>(client.serverDefinitions()));
      } catch (Exception error) {
        logger.warn(error.getMessage());
      }
    } catch (Exception error) {
      logger.warn(error.getMessage());
    }
  }

  private void transfer(BridgeHttpClient.TransferRequest transfer, RegisteredServer server) {
    try {
      Optional<Player> player = findPlayer(transfer.player());
      if (!player.isPresent()) {
        report(transfer.transferId(), false, "player is not connected to Velocity");
        return;
      }
      player
          .get()
          .createConnectionRequest(server)
          .connect()
          .whenComplete(
              (result, error) -> {
                if (error != null) {
                  report(transfer.transferId(), false, error.getMessage());
                } else {
                  boolean success =
                      result.isSuccessful()
                          || result.getStatus()
                              == ConnectionRequestBuilder.Status.ALREADY_CONNECTED;
                  report(transfer.transferId(), success, result.getStatus().name());
                }
              });
    } catch (Exception error) {
      report(transfer.transferId(), false, error.getMessage());
    }
  }

  private RegisteredServer registerManagedServer(String serverId, String address) {
    Optional<RegisteredServer> previous = proxyServer.getServer(serverId);
    RegisteredServer server = registerServer(serverId, address);
    boolean changed = previous.isEmpty() || previous.get() != server;
    boolean newlyManaged = managedServers.add(serverId);
    if (changed || newlyManaged) {
      viaVersionGate.serverRegistered(serverId, server);
    }
    return server;
  }

  private RegisteredServer registerServer(String serverId, String address) {
    int separator = address.lastIndexOf(':');
    if (separator <= 0 || separator == address.length() - 1) {
      throw new IllegalArgumentException("invalid server address: " + address);
    }
    InetSocketAddress socket =
        new InetSocketAddress(
            address.substring(0, separator), Integer.parseInt(address.substring(separator + 1)));
    Optional<RegisteredServer> existing = proxyServer.getServer(serverId);
    if (existing.isPresent() && existing.get().getServerInfo().getAddress().equals(socket)) {
      return existing.get();
    }
    if (existing.isPresent()) {
      proxyServer.unregisterServer(existing.get().getServerInfo());
    }
    return proxyServer.registerServer(new ServerInfo(serverId, socket));
  }

  private Optional<Player> findPlayer(String value) {
    try {
      return proxyServer.getPlayer(UUID.fromString(value));
    } catch (IllegalArgumentException ignored) {
      return proxyServer.getPlayer(value);
    }
  }

  private void report(String transferId, boolean success, String message) {
    CompletableFuture.runAsync(
        () -> {
          try {
            client.transferResult(transferId, success, message);
          } catch (Exception error) {
            logger.warn(error.getMessage());
          }
        });
  }

  public ProxyServer proxyServer() {
    return proxyServer;
  }

  public SchedulerBridge bridge() {
    return bridge;
  }

  private final class PingCommand implements SimpleCommand {
    @Override
    public void execute(Invocation invocation) {
      List<Player> players = new ArrayList<>(proxyServer.getAllPlayers());
      players.sort(
          Comparator.comparing(
                  (Player player) ->
                      player
                          .getCurrentServer()
                          .map(connection -> connection.getServerInfo().getName())
                          .orElse(""),
                  String.CASE_INSENSITIVE_ORDER)
              .thenComparing(Player::getUsername, String.CASE_INSENSITIVE_ORDER));
      invocation
          .source()
          .sendMessage(
              Component.text("Online players: ", NamedTextColor.GOLD)
                  .append(Component.text(players.size(), NamedTextColor.GREEN)));
      for (Player player : players) {
        String serverId =
            player
                .getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse("connecting");
        invocation.source().sendMessage(formatPingPlayer(player, serverId));
      }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
      return true;
    }
  }

  private final class HubCommand implements SimpleCommand {
    @Override
    public void execute(Invocation invocation) {
      if (!(invocation.source() instanceof Player player)) {
        invocation.source().sendMessage(Component.text("Only players can use /hub."));
        return;
      }
      Optional<RegisteredServer> hub = proxyServer.getServer("Lobby");
      if (!hub.isPresent()) {
        player.sendMessage(Component.text("Lobby is not available."));
        return;
      }
      if (player
          .getCurrentServer()
          .map(connection -> connection.getServerInfo().getName().equalsIgnoreCase("Lobby"))
          .orElse(false)) {
        player.sendMessage(Component.text("You are already connected to Lobby."));
        return;
      }
      player
          .createConnectionRequest(hub.get())
          .connect()
          .whenComplete(
              (result, error) -> {
                if (error != null) {
                  player.sendMessage(Component.text("Unable to connect to Lobby."));
                  logger.warn(error.getMessage());
                  return;
                }
                if (!result.isSuccessful()
                    && result.getStatus() != ConnectionRequestBuilder.Status.ALREADY_CONNECTED) {
                  player.sendMessage(
                      Component.text("Unable to connect to Lobby: " + result.getStatus().name()));
                }
              });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
      return true;
    }
  }

  private final class NetworkCommand implements SimpleCommand {
    @Override
    public void execute(Invocation invocation) {
      String[] arguments = invocation.arguments();
      if (arguments.length == 0 || arguments[0].equalsIgnoreCase("help")) {
        sendNetworkHelp(invocation.source());
        return;
      }
      if (client == null) {
        invocation
            .source()
            .sendMessage(Component.text("Network bridge is unavailable.", NamedTextColor.RED));
        return;
      }
      String subcommand = arguments[0].toLowerCase(Locale.ROOT);
      switch (subcommand) {
        case "list":
          runManagement(
              invocation.source(),
              () -> {
                List<ServerInstance> instances = client.servers();
                schedulerInstances = Collections.unmodifiableList(new ArrayList<>(instances));
                invocation
                    .source()
                    .sendMessage(
                        Component.text("Network instances: ", NamedTextColor.GOLD)
                            .append(Component.text(instances.size(), NamedTextColor.YELLOW)));
                for (ServerInstance instance : instances) {
                  invocation.source().sendMessage(formatInstance(instance));
                }
              });
          return;
        case "players":
          runManagement(
              invocation.source(),
              () -> {
                List<BridgeHttpClient.PlayerSnapshot> players = client.players();
                invocation
                    .source()
                    .sendMessage(
                        Component.text("Network players: ", NamedTextColor.GOLD)
                            .append(Component.text(players.size(), NamedTextColor.YELLOW)));
                for (BridgeHttpClient.PlayerSnapshot player : players) {
                  String server =
                      player.serverId().isEmpty() ? "connecting" : player.serverId() + "-1";
                  String ping = player.ping() < 0 ? "unknown" : player.ping() + " ms";
                  invocation
                      .source()
                      .sendMessage(formatPlayer(player.username(), server, ping, player.uuid()));
                }
              });
          return;
        case "start":
          if (!requireArguments(
              invocation.source(), arguments, 2, "/network start <server-id> [players...]")) {
            return;
          }
          runManagement(
              invocation.source(),
              () -> {
                ServerInstance instance =
                    client.launchServer(arguments[1], normalizePlayers(arguments, 2));
                invocation
                    .source()
                    .sendMessage(
                        Component.text("Server start accepted: ", NamedTextColor.GREEN)
                            .append(formatInstance(instance)));
              });
          return;
        case "stop":
          if (!requireArguments(invocation.source(), arguments, 2, "/network stop <instance-id>")) {
            return;
          }
          runManagement(
              invocation.source(),
              () -> {
                String serverId = resolveServerId(arguments[1], client.servers());
                ServerInstance instance = client.terminateServer(serverId);
                invocation
                    .source()
                    .sendMessage(
                        Component.text("Server stop accepted: ", NamedTextColor.GREEN)
                            .append(formatInstance(instance)));
              });
          return;
        case "restart":
          if (!requireArguments(
              invocation.source(), arguments, 2, "/network restart <instance-id>")) {
            return;
          }
          runManagement(
              invocation.source(),
              () -> {
                String serverId = resolveServerId(arguments[1], client.servers());
                ServerInstance instance = client.restartServer(serverId);
                invocation
                    .source()
                    .sendMessage(
                        Component.text("Server restart accepted: ", NamedTextColor.GREEN)
                            .append(formatInstance(instance)));
              });
          return;
        case "log":
        case "logs":
          if (!requireArguments(
              invocation.source(), arguments, 2, "/network log <instance-id> [lines]")) {
            return;
          }
          int lines;
          try {
            lines = arguments.length >= 3 ? Integer.parseInt(arguments[2]) : 50;
          } catch (NumberFormatException error) {
            invocation
                .source()
                .sendMessage(
                    Component.text("Log line count must be a number.", NamedTextColor.RED));
            return;
          }
          int requestedLines = Math.max(1, Math.min(lines, 200));
          runManagement(
              invocation.source(),
              () -> {
                String serverId = resolveServerId(arguments[1], client.servers());
                List<String> output = client.serverLogs(serverId, requestedLines);
                invocation
                    .source()
                    .sendMessage(
                        Component.text("Log output for ", NamedTextColor.GOLD)
                            .append(Component.text(serverId, NamedTextColor.AQUA))
                            .append(Component.text(" (", NamedTextColor.DARK_GRAY))
                            .append(Component.text(output.size(), NamedTextColor.YELLOW))
                            .append(Component.text(" lines):", NamedTextColor.DARK_GRAY)));
                sendLines(
                    invocation.source(),
                    output,
                    "No log output is available.",
                    NamedTextColor.GRAY);
              });
          return;
        case "command":
          if (!requireArguments(
              invocation.source(), arguments, 3, "/network command <instance-id> <command...>")) {
            return;
          }
          String serverCommand =
              String.join(" ", Arrays.copyOfRange(arguments, 2, arguments.length));
          runManagement(
              invocation.source(),
              () -> {
                String serverId = resolveServerId(arguments[1], client.servers());
                List<String> output = client.sendCommand(serverId, serverCommand);
                sendLines(
                    invocation.source(),
                    output,
                    "Command submitted; no output was captured.",
                    NamedTextColor.GRAY);
              });
          return;
        case "transfer":
        case "send":
          if (!requireArguments(
              invocation.source(), arguments, 3, "/network transfer <instance-id> <players...>")) {
            return;
          }
          runManagement(
              invocation.source(),
              () -> {
                String serverId = resolveServerId(arguments[1], client.servers());
                List<String> players = normalizePlayers(arguments, 2);
                client.queueTransfers(serverId, players);
                invocation
                    .source()
                    .sendMessage(
                        Component.text("Queued ", NamedTextColor.GREEN)
                            .append(Component.text(players.size(), NamedTextColor.YELLOW))
                            .append(Component.text(" player transfer(s) to ", NamedTextColor.GREEN))
                            .append(Component.text(serverId, NamedTextColor.AQUA))
                            .append(Component.text(".", NamedTextColor.GREEN)));
              });
          return;
        default:
          sendNetworkHelp(invocation.source());
      }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
      String[] arguments = invocation.arguments();
      if (arguments.length == 0) {
        return NETWORK_SUBCOMMANDS;
      }
      if (arguments.length == 1) {
        return filterSuggestions(NETWORK_SUBCOMMANDS, arguments[0]);
      }
      String subcommand = arguments[0].toLowerCase(Locale.ROOT);
      if (arguments.length == 2) {
        if (subcommand.equals("start")) {
          return filterSuggestions(schedulerDefinitions, arguments[1]);
        }
        if (Arrays.asList("stop", "restart", "log", "logs", "command", "transfer", "send")
            .contains(subcommand)) {
          List<String> instances =
              schedulerInstances.stream().map(SchedulerBridgeVelocityPlugin::displayId).toList();
          return filterSuggestions(instances, arguments[1]);
        }
      }
      if (arguments.length == 3 && (subcommand.equals("log") || subcommand.equals("logs"))) {
        return filterSuggestions(Arrays.asList("50", "100", "200"), arguments[2]);
      }
      if ((subcommand.equals("start") || subcommand.equals("transfer") || subcommand.equals("send"))
          && arguments.length >= 3) {
        List<String> players =
            proxyServer.getAllPlayers().stream().map(Player::getUsername).sorted().toList();
        return filterSuggestions(players, arguments[arguments.length - 1]);
      }
      return Collections.emptyList();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
      return !(invocation.source() instanceof Player)
          || invocation.source().hasPermission(NETWORK_PERMISSION);
    }
  }

  private void sendNetworkHelp(CommandSource source) {
    source.sendMessage(Component.text("Network management commands:", NamedTextColor.GOLD));
    source.sendMessage(Component.text("/network list", NamedTextColor.AQUA));
    source.sendMessage(Component.text("/network players", NamedTextColor.AQUA));
    source.sendMessage(
        Component.text("/network start ", NamedTextColor.AQUA)
            .append(Component.text("<server-id> [players...]", NamedTextColor.GRAY)));
    source.sendMessage(
        Component.text("/network stop ", NamedTextColor.AQUA)
            .append(Component.text("<instance-id>", NamedTextColor.GRAY)));
    source.sendMessage(
        Component.text("/network restart ", NamedTextColor.AQUA)
            .append(Component.text("<instance-id>", NamedTextColor.GRAY)));
    source.sendMessage(
        Component.text("/network log ", NamedTextColor.AQUA)
            .append(Component.text("<instance-id> [lines]", NamedTextColor.GRAY)));
    source.sendMessage(
        Component.text("/network command ", NamedTextColor.AQUA)
            .append(Component.text("<instance-id> <command...>", NamedTextColor.GRAY)));
    source.sendMessage(
        Component.text("/network transfer ", NamedTextColor.AQUA)
            .append(Component.text("<instance-id> <players...>", NamedTextColor.GRAY)));
  }

  private void runManagement(CommandSource source, ManagementAction action) {
    CompletableFuture.runAsync(
        () -> {
          try {
            action.run();
          } catch (Exception error) {
            String message = error.getMessage();
            if (message == null || message.isBlank()) {
              message = error.getClass().getSimpleName();
            }
            logger.warn("Network management command failed: {}", message);
            source.sendMessage(
                Component.text("Network request failed: " + message, NamedTextColor.RED));
          }
        });
  }

  private List<String> normalizePlayers(String[] arguments, int start) {
    List<String> players = new ArrayList<>();
    for (int index = start; index < arguments.length; index++) {
      if (arguments[index].equalsIgnoreCase("--player")
          || arguments[index].equalsIgnoreCase("--players")) {
        continue;
      }
      for (String value : arguments[index].split(",")) {
        String player = value.trim();
        if (player.isEmpty()) {
          continue;
        }
        players.add(
            findPlayer(player).map(online -> online.getUniqueId().toString()).orElse(player));
      }
    }
    return players;
  }

  private static boolean requireArguments(
      CommandSource source, String[] arguments, int count, String usage) {
    if (arguments.length >= count) {
      return true;
    }
    source.sendMessage(
        Component.text("Usage: ", NamedTextColor.YELLOW)
            .append(Component.text(usage, NamedTextColor.AQUA)));
    return false;
  }

  private static String resolveServerId(String value, List<ServerInstance> instances) {
    return instances.stream()
        .filter(
            instance ->
                instance.serverId().equalsIgnoreCase(value)
                    || instance.instanceId().equalsIgnoreCase(value)
                    || displayId(instance).equalsIgnoreCase(value))
        .map(ServerInstance::serverId)
        .findFirst()
        .orElse(value);
  }

  private static String displayId(ServerInstance instance) {
    return instance.serverId() + "-1";
  }

  private static Component formatInstance(ServerInstance instance) {
    return Component.text(displayId(instance), NamedTextColor.AQUA)
        .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
        .append(Component.text(instance.state().name(), stateColor(instance.state())))
        .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
        .append(Component.text("127.0.0.1:", NamedTextColor.GRAY))
        .append(Component.text(instance.port(), NamedTextColor.YELLOW))
        .append(Component.text(" | PID ", NamedTextColor.DARK_GRAY))
        .append(Component.text(instance.processId(), NamedTextColor.GRAY));
  }

  private static Component formatPlayer(String username, String server, String ping, String uuid) {
    return Component.text(username, NamedTextColor.AQUA)
        .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
        .append(Component.text(server, NamedTextColor.GOLD))
        .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
        .append(Component.text(ping, NamedTextColor.GREEN))
        .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
        .append(Component.text(uuid, NamedTextColor.GRAY));
  }

  private static Component formatPingPlayer(Player player, String server) {
    long ping = player.getPing();
    Component latency =
        ping < 0
            ? Component.text("unknown", NamedTextColor.RED)
            : Component.text(ping + " ms", pingColor(ping));
    return Component.text(player.getUsername(), NamedTextColor.AQUA)
        .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
        .append(Component.text(server, NamedTextColor.GOLD))
        .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
        .append(latency);
  }

  private static NamedTextColor pingColor(long ping) {
    if (ping <= 80) {
      return NamedTextColor.GREEN;
    }
    if (ping <= 150) {
      return NamedTextColor.YELLOW;
    }
    if (ping <= 250) {
      return NamedTextColor.GOLD;
    }
    return NamedTextColor.RED;
  }

  private static long resolveTransferTimeoutMillis() {
    String value = System.getenv("SCHEDULER_TRANSFER_TIMEOUT_SECONDS");
    if (value == null || value.isBlank()) {
      return Duration.ofSeconds(45).toMillis();
    }
    try {
      long seconds = Long.parseLong(value);
      return seconds > 0 ? Math.multiplyExact(seconds, 1000) : Duration.ofSeconds(45).toMillis();
    } catch (ArithmeticException | NumberFormatException ignored) {
      return Duration.ofSeconds(45).toMillis();
    }
  }

  private static NamedTextColor stateColor(ServerInstanceState state) {
    return switch (state) {
      case READY -> NamedTextColor.GREEN;
      case STARTING -> NamedTextColor.YELLOW;
      case STOPPING -> NamedTextColor.GOLD;
      case EXITED -> NamedTextColor.GRAY;
      case FAILED -> NamedTextColor.RED;
    };
  }

  private static List<String> filterSuggestions(List<String> values, String prefix) {
    String normalized = prefix.toLowerCase(Locale.ROOT);
    return values.stream()
        .distinct()
        .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized))
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .toList();
  }

  private static void sendLines(
      CommandSource source, List<String> lines, String emptyMessage, NamedTextColor lineColor) {
    if (lines.isEmpty()) {
      source.sendMessage(Component.text(emptyMessage, NamedTextColor.YELLOW));
      return;
    }
    for (String line : lines) {
      source.sendMessage(Component.text(line, lineColor));
    }
  }

  private interface ManagementAction {
    void run() throws Exception;
  }
}
