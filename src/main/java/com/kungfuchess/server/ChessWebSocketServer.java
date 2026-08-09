package com.kungfuchess.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kungfuchess.auth.UserRepository;
import com.kungfuchess.bus.EventBus;
import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import com.kungfuchess.net.AuthResultMessage;
import com.kungfuchess.net.BoardSerializer;
import com.kungfuchess.net.BoardStateMessage;
import com.kungfuchess.net.DisconnectCountdownMessage;
import com.kungfuchess.net.DodgeMessage;
import com.kungfuchess.net.ErrorMessage;
import com.kungfuchess.net.LoginMessage;
import com.kungfuchess.net.MoveMessage;
import com.kungfuchess.net.MoveNotation;
import com.kungfuchess.net.RoomCreateMessage;
import com.kungfuchess.net.RoomErrorMessage;
import com.kungfuchess.net.RoomInfoMessage;
import com.kungfuchess.net.RoomJoinMessage;
import com.kungfuchess.net.ScreamMessage;
import com.kungfuchess.realtime.Motion;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket server for multiplayer Kung-Fu Chess.
 *
 * <p>Every game lives in its own isolated {@link Room} (own {@link GameEngine}, own
 * board, own clock) — connections never share a board with a game they aren't part
 * of. A connection creates a room (gets a 4-character code back), joins one by code,
 * or requests a quick ELO-matched game via {@link Matchmaker}; either way it ends up
 * assigned WHITE/BLACK in a room, or SPECTATOR if the room already has both players.</p>
 *
 * <p>A single background tick advances every active room's real-time clock and
 * broadcasts a fresh {@link BoardStateMessage} at a steady interval, so cooldown/motion
 * progress streams smoothly instead of jumping once per move.</p>
 */
public class ChessWebSocketServer extends WebSocketServer {

    private static final int MAX_LOGIN_ATTEMPTS = 3;
    private static final int DISCONNECT_COUNTDOWN_SECONDS = 20;
    private static final long TICK_MS = 150L;
    private static final int ROOM_CREATE_TIMEOUT_SECONDS = 30;

    private final Gson gson = new Gson();
    private final UserRepository userRepository;
    private final Matchmaker matchmaker;
    private final RoomManager roomManager = new RoomManager();

    private final ConcurrentHashMap<WebSocket, ServerSession> authenticatedSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WebSocket, Integer> loginAttempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> disconnectCountdowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> roomCreationTimeouts = new ConcurrentHashMap<>();

    private final ScheduledExecutorService disconnectScheduler = Executors.newScheduledThreadPool(2);
    private final ScheduledExecutorService tickScheduler = Executors.newSingleThreadScheduledExecutor();

    public ChessWebSocketServer(int port) {
        this(port, new UserRepository());
    }

    public ChessWebSocketServer(int port, UserRepository userRepository) {
        super(new InetSocketAddress(port));
        this.userRepository = userRepository;
        this.matchmaker = new Matchmaker(this::onMatchFound);
    }

