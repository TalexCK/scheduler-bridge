package com.schedulerbridge.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class BridgeHttpClient {
  private final String baseUrl;
  private final String token;

  public BridgeHttpClient(String baseUrl, String token) {
    this.baseUrl = stripTrailingSlash(require(baseUrl, "SCHEDULER_BRIDGE_URL"));
    this.token = require(token, "SCHEDULER_BRIDGE_TOKEN");
  }

  public static BridgeHttpClient fromEnvironment() {
    return new BridgeHttpClient(
        System.getenv("SCHEDULER_BRIDGE_URL"), System.getenv("SCHEDULER_BRIDGE_TOKEN"));
  }

  public void ready(String serverId, String instanceId) throws IOException {
    post("/bridge/v1/ready", form("server_id", serverId, "instance_id", instanceId));
  }

  public void heartbeat(String serverId, String instanceId) throws IOException {
    post("/bridge/v1/heartbeat", form("server_id", serverId, "instance_id", instanceId));
  }

  public void idle(String serverId, String instanceId) throws IOException {
    post("/bridge/v1/idle", form("server_id", serverId, "instance_id", instanceId));
  }

  public List<TransferRequest> pendingTransfers() throws IOException {
    String response = request("GET", "/bridge/v1/transfers", null);
    if (response.trim().isEmpty()) {
      return Collections.emptyList();
    }
    List<TransferRequest> transfers = new ArrayList<>();
    for (String line : response.split("\\R")) {
      String[] fields = line.split("\\t", -1);
      if (fields.length == 4) {
        transfers.add(new TransferRequest(fields[0], fields[1], fields[2], fields[3]));
      }
    }
    return transfers;
  }

  public void transferResult(String transferId, boolean success, String message)
      throws IOException {
    post(
        "/bridge/v1/transfers/result",
        form(
            "transfer_id",
            transferId,
            "status",
            success ? "success" : "failure",
            "message",
            message == null ? "" : message));
  }

  public ServerInstance launchServer(String serverId, List<String> players) throws IOException {
    return parseServer(
        post(
            "/bridge/v1/servers/" + encodePath(serverId) + "/launch",
            form("players", String.join(",", players))));
  }

  public void queueTransfers(String serverId, List<String> players) throws IOException {
    post(
        "/bridge/v1/servers/" + encodePath(serverId) + "/transfers",
        form("players", String.join(",", players)));
  }

  public ServerInstance terminateServer(String serverId) throws IOException {
    return parseServer(post("/bridge/v1/servers/" + encodePath(serverId) + "/terminate", ""));
  }

  public ServerInstance restartServer(String serverId) throws IOException {
    return parseServer(
        request("POST", "/bridge/v1/servers/" + encodePath(serverId) + "/restart", "", 90000));
  }

  public List<String> serverLogs(String serverId, int lines) throws IOException {
    return responseLines(
        request(
            "GET",
            "/bridge/v1/servers/"
                + encodePath(serverId)
                + "/logs?lines="
                + Math.max(1, Math.min(lines, 200)),
            null));
  }

  public List<String> sendCommand(String serverId, String command) throws IOException {
    return responseLines(
        post("/bridge/v1/servers/" + encodePath(serverId) + "/command", form("command", command)));
  }

  public List<ServerInstance> servers() throws IOException {
    String response = request("GET", "/bridge/v1/servers", null);
    if (response.trim().isEmpty()) {
      return Collections.emptyList();
    }
    List<ServerInstance> instances = new ArrayList<>();
    for (String line : response.split("\\R")) {
      if (!line.trim().isEmpty()) {
        instances.add(parseServer(line));
      }
    }
    return instances;
  }

  public List<String> serverDefinitions() throws IOException {
    return responseLines(request("GET", "/bridge/v1/definitions", null));
  }

  public List<SchedulerGameDefinition> games() throws IOException {
    String response = request("GET", "/bridge/v1/games", null);
    if (response.trim().isEmpty()) {
      return Collections.emptyList();
    }
    List<SchedulerGameDefinition> games = new ArrayList<>();
    for (String line : response.split("\\R")) {
      if (!line.trim().isEmpty()) {
        games.add(parseGame(line));
      }
    }
    return games;
  }

  public List<PlayerSnapshot> players() throws IOException {
    String response = request("GET", "/bridge/v1/players", null);
    if (response.trim().isEmpty()) {
      return Collections.emptyList();
    }
    List<PlayerSnapshot> players = new ArrayList<>();
    for (String line : response.split("\\R")) {
      String[] fields = line.split("\\t", -1);
      if (fields.length != 4) {
        throw new IOException("scheduler returned an invalid player record");
      }
      try {
        players.add(new PlayerSnapshot(fields[0], fields[1], Long.parseLong(fields[2]), fields[3]));
      } catch (NumberFormatException error) {
        throw new IOException("scheduler returned an invalid player record", error);
      }
    }
    return players;
  }

  public void updatePlayers(List<PlayerSnapshot> players) throws IOException {
    StringBuilder snapshot = new StringBuilder();
    for (PlayerSnapshot player : players) {
      if (snapshot.length() > 0) {
        snapshot.append('\n');
      }
      snapshot
          .append(player.uuid())
          .append('\t')
          .append(player.username())
          .append('\t')
          .append(player.ping())
          .append('\t')
          .append(player.serverId());
    }
    post("/bridge/v1/players", form("players", snapshot.toString()));
  }

  private String post(String path, String body) throws IOException {
    return request("POST", path, body);
  }

  private String request(String method, String path, String body) throws IOException {
    return request(method, path, body, 5000);
  }

  private String request(String method, String path, String body, int readTimeoutMillis)
      throws IOException {
    HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
    connection.setRequestMethod(method);
    connection.setConnectTimeout(3000);
    connection.setReadTimeout(readTimeoutMillis);
    connection.setRequestProperty("Authorization", "Bearer " + token);
    if (body != null) {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
      connection.setFixedLengthStreamingMode(bytes.length);
      try (OutputStream output = connection.getOutputStream()) {
        output.write(bytes);
      }
    }
    int status = connection.getResponseCode();
    InputStream stream =
        status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
    String response = read(stream);
    connection.disconnect();
    if (status < 200 || status >= 300) {
      throw new IOException("scheduler returned HTTP " + status + ": " + response);
    }
    return response;
  }

  private static List<String> responseLines(String response) {
    if (response.trim().isEmpty()) {
      return Collections.emptyList();
    }
    List<String> lines = new ArrayList<>();
    Collections.addAll(lines, response.split("\\R"));
    return lines;
  }

  private static String read(InputStream stream) throws IOException {
    if (stream == null) {
      return "";
    }
    StringBuilder value = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (value.length() > 0) {
          value.append('\n');
        }
        value.append(line);
      }
    }
    return value.toString();
  }

  private static String form(String... values) throws IOException {
    StringBuilder result = new StringBuilder();
    for (int index = 0; index < values.length; index += 2) {
      if (result.length() > 0) {
        result.append('&');
      }
      result.append(encode(values[index])).append('=').append(encode(values[index + 1]));
    }
    return result.toString();
  }

  private static String encode(String value) throws IOException {
    return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
  }

  private static String encodePath(String value) throws IOException {
    return encode(value).replace("+", "%20");
  }

  private static ServerInstance parseServer(String value) throws IOException {
    String[] fields = value.trim().split("\\t", -1);
    if (fields.length != 5) {
      throw new IOException("scheduler returned an invalid server record");
    }
    try {
      return new ServerInstance(
          fields[0],
          fields[1],
          ServerInstanceState.valueOf(fields[2].toUpperCase(Locale.ROOT)),
          Long.parseLong(fields[3]),
          Integer.parseInt(fields[4]));
    } catch (IllegalArgumentException error) {
      throw new IOException("scheduler returned an invalid server record", error);
    }
  }

  private static SchedulerGameDefinition parseGame(String value) throws IOException {
    String[] fields = value.split("\\t", -1);
    if (fields.length != 12) {
      throw new IOException("scheduler returned an invalid game record");
    }
    try {
      String description = decodeGameField(fields[4]);
      List<String> lines = new ArrayList<>();
      if (!description.isEmpty()) {
        Collections.addAll(lines, description.split("\u001f", -1));
      }
      return new SchedulerGameDefinition(
          Integer.parseInt(fields[0]),
          decodeGameField(fields[1]),
          decodeGameField(fields[2]),
          decodeGameField(fields[3]),
          lines,
          decodeGameField(fields[5]),
          Integer.parseInt(fields[6]),
          nullableGameField(fields[7]),
          nullableGameField(fields[8]),
          nullableGameField(fields[9]),
          Integer.parseInt(fields[10]),
          Integer.parseInt(fields[11]));
    } catch (IllegalArgumentException error) {
      throw new IOException("scheduler returned an invalid game record", error);
    }
  }

  private static String nullableGameField(String value) {
    String decoded = decodeGameField(value);
    return decoded.isEmpty() ? null : decoded;
  }

  private static String decodeGameField(String value) {
    return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
  }

  private static String stripTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private static String require(String value, String name) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalStateException(name + " must be set");
    }
    return value;
  }

  public static final class TransferRequest {
    private final String transferId;
    private final String serverId;
    private final String player;
    private final String address;

    public TransferRequest(String transferId, String serverId, String player, String address) {
      this.transferId = transferId;
      this.serverId = serverId;
      this.player = player;
      this.address = address;
    }

    public String transferId() {
      return transferId;
    }

    public String serverId() {
      return serverId;
    }

    public String player() {
      return player;
    }

    public String address() {
      return address;
    }
  }

  public static final class PlayerSnapshot {
    private final String uuid;
    private final String username;
    private final long ping;
    private final String serverId;

    public PlayerSnapshot(String uuid, String username, long ping, String serverId) {
      this.uuid = uuid;
      this.username = username;
      this.ping = ping;
      this.serverId = serverId;
    }

    public String uuid() {
      return uuid;
    }

    public String username() {
      return username;
    }

    public long ping() {
      return ping;
    }

    public String serverId() {
      return serverId;
    }
  }
}
