package com.kungfuchess.cloud.services;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kungfuchess.cloud.infra.GameHistoryRepository;
import com.kungfuchess.cloud.infra.HttpJson;
import com.kungfuchess.cloud.infra.PostgresUserRepository;
import com.kungfuchess.cloud.infra.RedisRegistry;
import com.kungfuchess.bus.EventBus;
import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import com.kungfuchess.net.BoardSerializer;
import com.kungfuchess.net.BoardStateMessage;
import com.kungfuchess.net.DisconnectCountdownMessage;
import com.kungfuchess.net.DodgeMessage;
import com.kungfuchess.net.ErrorMessage;
import com.kungfuchess.net.MoveMessage;
import com.kungfuchess.net.MoveNotation;
import com.kungfuchess.net.RoomInfoMessage;
import com.kungfuchess.net.ScreamMessage;
import com.kungfuchess.net.ShardJoinMessage;
import com.kungfuchess.realtime.Motion;
import com.kungfuchess.server.Room;
import com.kungfuchess.server.ServerSession;
import com.kungfuchess.util.ActivityLogger;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Game Server Shard — runs the authoritative {@link GameEngine} for whichever
 * rooms the Game Allocator assigns it. This is the *only* place game rules are
 * decided (Server_Design.md §2.2/§3); every other service is stateless.
 *
 * <p>Reuses {@link Room}/{@link ServerSession} from the Phase 1 single-process
 * server unchanged — a "room" means the same thing here, just reached via a
 * {@link ShardJoinMessage} handshake instead of an in-process ROOM_CREATE/JOIN.</p>
 *
 * <p>Heartbeats {@code shard:worker:{id}} into Redis every 10s so the Game
 * Allocator can pick the least-loaded shard; writes ELO to PostgreSQL at game end
 * (no separate Rating service — matches the reviewed course diagram).</p>
 */
public class GameShardMain extends WebSocketServer {

    private static final long TICK_MS = 150L;
    private static final int DISCONNECT_COUNTDOWN_SECONDS = 20;
    private static final int HEARTBEAT_SECONDS = 10;
    private static final String STARTING_BOARD =
        "bR bN bB bQ bK bB bN bR\n" +
        "bP bP bP bP bP bP bP bP\n" +
        " .  .  .  .  .  .  .  .\n" +
        " .  .  .  .  .  .  .  .\n" +
        " .  .  .  .  .  .  .  .\n" +
        " .  .  .  .  .  .  .  .\n" +
        "wP wP wP wP wP wP wP wP\n" +
        "wR wN wB wQ wK wB wN wR";

    private final Gson gson = new Gson();
    private final RedisRegistry redis;
    private final PostgresUserRepository users;
    private final GameHistoryRepository history;
    private final String shardId;
    private final String publicHost;
    private final int port;

