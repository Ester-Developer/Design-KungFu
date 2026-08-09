package com.kungfuchess.cloud.services;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kungfuchess.cloud.infra.HttpJson;
import com.kungfuchess.cloud.infra.RedisRegistry;
import com.kungfuchess.net.RoomErrorMessage;
import com.kungfuchess.net.RoomInfoMessage;
import com.kungfuchess.net.RoomJoinMessage;
import com.kungfuchess.net.ShardConnectMessage;
import com.kungfuchess.net.TokenMessage;
import com.kungfuchess.util.ActivityLogger;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Nats;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WS Gateway — the only service holding a live connection to the client
 * (Server_Design.md §2.2/§3). Validates the session token issued by the API
 * Gateway, tracks room codes in Redis (create/join), and — once both players are
 * present — asks the Game Allocator which shard to use and tells both clients to
 * reconnect there via {@link ShardConnectMessage}. Holds no game state itself;
 * once a room starts, this gateway's job for that room is done.
 */
public class WsGatewayMain extends WebSocketServer {

    private static final String ROOM_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I
    private static final int ROOM_CODE_LENGTH = 4;
    private static final int ROOM_WAIT_TIMEOUT_SECONDS = 300;

    private final Gson gson = new Gson();
    private final SecureRandom random = new SecureRandom();
    private final RedisRegistry redis;
    private final String allocatorUrl;
    private final String matchmakerUrl;
    private final Connection nats;
    private final Dispatcher natsDispatcher;
    private final ExecutorService waiters = Executors.newCachedThreadPool();

    private final ConcurrentHashMap<WebSocket, String> usernameOf = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WebSocket, Integer> eloOf = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WebSocket> pendingByUsername = new ConcurrentHashMap<>();

    public WsGatewayMain(int port, RedisRegistry redis, String allocatorUrl, String matchmakerUrl, Connection nats) {
        super(new InetSocketAddress(port));
        this.redis = redis;
        this.allocatorUrl = allocatorUrl;
        this.matchmakerUrl = matchmakerUrl;
        this.nats = nats;
        this.natsDispatcher = nats.createDispatcher();
    }

    public static void main(String[] args) throws Exception {
        ActivityLogger.install("ws-gateway");
        int port = Integer.parseInt(System.getenv().getOrDefault("WS_PORT", "5555"));
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
        String allocatorUrl = "http://" + System.getenv().getOrDefault("ALLOCATOR_HOST", "localhost")
                + ":" + System.getenv().getOrDefault("ALLOCATOR_PORT", "8004");
        String matchmakerUrl = "http://" + System.getenv().getOrDefault("MATCHMAKER_HOST", "localhost")
                + ":" + System.getenv().getOrDefault("MATCHMAKER_PORT", "8003");
        String natsUrl = "nats://" + System.getenv().getOrDefault("NATS_HOST", "localhost")
                + ":" + System.getenv().getOrDefault("NATS_PORT", "4222");
        int healthPort = Integer.parseInt(System.getenv().getOrDefault("WS_HEALTH_PORT", "6555"));

        RedisRegistry redis = new RedisRegistry(redisHost, redisPort);
        Connection nats = Nats.connect(natsUrl);
        WsGatewayMain gateway = new WsGatewayMain(port, redis, allocatorUrl, matchmakerUrl, nats);
        gateway.start();
        gateway.startHealthServer(healthPort);
        System.out.println("[WsGateway] listening on ws://0.0.0.0:" + port + " (Allocator at " + allocatorUrl
                + ", Matchmaker at " + matchmakerUrl + ")");
        Thread.currentThread().join();
    }

    private void startHealthServer(int healthPort) throws IOException {
        com.sun.net.httpserver.HttpServer health = HttpJson.start(healthPort);
        HttpJson.get(health, "/health", (body, ex) -> Map.of("status", "ok", "service", "ws-gateway"));
        HttpJson.get(health, "/metrics", (body, ex) -> Map.of(
                "kfc_ws_gateway_open_connections", usernameOf.size()));
        health.start();
        System.out.println("[WsGateway] health/metrics on http://0.0.0.0:" + healthPort);
    }