    // ── connection lifecycle ────────────────────────────────────────────────

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("[Server] New connection from: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        ServerSession session = authenticatedSessions.remove(conn);
        loginAttempts.remove(conn);
        if (session == null) {
            System.out.println("[Server] Connection closed: " + conn.getRemoteSocketAddress() + " - " + reason);
            return;
        }

        matchmaker.removeFromQueue(session);

        Room room = session.getRoom();
        System.out.println("[Server] Connection closed: " + session.getUsername()
                + " (" + session.getColor() + ") - " + reason);

        if (room == null) {
            return;
        }
        if (session.isSpectator()) {
            room.removeSpectator(session);
            return;
        }

        ServerSession opponent = room.opponentOf(session);
        if (opponent != null && opponent.getConnection().isOpen() && !room.getEngine().isGameOver()) {
            startDisconnectCountdown(room, session, opponent);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[Server] Error occurred: " + ex.getMessage());
        ex.printStackTrace();
    }

    @Override
    public void onStart() {
        System.out.println("[Server] WebSocket server started successfully");
        setConnectionLostTimeout(0);
        setConnectionLostTimeout(100);
        tickScheduler.scheduleAtFixedRate(this::tickAllRooms, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    // ── message dispatch ─────────────────────────────────────────────────────

    @Override
    public void onMessage(WebSocket conn, String message) {
        System.out.println("[Server] Received: " + message);
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            String type = json.has("type") ? json.get("type").getAsString() : "UNKNOWN";

            switch (type) {
                case "LOGIN" -> handleLoginMessage(conn, message);
                case "ROOM_CREATE" -> handleRoomCreate(conn);
                case "ROOM_JOIN" -> handleRoomJoin(conn, message);
                case "PLAY" -> handlePlayRequest(conn);
                case "MOVE" -> handleMoveMessage(conn, message);
                case "SCREAM" -> handleScreamMessage(conn, message);
                case "DODGE" -> handleDodgeMessage(conn, message);
                default -> sendError(conn, "Unknown message type: " + type);
            }
        } catch (Exception e) {
            sendError(conn, "Invalid message format: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ── login ─────────────────────────────────────────────────────────────────

    private void handleLoginMessage(WebSocket conn, String message) {
        try {
            LoginMessage loginMsg = gson.fromJson(message, LoginMessage.class);

            if (loginMsg.getUsername() == null || loginMsg.getUsername().trim().isEmpty()) {
                sendAuthResult(conn, false, "Username cannot be empty", 0);
                return;
            }
            if (loginMsg.getPassword() == null || loginMsg.getPassword().isEmpty()) {
                sendAuthResult(conn, false, "Password cannot be empty", 0);
                return;
            }

            String username = loginMsg.getUsername();
            String password = loginMsg.getPassword();

            boolean authenticated;
            int elo;

            if (!userRepository.userExists(username)) {
                System.out.println("[Server] Registering new user: " + username);
                if (!userRepository.registerUser(username, password)) {
                    sendAuthResult(conn, false, "Failed to register user", 0);
                    return;
                }
                authenticated = true;
                elo = userRepository.getElo(username);
                System.out.println("[Server] User registered successfully: " + username + " (ELO: " + elo + ")");
            } else {
                authenticated = userRepository.verifyPassword(username, password);
                if (authenticated) {
                    elo = userRepository.getElo(username);
                    System.out.println("[Server] User authenticated: " + username + " (ELO: " + elo + ")");
                } else {
                    int attempts = loginAttempts.getOrDefault(conn, 0) + 1;
                    loginAttempts.put(conn, attempts);
                    System.out.println("[Server] Authentication failed for " + username
                            + " (attempt " + attempts + "/" + MAX_LOGIN_ATTEMPTS + ")");
                    if (attempts >= MAX_LOGIN_ATTEMPTS) {
                        sendAuthResult(conn, false, "Maximum login attempts exceeded", 0);
                        conn.close(1000, "Authentication failed");
                    } else {
                        sendAuthResult(conn, false, "Invalid password (attempt " + attempts
                                + "/" + MAX_LOGIN_ATTEMPTS + ")", 0);
                    }
                    return;
                }
            }

            sendAuthResult(conn, true, "Authentication successful", elo);

            // No room/color assigned yet — the client now chooses Quick Play or Room
            // create/join. See handlePlayRequest / handleRoomCreate / handleRoomJoin.
            ServerSession session = new ServerSession(conn, username, null, elo);
            authenticatedSessions.put(conn, session);
            System.out.println("[Server] " + username + " logged in, awaiting Play/Room choice");

        } catch (Exception e) {
            sendAuthResult(conn, false, "Failed to process login: " + e.getMessage(), 0);
            e.printStackTrace();
        }
    }

    private void sendAuthResult(WebSocket conn, boolean success, String reason, int elo) {
        conn.send(gson.toJson(new AuthResultMessage(success, reason, elo)));
    }

    // ── rooms: create / join ─────────────────────────────────────────────────

    private void handleRoomCreate(WebSocket conn) {
        ServerSession session = authenticatedSessions.get(conn);
        if (session == null) {
            sendError(conn, "Must login before creating a room");
            return;
        }
        if (session.getRoom() != null) {
            sendError(conn, "Already in a room");
            return;
        }

        Room room = roomManager.createRoom();
        session.setColor("WHITE");
        session.setRoom(room);
        room.setWhite(session);

        System.out.println("[Server] " + session.getUsername() + " created room " + room.getRoomId());
        sendRoomInfo(session, room);
        scheduleRoomCreationTimeout(room, session);
    }

    /** If nobody joins within {@link #ROOM_CREATE_TIMEOUT_SECONDS}, notify the creator and close the room. */
    private void scheduleRoomCreationTimeout(Room room, ServerSession creator) {
        ScheduledFuture<?> timeout = disconnectScheduler.schedule(() -> {
            roomCreationTimeouts.remove(room.getRoomId());
            if (room.getBlack() != null) {
                return; // someone joined in time — nothing to do
            }
            System.out.println("[Server] room " + room.getRoomId() + " timed out — nobody joined within "
                    + ROOM_CREATE_TIMEOUT_SECONDS + "s");
            roomManager.removeRoom(room.getRoomId());
            creator.setRoom(null);
            creator.setColor(null);
            if (creator.getConnection().isOpen()) {
                creator.getConnection().send(gson.toJson(new RoomErrorMessage(
                        "No one joined room " + room.getRoomId() + " within " + ROOM_CREATE_TIMEOUT_SECONDS
                                + " seconds. Try again.")));
            }
        }, ROOM_CREATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        roomCreationTimeouts.put(room.getRoomId(), timeout);
    }

    private void cancelRoomCreationTimeout(String roomId) {
        ScheduledFuture<?> timeout = roomCreationTimeouts.remove(roomId);
        if (timeout != null) {
            timeout.cancel(false);
        }
    }

    private void handleRoomJoin(WebSocket conn, String message) {
        ServerSession session = authenticatedSessions.get(conn);
        if (session == null) {
            sendError(conn, "Must login before joining a room");
            return;
        }
        if (session.getRoom() != null) {
            sendError(conn, "Already in a room");
            return;
        }

        RoomJoinMessage joinMsg = gson.fromJson(message, RoomJoinMessage.class);
        Room room = roomManager.getRoom(joinMsg.getRoomId());
        if (room == null) {
            conn.send(gson.toJson(new RoomErrorMessage("Room '" + joinMsg.getRoomId() + "' not found.")));
            return;
        }

        if (room.getBlack() != null) {
            // Room already has both players — reject rather than silently spectating.
            conn.send(gson.toJson(new RoomErrorMessage(
                    "Room '" + room.getRoomId() + "' is full — the game has already started.")));
            return;
        }

        cancelRoomCreationTimeout(room.getRoomId());
        session.setColor("BLACK");
        session.setRoom(room);
        room.setBlack(session);
        room.setStarted(true);
        subscribeGameEndedForRoom(room);

        System.out.println("[Server] " + session.getUsername() + " joined room " + room.getRoomId()
                + " as BLACK — game starting");
        sendRoomInfo(room.getWhite(), room);
        sendRoomInfo(room.getBlack(), room);
        broadcastRoomBoardState(room);
    }

    private void sendRoomInfo(ServerSession target, Room room) {
        RoomInfoMessage msg = new RoomInfoMessage(
                room.getRoomId(), target.getColor(), room.getWhiteName(), room.getBlackName(), room.isStarted());
        target.getConnection().send(gson.toJson(msg));
    }

    // ── matchmaking (Play button) ────────────────────────────────────────────

    private void handlePlayRequest(WebSocket conn) {
        ServerSession session = authenticatedSessions.get(conn);
        if (session == null) {
            sendError(conn, "Must login before requesting a match");
            return;
        }
        if (matchmaker.isInQueue(session)) {
            sendError(conn, "Already in matchmaking queue");
            return;
        }
        if (session.getRoom() != null) {
            sendError(conn, "Already in a room");
            return;
        }

        System.out.println("[Server] " + session.getUsername() + " requested a match");
        matchmaker.addToQueue(session);
    }

    /** Called by {@link Matchmaker} once two queued players are ELO-compatible. */
    private void onMatchFound(ServerSession player1, ServerSession player2) {
        Room room = roomManager.createMatchedRoom();
        player1.setColor("WHITE");
        player1.setRoom(room);
        room.setWhite(player1);

        player2.setColor("BLACK");
        player2.setRoom(room);
        room.setBlack(player2);

        room.setStarted(true);
        subscribeGameEndedForRoom(room);

        System.out.println("[Server] Matched game starting in room " + room.getRoomId() + ": "
                + player1.getUsername() + " vs " + player2.getUsername());
        sendRoomInfo(player1, room);
        sendRoomInfo(player2, room);
        broadcastRoomBoardState(room);
    }

    // ── moves ─────────────────────────────────────────────────────────────────

    private void handleMoveMessage(WebSocket conn, String message) {
        Room room = requireActiveRoom(conn);
        if (room == null) return;

        try {
            MoveMessage moveMsg = gson.fromJson(message, MoveMessage.class);
            if (moveMsg.getFrom() == null || moveMsg.getTo() == null) {
                sendError(conn, "Move message missing 'from' or 'to' field");
                return;
            }
            Position from = parseSquare(moveMsg.getFrom());
            Position to = parseSquare(moveMsg.getTo());
            ServerSession session = authenticatedSessions.get(conn);

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
        } catch (IllegalArgumentException e) {
            sendError(conn, "Invalid square notation: " + e.getMessage());
        } catch (Board.OutOfBoundsException e) {
            sendError(conn, "Move out of bounds: " + e.getMessage());
        } catch (Exception e) {
            sendError(conn, e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleScreamMessage(WebSocket conn, String message) {
        Room room = requireActiveRoom(conn);
        if (room == null) return;

        try {
            ScreamMessage screamMsg = gson.fromJson(message, ScreamMessage.class);
            if (screamMsg.getFrom() == null || screamMsg.getTo() == null) {
                sendError(conn, "Scream message missing 'from' or 'to' field");
                return;
            }
            Position from = parseSquare(screamMsg.getFrom());
            Position to = parseSquare(screamMsg.getTo());
            ServerSession session = authenticatedSessions.get(conn);

            synchronized (room) {
                GameEngine engine = room.getEngine();
                Optional<Piece> screamerPiece = engine.getBoard().pieceAt(from);
                if (screamerPiece.isPresent() && !screamerPiece.get().getColor().equalsIgnoreCase(session.getColor())) {
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
        } catch (IllegalArgumentException e) {
            sendError(conn, "Invalid square notation: " + e.getMessage());
        } catch (Board.OutOfBoundsException e) {
            sendError(conn, "Scream out of bounds: " + e.getMessage());
        } catch (Exception e) {
            sendError(conn, e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleDodgeMessage(WebSocket conn, String message) {
        Room room = requireActiveRoom(conn);
        if (room == null) return;

        try {
            DodgeMessage dodgeMsg = gson.fromJson(message, DodgeMessage.class);
            if (dodgeMsg.getSquare() == null) {
                sendError(conn, "Dodge message missing 'square' field");
                return;
            }
            Position square = parseSquare(dodgeMsg.getSquare());
            ServerSession session = authenticatedSessions.get(conn);

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
        } catch (IllegalArgumentException e) {
            sendError(conn, "Invalid square notation: " + e.getMessage());
        } catch (Board.OutOfBoundsException e) {
            sendError(conn, "Dodge out of bounds: " + e.getMessage());
        } catch (Exception e) {
            sendError(conn, e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Flashes the rejected destination square for the requesting connection only —
     * a rejected move/scream/dodge must not be visible to the opponent or spectators
     * (they never attempted it, and shouldn't see or hear it). Sends one private
     * BoardStateMessage carrying the flash, then immediately clears it so the room's
     * regular broadcasts (next tick, other players) never carry it.
     */
    private void rejectPrivately(WebSocket conn, Room room, Position dest) {
        GameEngine engine = room.getEngine();
        engine.recordRejection(dest);
        sendBoardState(conn, room);
        engine.recordRejection(null);
    }

    /** Common MOVE/SCREAM/DODGE gating: logged in, in a started room, not a spectator. */
    private Room requireActiveRoom(WebSocket conn) {
        ServerSession session = authenticatedSessions.get(conn);
        if (session == null) {
            sendError(conn, "Must login before sending moves");
            return null;
        }
        Room room = session.getRoom();
        if (room == null) {
            sendError(conn, "You are not in a room");
            return null;
        }
        if (!room.isStarted()) {
            sendError(conn, "Waiting for opponent to join");
            return null;
        }
        if (session.isSpectator()) {
            sendError(conn, "Spectators cannot move");
            return null;
        }
        return room;
    }

    private Position parseSquare(String square) {
        if (square == null || square.length() != 2) {
            throw new IllegalArgumentException("Square must be 2 characters (e.g., 'e2')");
        }
        return MoveNotation.parseSquare(square.charAt(0), square.charAt(1));
    }

    // ── real-time tick: advances every active room's clock, broadcasts smoothly ─

    private void tickAllRooms() {
        for (Room room : roomManager.activeRooms()) {
            if (!room.isStarted() || room.getEngine().isGameOver()) {
                continue;
            }
            try {
                synchronized (room) {
                    room.getEngine().waitMs(TICK_MS);
                    broadcastRoomBoardState(room);
                }
            } catch (Exception e) {
                System.err.println("[Server] Tick error in room " + room.getRoomId() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // ── game end / ELO ───────────────────────────────────────────────────────

    private void subscribeGameEndedForRoom(Room room) {
        room.getEngine().getEventBus().subscribe(EventBus.GAME_ENDED, payload -> handleRoomGameEnded(room, payload));
    }

    private void handleRoomGameEnded(Room room, Object payload) {
        String message = payload == null ? "" : payload.toString();
        String winnerColor = message.contains("winner: white") ? "white"
                : message.contains("winner: black") ? "black" : null;
        if (winnerColor == null) {
            System.err.println("[Server] Could not determine winner from game-ended event: " + message);
            return;
        }
        ServerSession winner = "white".equals(winnerColor) ? room.getWhite() : room.getBlack();
        ServerSession loser = "white".equals(winnerColor) ? room.getBlack() : room.getWhite();
        finishGame(room, winner, loser);
    }

    /** Updates ELO, broadcasts the final board (already carries gameOver+winner), retires the room. */
    private void finishGame(Room room, ServerSession winner, ServerSession loser) {
        try {
            int winnerOldElo = userRepository.getElo(winner.getUsername());
            int loserOldElo = userRepository.getElo(loser.getUsername());
            userRepository.updateElo(winner.getUsername(), loser.getUsername());
            // Refresh the cached ELO on both sessions — they may keep playing in the
            // same connection ("Play Again"), and both matchmaking and the client's
            // own display must see the new rating, not the one from login time.
            winner.setElo(userRepository.getElo(winner.getUsername()));
            loser.setElo(userRepository.getElo(loser.getUsername()));
            System.out.println("[Server] room " + room.getRoomId() + " over — " + winner.getUsername()
                    + " (ELO " + winnerOldElo + "->" + winner.getElo() + ") beat "
                    + loser.getUsername() + " (ELO " + loserOldElo + "->" + loser.getElo() + ")");
        } catch (Exception e) {
            System.err.println("[Server] Failed to update ELO ratings: " + e.getMessage());
            e.printStackTrace();
        }
        broadcastRoomBoardState(room);
        disconnectCountdowns.remove(room.getRoomId());
        roomManager.removeRoom(room.getRoomId());

        // Free every participant to create/join/queue for a brand new game ("Play Again") —
        // without this their ServerSession would still point at the now-retired room.
        for (ServerSession s : room.getAllSessions()) {
            s.setRoom(null);
            s.setColor(null);
        }
    }

    // ── disconnect grace period ──────────────────────────────────────────────

    private void startDisconnectCountdown(Room room, ServerSession disconnected, ServerSession opponent) {
        if (disconnectCountdowns.containsKey(room.getRoomId())) {
            return;
        }
        System.out.println("[Server] Starting disconnect countdown for opponent: " + opponent.getUsername());

        ScheduledFuture<?> countdownTask = disconnectScheduler.schedule(() -> {
            try {
                for (int i = DISCONNECT_COUNTDOWN_SECONDS; i > 0; i--) {
                    if (!opponent.getConnection().isOpen() || room.getEngine().isGameOver()) {
                        disconnectCountdowns.remove(room.getRoomId());
                        return;
                    }
                    opponent.getConnection().send(gson.toJson(new DisconnectCountdownMessage(i)));
                    System.out.println("[Server] room " + room.getRoomId() + " disconnect countdown: " + i + "s remaining");
                    if (i > 1) {
                        Thread.sleep(1000);
                    }
                }
                if (!room.getEngine().isGameOver()) {
                    System.out.println("[Server] " + disconnected.getUsername() + " forfeits room " + room.getRoomId());
                    room.getEngine().setGameOver(true);
                    finishGame(room, opponent, disconnected);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("[Server] Error in disconnect countdown: " + e.getMessage());
                e.printStackTrace();
            } finally {
                disconnectCountdowns.remove(room.getRoomId());
            }
        }, 0, TimeUnit.SECONDS);

        disconnectCountdowns.put(room.getRoomId(), countdownTask);
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

    private void sendBoardState(WebSocket conn, Room room) {
        conn.send(gson.toJson(buildBoardStateMessage(room)));
    }

    /** Builds a BoardStateMessage from a room's engine snapshot — full state, cooldowns, live ELO included. */
    private BoardStateMessage buildBoardStateMessage(Room room) {
        GameEngine engine = room.getEngine();
        GameEngine.GameSnapshot snapshot = engine.snapshot();

        List<BoardStateMessage.PieceData> pieceDataList = new java.util.ArrayList<>();
        for (GameEngine.GameSnapshot.PieceView pv : snapshot.pieces()) {
            BoardStateMessage.PositionData posData = new BoardStateMessage.PositionData(
                    pv.position().getRow(), pv.position().getCol());
            pieceDataList.add(new BoardStateMessage.PieceData(
                    pv.kind(), pv.color(), posData, pv.restUntilMs(), pv.restStartMs()));
        }

        BoardStateMessage.PositionData rejectedPos = null;
        if (snapshot.rejectedDest() != null) {
            rejectedPos = new BoardStateMessage.PositionData(
                    snapshot.rejectedDest().getRow(), snapshot.rejectedDest().getCol());
        }

        String[][] boardArray = BoardSerializer.toArray(engine.getBoard());

        List<BoardStateMessage.MotionData> motionData = new java.util.ArrayList<>();
        for (Motion m : engine.getArbiter().getPendingMotions()) {
            motionData.add(new BoardStateMessage.MotionData(
                    m.getPiece().getKind(), m.getPiece().getColor(),
                    new BoardStateMessage.PositionData(m.getFrom().getRow(), m.getFrom().getCol()),
                    new BoardStateMessage.PositionData(m.getTo().getRow(), m.getTo().getCol()),
                    m.getStartTime(), m.getDueTime(), m.isJump(), m.isDodge()));
        }

        List<BoardStateMessage.MoveLogEntryData> moveLogData = new java.util.ArrayList<>();
        for (GameEngine.GameSnapshot.MoveLogEntry e : snapshot.moveLog()) {
            moveLogData.add(new BoardStateMessage.MoveLogEntryData(
                    e.timestamp(), e.color(), e.pieceKind(),
                    e.from() != null ? new BoardStateMessage.PositionData(e.from().getRow(), e.from().getCol()) : null,
                    e.to() != null ? new BoardStateMessage.PositionData(e.to().getRow(), e.to().getCol()) : null,
                    e.capturedKind(), e.isJump(), e.isPromoted(), e.isDodge(), e.isScream()));
        }

        int whiteElo = room.getWhite() != null ? room.getWhite().getElo() : 0;
        int blackElo = room.getBlack() != null ? room.getBlack().getElo() : 0;

        return new BoardStateMessage(
                boardArray, pieceDataList, snapshot.boardWidth(), snapshot.boardHeight(),
                snapshot.gameOver(), snapshot.turn(), snapshot.clock(),
                snapshot.scoreWhite(), snapshot.scoreBlack(), rejectedPos, snapshot.winner(),
                motionData, moveLogData, whiteElo, blackElo);
    }

    private void sendError(WebSocket conn, String reason) {
        conn.send(gson.toJson(new ErrorMessage(reason)));
    }

    /** Shuts down the server and cleans up resources. */
    public void shutdown() {
        matchmaker.shutdown();
        tickScheduler.shutdown();
        disconnectScheduler.shutdown();
        try {
            if (!disconnectScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                disconnectScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            disconnectScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