    private final ConcurrentHashMap<String, Room> waitingRooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> disconnectCountdowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> roomStartedAtMs = new ConcurrentHashMap<>();
    private final AtomicInteger activeRooms = new AtomicInteger(0);

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);

    public GameShardMain(int port, String publicHost, RedisRegistry redis, PostgresUserRepository users,
                          GameHistoryRepository history) {
        super(new InetSocketAddress(port));
        this.port = port;
        this.publicHost = publicHost;
        this.redis = redis;
        this.users = users;
        this.history = history;
        this.shardId = java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    public static void main(String[] args) throws Exception {
        ActivityLogger.install("game-shard");
        int port = Integer.parseInt(System.getenv().getOrDefault("SHARD_PORT", "5556"));
        String publicHost = System.getenv().getOrDefault("SHARD_PUBLIC_HOST", "localhost");
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
        String pgHost = System.getenv().getOrDefault("PG_HOST", "localhost");
        int pgPort = Integer.parseInt(System.getenv().getOrDefault("PG_PORT", "5432"));
        int healthPort = Integer.parseInt(System.getenv().getOrDefault("SHARD_HEALTH_PORT", "6556"));

        RedisRegistry redis = new RedisRegistry(redisHost, redisPort);
        PostgresUserRepository users = new PostgresUserRepository(pgHost, pgPort, "kfc", "kfc", "kfc");
        GameHistoryRepository history = new GameHistoryRepository(pgHost, pgPort, "kfc", "kfc", "kfc");

        GameShardMain shard = new GameShardMain(port, publicHost, redis, users, history);
        shard.start();
        shard.startHealthServer(healthPort);
        System.out.println("[GameShard] listening on ws://0.0.0.0:" + port + " (public: " + publicHost + ":" + port + ")");
        Thread.currentThread().join();
    }

    private void startHealthServer(int healthPort) throws java.io.IOException {
        com.sun.net.httpserver.HttpServer health = HttpJson.start(healthPort);
        HttpJson.get(health, "/health", (body, ex) ->
                java.util.Map.of("status", "ok", "shardId", shardId, "activeRooms", activeRooms.get()));
        HttpJson.get(health, "/metrics", (body, ex) -> java.util.Map.of(
                "kfc_shard_active_rooms", activeRooms.get(),
                "kfc_shard_open_connections", sessions.size(),
                "kfc_shard_id", shardId));
        health.start();
        System.out.println("[GameShard] health/metrics on http://0.0.0.0:" + healthPort);
    }

    @Override
    public void onStart() {
        setConnectionLostTimeout(0);
        setConnectionLostTimeout(100);
        scheduler.scheduleAtFixedRate(this::tickAllRooms, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(
                () -> redis.heartbeatShard(shardId, publicHost, port, activeRooms.get()),
                0, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("[GameShard] new connection: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        ServerSession session = sessionOf(conn);
        if (session == null) return;
        Room room = session.getRoom();
        if (room == null || session.isSpectator()) return;
        ServerSession opponent = room.opponentOf(session);
        if (opponent != null && opponent.getConnection().isOpen() && !room.getEngine().isGameOver()) {
            startDisconnectCountdown(room, session, opponent);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[GameShard] error: " + ex.getMessage());
    }

    // ── connection -> session lookup (a connection's session lives on the Room once seated) ─

    private final ConcurrentHashMap<WebSocket, ServerSession> sessions = new ConcurrentHashMap<>();

    private ServerSession sessionOf(WebSocket conn) {
        return sessions.get(conn);
    }

    // ── message dispatch ─────────────────────────────────────────────────────

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            String type = json.has("type") ? json.get("type").getAsString() : "UNKNOWN";
            switch (type) {
                case "SHARD_JOIN" -> handleShardJoin(conn, message);
                case "MOVE" -> handleMove(conn, message);
                case "SCREAM" -> handleScream(conn, message);
                case "DODGE" -> handleDodge(conn, message);
                default -> sendError(conn, "Unknown message type: " + type);
            }
        } catch (Exception e) {
            sendError(conn, "Invalid message: " + e.getMessage());
        }
    }

    private void handleShardJoin(WebSocket conn, String message) {
        ShardJoinMessage join = gson.fromJson(message, ShardJoinMessage.class);
        String username = redis.consumeSessionToken(join.getToken());
        if (username == null) {
            sendError(conn, "invalid or expired shard token");
            conn.close(1000, "invalid token");
            return;
        }

        boolean isWhite = "WHITE".equalsIgnoreCase(join.getColor());
        Room room = waitingRooms.computeIfAbsent(join.getRoomId(), id -> newRoom(id));

        ServerSession session = new ServerSession(conn, username, isWhite ? "WHITE" : "BLACK", 1200);
        session.setRoom(room);
        sessions.put(conn, session);

        boolean justStarted;
        synchronized (room) {
            if (isWhite) {
                room.setWhite(session);
            } else {
                room.setBlack(session);
            }
            justStarted = room.getWhite() != null && room.getBlack() != null && !room.isStarted();
            if (justStarted) {
                room.setStarted(true);
                roomStartedAtMs.put(room.getRoomId(), System.currentTimeMillis());
                activeRooms.incrementAndGet();
                room.getEngine().getEventBus().subscribe(EventBus.GAME_ENDED,
                        payload -> handleGameEnded(room, payload));
                System.out.println("[GameShard] room " + room.getRoomId() + " started: "
                        + room.getWhite().getUsername() + " vs " + room.getBlack().getUsername());
            }
        }

        // The client's colorLatch/onColorAssigned callback (see CloudClientMain,
        // mirroring the Phase 1 flow) only ever fires off a RoomInfoMessage — without
        // this, a client that just joined the shard would wait forever, never seeing
        // its own color or the game window.
        String whiteName = room.getWhite() != null ? room.getWhite().getUsername() : "Waiting...";
        String blackName = room.getBlack() != null ? room.getBlack().getUsername() : "Waiting...";
        conn.send(gson.toJson(new RoomInfoMessage(
                room.getRoomId(), session.getColor(), whiteName, blackName, room.isStarted())));

        if (justStarted) {
            // The player who was already waiting still has stale "Waiting..." names
            // in their UI until they hear the room actually started.
            ServerSession other = isWhite ? room.getBlack() : room.getWhite();
            if (other != null && other.getConnection() != conn && other.getConnection().isOpen()) {
                other.getConnection().send(gson.toJson(new RoomInfoMessage(
                        room.getRoomId(), other.getColor(), whiteName, blackName, true)));
            }
        }

        if (room.isStarted()) {
            broadcastRoomBoardState(room);
        }
    }

    private Room newRoom(String roomId) {
        try {
            Board board = new com.kungfuchess.io.BoardParser.TextParser().parse(STARTING_BOARD);
            Room room = new Room(roomId);
            room.getEngine().setBoard(board);
            return room;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create room " + roomId, e);
        }
    }

    // ── moves ─────────────────────────────────────────────────────────────────

    private void handleMove(WebSocket conn, String message) {
        Room room = requireActiveRoom(conn);
        if (room == null) return;
        MoveMessage msg = gson.fromJson(message, MoveMessage.class);
        if (msg.getFrom() == null || msg.getTo() == null) {
            sendError(conn, "Move message missing 'from' or 'to' field");
            return;
        }
        try {
            Position from = parseSquare(msg.getFrom());
            Position to = parseSquare(msg.getTo());
            ServerSession session = sessionOf(conn);
            synchronized (room) {
                GameEngine engine = room.getEngine();
                Optional<Piece> movingPiece = engine.getBoard().pieceAt(from);
                if (movingPiece.isPresent() && !movingPiece.get().getColor().equalsIgnoreCase(session.getColor())) {
                    sendError(conn, "You can only move your own pieces");
                    rejectPrivately(conn, room, to);
                    return;
                }
                GameEngine.MoveResult result = engine.requestMove(from, to);
                if (!result.isAccepted()) {
                    sendError(conn, result.reason());
                    rejectPrivately(conn, room, to);
                    return;
                }
                broadcastRoomBoardState(room);
            }
        } catch (Exception e) {
            sendError(conn, e.getMessage());
        }
    }

    private void handleScream(WebSocket conn, String message) {
        Room room = requireActiveRoom(conn);
        if (room == null) return;
        ScreamMessage msg = gson.fromJson(message, ScreamMessage.class);
        if (msg.getFrom() == null || msg.getTo() == null) {
            sendError(conn, "Scream message missing 'from' or 'to' field");
            return;
        }
        try {
            Position from = parseSquare(msg.getFrom());
            Position to = parseSquare(msg.getTo());
            ServerSession session = sessionOf(conn);
            synchronized (room) {
                GameEngine engine = room.getEngine();
                Optional<Piece> piece = engine.getBoard().pieceAt(from);
                if (piece.isPresent() && !piece.get().getColor().equalsIgnoreCase(session.getColor())) {
                    sendError(conn, "You can only use your own pieces");
                    rejectPrivately(conn, room, to);
                    return;
                }
                GameEngine.MoveResult result = engine.requestScream(from, to);
                if (!result.isAccepted()) {
                    sendError(conn, result.reason());
                    rejectPrivately(conn, room, to);
                    return;
                }
                broadcastRoomBoardState(room);
            }
        } catch (Exception e) {
            sendError(conn, e.getMessage());
        }
    }

    private void handleDodge(WebSocket conn, String message) {
        Room room = requireActiveRoom(conn);
        if (room == null) return;
        DodgeMessage msg = gson.fromJson(message, DodgeMessage.class);
        if (msg.getSquare() == null) {
            sendError(conn, "Dodge message missing 'square' field");
            return;
        }
        try {
            Position square = parseSquare(msg.getSquare());
            ServerSession session = sessionOf(conn);
            synchronized (room) {
                GameEngine engine = room.getEngine();
                Optional<Piece> piece = engine.getBoard().pieceAt(square);
                if (piece.isPresent() && !piece.get().getColor().equalsIgnoreCase(session.getColor())) {
                    sendError(conn, "You can only dodge with your own pieces");
                    rejectPrivately(conn, room, square);
                    return;
                }
                GameEngine.MoveResult result = engine.requestDodge(square);
                if (!result.isAccepted()) {
                    sendError(conn, result.reason());
                    rejectPrivately(conn, room, square);
                    return;
                }
                broadcastRoomBoardState(room);
            }
        } catch (Exception e) {
            sendError(conn, e.getMessage());
        }
    }

    private Room requireActiveRoom(WebSocket conn) {
        ServerSession session = sessionOf(conn);
        if (session == null) {
            sendError(conn, "Not joined to a room yet");
            return null;
        }
        Room room = session.getRoom();
        if (room == null || !room.isStarted()) {
            sendError(conn, "Waiting for opponent to join");
            return null;
        }
        if (session.isSpectator()) {
            sendError(conn, "Spectators cannot move");
            return null;
        }
        return room;
    }

    private void rejectPrivately(WebSocket conn, Room room, Position dest) {
        GameEngine engine = room.getEngine();
        engine.recordRejection(dest);
        conn.send(gson.toJson(buildBoardStateMessage(room)));
        engine.recordRejection(null);
    }

    private Position parseSquare(String square) {
        if (square == null || square.length() != 2) {
            throw new IllegalArgumentException("Square must be 2 characters (e.g., 'e2')");
        }
        return MoveNotation.parseSquare(square.charAt(0), square.charAt(1));
    }

    // ── real-time tick ────────────────────────────────────────────────────────

    private void tickAllRooms() {
        for (Room room : waitingRooms.values()) {
            if (!room.isStarted() || room.getEngine().isGameOver()) continue;
            try {
                synchronized (room) {
                    room.getEngine().waitMs(TICK_MS);
                    broadcastRoomBoardState(room);
                }
            } catch (Exception e) {
                System.err.println("[GameShard] tick error in room " + room.getRoomId() + ": " + e.getMessage());
            }
        }
    }

    // ── game end / ELO (no separate Rating service — writes PostgreSQL directly) ─

    private void handleGameEnded(Room room, Object payload) {
        String message = payload == null ? "" : payload.toString();
        String winnerColor = message.contains("winner: white") ? "white"
                : message.contains("winner: black") ? "black" : null;
        if (winnerColor == null) return;
        ServerSession winner = "white".equals(winnerColor) ? room.getWhite() : room.getBlack();
        ServerSession loser = "white".equals(winnerColor) ? room.getBlack() : room.getWhite();
        finishGame(room, winner, loser);
    }

    private void finishGame(Room room, ServerSession winner, ServerSession loser) {
        try {
            users.updateElo(winner.getUsername(), loser.getUsername());
            System.out.println("[GameShard] room " + room.getRoomId() + " over — " + winner.getUsername()
                    + " beat " + loser.getUsername() + " (ELO -> " + users.getElo(winner.getUsername())
                    + "/" + users.getElo(loser.getUsername()) + ")");
        } catch (Exception e) {
            System.err.println("[GameShard] failed to update ELO: " + e.getMessage());
        }
        try {
            Long startedAt = roomStartedAtMs.getOrDefault(room.getRoomId(), System.currentTimeMillis());
            history.recordGame(room.getRoomId(), room.getWhite().getUsername(), room.getBlack().getUsername(),
                    winner.getUsername(), users.getElo(room.getWhite().getUsername()),
                    users.getElo(room.getBlack().getUsername()), startedAt,
                    room.getEngine().snapshot().moveLog());
        } catch (Exception e) {
            System.err.println("[GameShard] failed to record game history: " + e.getMessage());
        }
        broadcastRoomBoardState(room);
        disconnectCountdowns.remove(room.getRoomId());
        waitingRooms.remove(room.getRoomId());
        roomStartedAtMs.remove(room.getRoomId());
        redis.deleteRoom(room.getRoomId());
        activeRooms.decrementAndGet();
    }

    private void startDisconnectCountdown(Room room, ServerSession disconnected, ServerSession opponent) {
        if (disconnectCountdowns.containsKey(room.getRoomId())) return;
        System.out.println("[GameShard] disconnect countdown started for room " + room.getRoomId());

        ScheduledFuture<?> task = scheduler.schedule(() -> {
            try {
                for (int i = DISCONNECT_COUNTDOWN_SECONDS; i > 0; i--) {
                    if (!opponent.getConnection().isOpen() || room.getEngine().isGameOver()) {
                        disconnectCountdowns.remove(room.getRoomId());
                        return;
                    }
                    opponent.getConnection().send(gson.toJson(new DisconnectCountdownMessage(i)));
                    if (i > 1) Thread.sleep(1000);
                }
                if (!room.getEngine().isGameOver()) {
                    room.getEngine().setGameOver(true);
                    finishGame(room, opponent, disconnected);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                disconnectCountdowns.remove(room.getRoomId());
            }
        }, 0, TimeUnit.SECONDS);
        disconnectCountdowns.put(room.getRoomId(), task);
    }

    // ── board state ───────────────────────────────────────────────────────────

    private void broadcastRoomBoardState(Room room) {
        String json = gson.toJson(buildBoardStateMessage(room));
        for (ServerSession s : room.getAllSessions()) {
            if (s.getConnection().isOpen()) {
                s.getConnection().send(json);
            }
        }
    }

    private BoardStateMessage buildBoardStateMessage(Room room) {
        GameEngine engine = room.getEngine();
        GameEngine.GameSnapshot snapshot = engine.snapshot();

        List<BoardStateMessage.PieceData> pieceDataList = new ArrayList<>();
        for (GameEngine.GameSnapshot.PieceView pv : snapshot.pieces()) {
            pieceDataList.add(new BoardStateMessage.PieceData(
                    pv.kind(), pv.color(),
                    new BoardStateMessage.PositionData(pv.position().getRow(), pv.position().getCol()),
                    pv.restUntilMs(), pv.restStartMs()));
        }

        BoardStateMessage.PositionData rejectedPos = null;
        if (snapshot.rejectedDest() != null) {
            rejectedPos = new BoardStateMessage.PositionData(
                    snapshot.rejectedDest().getRow(), snapshot.rejectedDest().getCol());
        }

        List<BoardStateMessage.MotionData> motionData = new ArrayList<>();
        for (Motion m : engine.getArbiter().getPendingMotions()) {
            motionData.add(new BoardStateMessage.MotionData(
                    m.getPiece().getKind(), m.getPiece().getColor(),
                    new BoardStateMessage.PositionData(m.getFrom().getRow(), m.getFrom().getCol()),
                    new BoardStateMessage.PositionData(m.getTo().getRow(), m.getTo().getCol()),
                    m.getStartTime(), m.getDueTime(), m.isJump(), m.isDodge()));
        }

        List<BoardStateMessage.MoveLogEntryData> moveLogData = new ArrayList<>();
        for (GameEngine.GameSnapshot.MoveLogEntry e : snapshot.moveLog()) {
            moveLogData.add(new BoardStateMessage.MoveLogEntryData(
                    e.timestamp(), e.color(), e.pieceKind(),
                    e.from() != null ? new BoardStateMessage.PositionData(e.from().getRow(), e.from().getCol()) : null,
                    e.to() != null ? new BoardStateMessage.PositionData(e.to().getRow(), e.to().getCol()) : null,
                    e.capturedKind(), e.isJump(), e.isPromoted(), e.isDodge(), e.isScream()));
        }

        int whiteElo = room.getWhite() != null ? room.getWhite().getElo() : 0;
        int blackElo = room.getBlack() != null ? room.getBlack().getElo() : 0;
        String[][] boardArray = BoardSerializer.toArray(engine.getBoard());

        return new BoardStateMessage(
                boardArray, pieceDataList, snapshot.boardWidth(), snapshot.boardHeight(),
                snapshot.gameOver(), snapshot.turn(), snapshot.clock(),
                snapshot.scoreWhite(), snapshot.scoreBlack(), rejectedPos, snapshot.winner(),
                motionData, moveLogData, whiteElo, blackElo);
    }

    private void sendError(WebSocket conn, String reason) {
        conn.send(gson.toJson(new ErrorMessage(reason)));
    }
}