    @Override
    public void onStart() {
        setConnectionLostTimeout(0);
        setConnectionLostTimeout(100);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("[WsGateway] new connection: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String username = usernameOf.remove(conn);
        eloOf.remove(conn);
        if (username != null) {
            pendingByUsername.remove(username, conn);
            natsDispatcher.unsubscribe("kfc.matched." + username);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[WsGateway] error: " + ex.getMessage());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            String type = json.has("type") ? json.get("type").getAsString() : "UNKNOWN";
            switch (type) {
                case "TOKEN" -> handleToken(conn, message);
                case "ROOM_CREATE" -> handleRoomCreate(conn, message);
                case "ROOM_JOIN" -> handleRoomJoin(conn, message);
                case "PLAY" -> handleQuickMatch(conn);
                default -> conn.send(gson.toJson(new RoomErrorMessage("Unexpected message: " + type)));
            }
        } catch (Exception e) {
            conn.send(gson.toJson(new RoomErrorMessage("Invalid message: " + e.getMessage())));
        }
    }

    // ── handshake ─────────────────────────────────────────────────────────────

    private void handleToken(WebSocket conn, String message) {
        TokenMessage token = gson.fromJson(message, TokenMessage.class);
        String username = redis.consumeSessionToken(token.getToken());
        if (username == null) {
            conn.send(gson.toJson(new RoomErrorMessage("invalid or expired session token")));
            conn.close(1000, "invalid token");
            return;
        }
        usernameOf.put(conn, username);
        eloOf.put(conn, token.getElo());
        System.out.println("[WsGateway] " + username + " (ELO " + token.getElo() + ") connected, awaiting Room choice");
    }

    // ── rooms ─────────────────────────────────────────────────────────────────

    private void handleRoomCreate(WebSocket conn, String message) {
        String username = usernameOf.get(conn);
        if (username == null) {
            conn.send(gson.toJson(new RoomErrorMessage("must send TOKEN first")));
            return;
        }
        String roomId = generateRoomCode();
        redis.createRoom(roomId, username);
        pendingByUsername.put(username, conn);

        conn.send(gson.toJson(new RoomInfoMessage(roomId, "WHITE", username, "Waiting...", false)));
        System.out.println("[WsGateway] " + username + " created room " + roomId);

        waiters.submit(() -> waitForOpponentThenAllocate(roomId, username, conn));
    }

    private void handleRoomJoin(WebSocket conn, String message) {
        String username = usernameOf.get(conn);
        if (username == null) {
            conn.send(gson.toJson(new RoomErrorMessage("must send TOKEN first")));
            return;
        }
        RoomJoinMessage join = gson.fromJson(message, RoomJoinMessage.class);
        String roomId = join.getRoomId() == null ? "" : join.getRoomId().trim().toUpperCase();
        Map<String, String> room = redis.getRoom(roomId);
        if (room == null) {
            conn.send(gson.toJson(new RoomErrorMessage("Room '" + roomId + "' not found.")));
            return;
        }
        if (room.get("black") != null) {
            conn.send(gson.toJson(new RoomErrorMessage(
                    "Room '" + roomId + "' is full — the game has already started.")));
            return;
        }
        if (!redis.setRoomBlack(roomId, username)) {
            conn.send(gson.toJson(new RoomErrorMessage("Room '" + roomId + "' no longer exists.")));
            return;
        }
        pendingByUsername.put(username, conn);
        System.out.println("[WsGateway] " + username + " joined room " + roomId + " — waiting for shard allocation");
        // The creator's own waitForOpponentThenAllocate task (already running) will
        // notice "black" is now set and send ShardConnectMessage to both of us.
    }

    // ── quick match ───────────────────────────────────────────────────────────

    /**
     * Subscribes this connection to its own NATS subject <em>before</em> asking the
     * Matchmaker to queue it, so there's no race between "matched" firing and the
     * subscription existing — NATS core doesn't replay missed messages.
     */
    private void handleQuickMatch(WebSocket conn) {
        String username = usernameOf.get(conn);
        Integer elo = eloOf.get(conn);
        if (username == null || elo == null) {
            conn.send(gson.toJson(new RoomErrorMessage("must send TOKEN first")));
            return;
        }
        String subject = "kfc.matched." + username;
        natsDispatcher.subscribe(subject, msg -> {
            natsDispatcher.unsubscribe(subject);
            if (!conn.isOpen()) return;
            ShardConnectMessage shardConnect = gson.fromJson(
                    new String(msg.getData(), StandardCharsets.UTF_8), ShardConnectMessage.class);
            conn.send(gson.toJson(shardConnect));
            System.out.println("[WsGateway] " + username + " quick-matched -> " + shardConnect.getShardUrl());
        });
        try {
            HttpJson.postJson(matchmakerUrl + "/queue/join", Map.of("username", username, "elo", elo), Map.class);
            System.out.println("[WsGateway] " + username + " (ELO " + elo + ") requested Quick Match");
        } catch (Exception e) {
            natsDispatcher.unsubscribe(subject);
            conn.send(gson.toJson(new RoomErrorMessage("Matchmaker is unavailable. Try again.")));
        }
    }

    /** Runs on a background thread — polls Redis for the room to be filled, then allocates a shard. */
    private void waitForOpponentThenAllocate(String roomId, String whiteUsername, WebSocket whiteConn) {
        long deadline = System.currentTimeMillis() + ROOM_WAIT_TIMEOUT_SECONDS * 1000L;
        try {
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(1000);
                Map<String, String> room = redis.getRoom(roomId);
                if (room == null) return; // room vanished (e.g. timed out via another path)
                String blackUsername = room.get("black");
                if (blackUsername == null) continue;

                WebSocket blackConn = pendingByUsername.get(blackUsername);
                if (blackConn == null || !blackConn.isOpen()) {
                    // joiner disconnected before we got here — best effort, give up on this room
                    conn(whiteConn, new RoomErrorMessage("Opponent disconnected before the game could start."));
                    redis.deleteRoom(roomId);
                    return;
                }

                Map<String, Object> allocated;
                try {
                    allocated = HttpJson.postJson(allocatorUrl + "/allocate", Map.of("roomId", roomId), Map.class);
                } catch (Exception e) {
                    conn(whiteConn, new RoomErrorMessage("No game server is currently available. Try again."));
                    redis.deleteRoom(roomId);
                    return;
                }
                String shardHost = String.valueOf(allocated.get("host"));
                int shardPort = (int) Double.parseDouble(String.valueOf(allocated.get("port")));
                String shardUrl = "ws://" + shardHost + ":" + shardPort;

                String whiteToken = redis.issueSessionToken(whiteUsername);
                String blackToken = redis.issueSessionToken(blackUsername);
                String[] players = { whiteUsername, blackUsername };

                whiteConn.send(gson.toJson(new ShardConnectMessage(shardUrl, roomId, whiteToken, "WHITE", players)));
                blackConn.send(gson.toJson(new ShardConnectMessage(shardUrl, roomId, blackToken, "BLACK", players)));
                System.out.println("[WsGateway] room " + roomId + " allocated to " + shardUrl
                        + " — sent ShardConnect to both players");

                pendingByUsername.remove(whiteUsername, whiteConn);
                pendingByUsername.remove(blackUsername, blackConn);
                return;
            }
            // timed out — nobody joined
            conn(whiteConn, new RoomErrorMessage(
                    "No one joined room " + roomId + " within " + ROOM_WAIT_TIMEOUT_SECONDS + " seconds. Try again."));
            redis.deleteRoom(roomId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pendingByUsername.remove(whiteUsername, whiteConn);
        }
    }

    private void conn(WebSocket ws, Object msg) {
        if (ws.isOpen()) {
            ws.send(gson.toJson(msg));
        }
    }

    private String generateRoomCode() {
        String code;
        do {
            StringBuilder sb = new StringBuilder(ROOM_CODE_LENGTH);
            for (int i = 0; i < ROOM_CODE_LENGTH; i++) {
                sb.append(ROOM_CODE_CHARS.charAt(random.nextInt(ROOM_CODE_CHARS.length())));
            }
            code = sb.toString();
        } while (redis.getRoom(code) != null);
        return code;
    }
}
