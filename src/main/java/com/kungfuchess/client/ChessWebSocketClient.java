package com.kungfuchess.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.input.BoardMapper;
import com.kungfuchess.io.BoardParser;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import com.kungfuchess.net.AuthResultMessage;
import com.kungfuchess.net.BoardSerializer;
import com.kungfuchess.net.BoardStateMessage;
import com.kungfuchess.net.ColorMessage;
import com.kungfuchess.net.DisconnectCountdownMessage;
import com.kungfuchess.net.DodgeMessage;
import com.kungfuchess.net.ErrorMessage;
import com.kungfuchess.net.LoginMessage;
import com.kungfuchess.net.MoveMessage;
import com.kungfuchess.net.NoMatchMessage;
import com.kungfuchess.net.PlayRequestMessage;
import com.kungfuchess.net.RoomCreateMessage;
import com.kungfuchess.net.RoomErrorMessage;
import com.kungfuchess.net.RoomInfoMessage;
import com.kungfuchess.net.RoomJoinMessage;
import com.kungfuchess.net.ScreamMessage;
import com.kungfuchess.net.ServerFullMessage;
import com.kungfuchess.realtime.Motion;
import com.kungfuchess.view.Renderer;
import com.kungfuchess.view.SoundManager;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * WebSocket client for connecting to the Kung-Fu Chess server.
 *
 * <p>Receives board state updates from the server via JSON and allows sending moves
 * via {@link #sendMove(String, String)}.</p>
 *
 * <p>Supports both console mode (ASCII text) and GUI mode (graphical renderer).</p>
 */
public class ChessWebSocketClient extends WebSocketClient {

    private final Gson gson;
    private Renderer renderer;
    private SoundManager soundManager;
    private boolean consoleMode = true;
    private String assignedColor;
    private Consumer<String> onColorAssigned;
    private BiConsumer<Boolean, String> onAuthResult;
    private Runnable onOpponentJoined;
    private boolean opponentJoinedFired = false;
    private Consumer<RoomInfoMessage> onRoomInfo;
    private Consumer<String> onRoomError;
    private Runnable onNoMatch;
    private String currentRoomId;
    private java.util.function.IntConsumer onOpponentDisconnectedCountdown;
    private Consumer<String> onGameOver;
    private boolean gameOverFired = false;
    private int lastKnownElo = 1200;
    private BiConsumer<String, String> moveCallback;
    private BiConsumer<String, String> screamCallback;
    private Consumer<String> dodgeCallback;
    private boolean mouseListenerAttached = false;
    private Position localSelectedSquare = null;  // Track selection client-side for highlight
    private Board currentBoard = null;  // Track current board state for capture detection
    private int previousPieceCount = 32;  // Track pieces for capture detection
    // Motions in flight on the previous snapshot — used to detect genuine arrivals
    // (server now broadcasts every ~150ms regardless of activity, so "a BOARD_STATE
    // arrived" no longer means "a piece just landed").
    private java.util.Set<String> previousMotionIds = new java.util.HashSet<>();
    private int previousMoveLogSize = 0;  // moveLog only grows — used to detect new (e.g. promotion) entries
    
    // Store player names to handle race condition (names may arrive before renderer exists)
    private String storedWhiteName = null;
    private String storedBlackName = null;
    
    // Cooldown animation: interpolate clock locally for smooth real-time updates
    private GameEngine.GameSnapshot lastReceivedSnapshot = null;
    private long lastSnapshotReceivedAt = 0;  // Wall-clock time (System.currentTimeMillis)
    private javax.swing.Timer cooldownAnimationTimer = null;

    public ChessWebSocketClient(URI serverUri) {
        super(serverUri);
        this.gson = new Gson();
    }
    
    /**
     * Sets the renderer for GUI mode.
     * @param renderer the renderer to use for displaying board state
     * @param consoleMode whether to also print ASCII to console
     */
    public void setRenderer(Renderer renderer, boolean consoleMode) {
        this.renderer = renderer;
        this.soundManager = new SoundManager();
        this.consoleMode = consoleMode;
        
        // Apply stored player names if they arrived before renderer was created (race condition fix)
        if (storedWhiteName != null && storedBlackName != null) {
            System.out.println("[Client] Applying stored player names to newly created renderer: WHITE=" + storedWhiteName + ", BLACK=" + storedBlackName);
            renderer.setPlayerNames(storedWhiteName, storedBlackName);
        }
    }
    
    /**
     * Sets the client mode and assigned color.
     */
    public void setClientMode(boolean consoleMode, String assignedColor) {
        this.consoleMode = consoleMode;
        this.assignedColor = assignedColor;
    }
    
    /**
     * Sets callback for when color is assigned by server.
     */
    public void setOnColorAssigned(Consumer<String> callback) {
        this.onColorAssigned = callback;
    }

    /**
     * Sets callback for when the server responds to a login attempt.
     * Invoked with (success, reason) — reason is a human-readable message in both cases.
     */
    public void setOnAuthResult(BiConsumer<Boolean, String> callback) {
        this.onAuthResult = callback;
    }

    /**
     * Sets callback fired the first time both players' real names are known
     * (i.e. the opponent has joined and is no longer "Waiting...").
     */
    public void setOnOpponentJoined(Runnable callback) {
        this.onOpponentJoined = callback;
    }

    /** Sets callback fired on every ROOM_INFO (role/room-code updates, incl. before game start). */
    public void setOnRoomInfo(Consumer<RoomInfoMessage> callback) {
        this.onRoomInfo = callback;
    }

    /** Sets callback fired when a room-related request fails (e.g. unknown room code). */
    public void setOnRoomError(Consumer<String> callback) {
        this.onRoomError = callback;
    }

    /** Sets callback fired when a Quick Match search times out with no opponent found. */
    public void setOnNoMatch(Runnable callback) {
        this.onNoMatch = callback;
    }

    /** Sets callback fired once per second while the opponent is disconnected (seconds left). */
    public void setOnOpponentDisconnectedCountdown(java.util.function.IntConsumer callback) {
        this.onOpponentDisconnectedCountdown = callback;
    }

    /**
     * Sets callback fired once, the first time a BOARD_STATE arrives with the game over.
     * Receives the winning color ("white"/"black"), or null if undetermined.
     */
    public void setOnGameOver(Consumer<String> callback) {
        this.onGameOver = callback;
    }

    /** @return the ELO reported by the server on the most recent successful login. */
    public int getLastKnownElo() {
        return lastKnownElo;
    }

    /**
     * Resets all per-game mutable state so this connection can play another game
     * ("Play Again") without carrying over stale flags/timers/references from the
     * previous one.
     */
    public void resetForNewGame() {
        if (cooldownAnimationTimer != null) {
            cooldownAnimationTimer.stop();
            cooldownAnimationTimer = null;
        }
        opponentJoinedFired = false;
        gameOverFired = false;
        mouseListenerAttached = false;
        previousPieceCount = 32;
        previousMotionIds = new java.util.HashSet<>();
        previousMoveLogSize = 0;
        lastReceivedSnapshot = null;
        storedWhiteName = null;
        storedBlackName = null;
        currentRoomId = null;
        assignedColor = null;
        renderer = null;
        soundManager = null;
    }

    /** @return {@code true} once both players have joined (opponent is no longer "Waiting..."). */
    public boolean isOpponentPresent() {
        return opponentJoinedFired;
    }
    
    /**
     * Sets callback for when a move is made via mouse click (GUI mode).
     */
    public void setMoveCallback(BiConsumer<String, String> callback) {
        this.moveCallback = callback;
    }

    /**
     * Sets callback for when a Scream is triggered via right-click (GUI mode):
     * select a piece, then right-click an adjacent enemy.
     */
    public void setScreamCallback(BiConsumer<String, String> callback) {
        this.screamCallback = callback;
    }

    /**
     * Sets callback for when a Dodge is triggered via a same-square second click
     * (select a piece, then click it again while it's threatened).
     */
    public void setDodgeCallback(Consumer<String> callback) {
        this.dodgeCallback = callback;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        System.out.println("[Client] Connected to server");
    }

    @Override
    public void onMessage(String message) {
        try {
            // Parse the type field first
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            String type = json.has("type") ? json.get("type").getAsString() : "UNKNOWN";

            switch (type) {
                case "BOARD_STATE":
                    handleBoardState(message);
                    break;
                case "ERROR":
                    handleError(message);
                    break;
                case "AUTH_RESULT":
                    handleAuthResult(message);
                    break;
                case "COLOR":
                    handleColor(message);
                    break;
                case "FULL":
                    handleServerFull(message);
                    break;
                case "NO_MATCH_FOUND":
                    handleNoMatchFound(message);
                    break;
                case "OPPONENT_DISCONNECTED":
                    handleOpponentDisconnected(message);
                    break;
                case "ROOM_INFO":
                    handleRoomInfo(message);
                    break;
                case "ROOM_ERROR":
                    handleRoomError(message);
                    break;
                default:
                    System.err.println("[Client] Unknown message type: " + type);
                    break;
            }
        } catch (Exception e) {
            System.err.println("[Client] Failed to parse message: " + e.getMessage());
        }
    }

    private void handleBoardState(String message) {
        try {
            BoardStateMessage msg = gson.fromJson(message, BoardStateMessage.class);

            // Always print to console in console mode
            if (consoleMode) {
                System.out.println("\n=== Current Board ===");
                System.out.println(BoardSerializer.formatBoard(msg.getBoard()));
                System.out.println("====================\n");
            }

            // Render to GUI if renderer is available
            if (renderer != null && !consoleMode) {
                try {
                    // Reconstruct GameSnapshot from rich message data
                    GameEngine.GameSnapshot snapshot = buildSnapshotFromMessage(msg);

                    // Store snapshot and wall-clock time for cooldown animation
                    lastReceivedSnapshot = snapshot;
                    lastSnapshotReceivedAt = System.currentTimeMillis();
                    
                    // Start cooldown animation timer if not already running
                    if (cooldownAnimationTimer == null) {
                        startCooldownAnimationTimer();
                    }
                    
                    // Detect captures and game-over state for sound effects
                    int newPieceCount = snapshot.pieces().size();
                    boolean captureDetected = false;
                    if (newPieceCount < previousPieceCount && soundManager != null) {
                        System.out.println("[Client] Capture detected: " + previousPieceCount + " -> " + newPieceCount + " pieces");
                        soundManager.playCapture();
                        captureDetected = true;
                    }
                    previousPieceCount = newPieceCount;

                    // Detect a genuine arrival (a motion that was in-flight last snapshot
                    // and no longer is) — the server now broadcasts every ~150ms whether
                    // or not anything changed, so "a BOARD_STATE arrived" alone no longer
                    // means "a piece just landed". Also detect a newly-appeared Dodge motion
                    // (confirmed success — see sendDodge, which no longer plays optimistically).
                    java.util.Set<String> currentMotionIds = new java.util.HashSet<>();
                    boolean dodgeConfirmed = false;
                    for (Motion m : snapshot.activeMotions()) {
                        String id = m.getPiece().getKind() + "@" + m.getFrom() + "->" + m.getTo()
                                + "#" + m.getDueTime();
                        currentMotionIds.add(id);
                        if (m.isDodge() && !previousMotionIds.contains(id)) {
                            dodgeConfirmed = true;
                        }
                    }
                    boolean arrivalDetected = false;
                    for (String id : previousMotionIds) {
                        if (!currentMotionIds.contains(id)) {
                            arrivalDetected = true;
                            break;
                        }
                    }
                    previousMotionIds = currentMotionIds;
                    if (dodgeConfirmed && soundManager != null) {
                        soundManager.playDodge();
                    }

                    // Detect promotion: moveLog only grows during a game, so any entries
                    // beyond what we've already seen are new since the last snapshot.
                    List<GameEngine.GameSnapshot.MoveLogEntry> log = snapshot.moveLog();
                    for (int i = previousMoveLogSize; i < log.size(); i++) {
                        if (log.get(i).isPromoted() && soundManager != null) {
                            soundManager.playPromotion();
                        }
                    }
                    previousMoveLogSize = log.size();

                    // Play game-over sound if game just ended
                    if (snapshot.gameOver() && soundManager != null) {
                        System.out.println("[Client] Game over detected, playing game-over sound");
                        soundManager.playGameOver();
                    }
                    if (snapshot.gameOver() && !gameOverFired) {
                        gameOverFired = true;
                        if (onGameOver != null) {
                            onGameOver.accept(snapshot.winner());
                        }
                    }
                    if (arrivalDetected && !snapshot.gameOver() && !captureDetected && soundManager != null) {
                        // A piece actually landed this update (not just a periodic re-broadcast)
                        soundManager.playMoveLand();
                    }
                    
                    // Store current board for capture detection
                    currentBoard = boardStateToBoard(msg);

                    // Live ELO — refreshed every broadcast, so it reflects the latest
                    // rating even mid-session ("Play Again") instead of the value
                    // cached at login time.
                    if ("WHITE".equalsIgnoreCase(assignedColor)) {
                        lastKnownElo = msg.getWhiteElo();
                    } else if ("BLACK".equalsIgnoreCase(assignedColor)) {
                        lastKnownElo = msg.getBlackElo();
                    }

                    // CRITICAL: Render must happen on EDT, not on network thread!
                    // Wrap in SwingUtilities.invokeLater() to ensure thread safety
                    SwingUtilities.invokeLater(() -> {
                        renderer.setLocalPlayerInfo(assignedColor, lastKnownElo);
                        renderer.setRoomId(currentRoomId);
                        renderer.render(snapshot);

                        // Attach mouse listener after first render (when window is created)
                        // This must also run on EDT since it touches Swing components
                        if (!mouseListenerAttached && renderer.getLabel() != null) {
                            attachMouseListener();
                            mouseListenerAttached = true;
                        }
                    });
                } catch (Exception e) {
                    System.err.println("[Client] ERROR rendering board:");
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            System.err.println("[Client] ERROR in handleBoardState:");
            e.printStackTrace();
        }
    }
    
    /**
     * Builds a GameSnapshot from the rich BoardStateMessage.
     * Reuses GameSnapshot structure so existing Renderer draws cooldowns, highlights, etc.
     */
    private GameEngine.GameSnapshot buildSnapshotFromMessage(BoardStateMessage msg) {
        List<GameEngine.GameSnapshot.PieceView> pieces = new ArrayList<>();

        // Convert PieceData to PieceView if rich data is available
        if (msg.getPieces() != null) {
            for (BoardStateMessage.PieceData pd : msg.getPieces()) {
                Position pos = pd.getPosition().toPosition();
                pieces.add(new GameEngine.GameSnapshot.PieceView(
                    pd.getKind(),
                    pd.getColor(),
                    pos,
                    pd.getRestUntilMs(),
                    pd.getRestStartMs()
                ));
            }
        } else {
            // Fallback: build from flat board array (no cooldown data)
            String[][] grid = msg.getBoard();
            if (grid != null) {
                for (int r = 0; r < grid.length; r++) {
                    for (int c = 0; c < grid[r].length; c++) {
                        String cell = grid[r][c];
                        if (cell != null && !cell.equals(".")) {
                            String color = cell.startsWith("w") ? "white" : "black";
                            String kind = cell.substring(1);
                            pieces.add(new GameEngine.GameSnapshot.PieceView(
                                kind, color, new Position(r, c), 0L, 0L
                            ));
                        }
                    }
                }
            }
        }
        
        // Convert rejection position if present
        Position rejectedDest = null;
        if (msg.getLastRejectedDest() != null) {
            rejectedDest = msg.getLastRejectedDest().toPosition();
        }

        // In-flight motions — lets ImageView slide pieces smoothly and correctly
        // withhold the cooldown ring until the piece actually lands (matches the
        // local/offline rendering path exactly).
        List<Motion> activeMotions = new ArrayList<>();
        if (msg.getMotions() != null) {
            for (BoardStateMessage.MotionData md : msg.getMotions()) {
                activeMotions.add(new Motion(
                    new Piece(md.getKind(), md.getColor()),
                    md.getFrom().toPosition(),
                    md.getTo().toPosition(),
                    md.getStartTime(), md.getDueTime(), md.isJump(), md.isDodge(), null
                ));
            }
        }

        // Structured move log — drives the per-player move-log tables in the side panels.
        List<GameEngine.GameSnapshot.MoveLogEntry> moveLog = new ArrayList<>();
        if (msg.getMoveLog() != null) {
            for (BoardStateMessage.MoveLogEntryData led : msg.getMoveLog()) {
                moveLog.add(new GameEngine.GameSnapshot.MoveLogEntry(
                    led.getTimestamp(), led.getColor(), led.getPieceKind(),
                    led.getFrom() != null ? led.getFrom().toPosition() : null,
                    led.getTo() != null ? led.getTo().toPosition() : null,
                    led.getCapturedKind(), led.isJump(), led.isPromoted(), led.isDodge(), led.isScream()
                ));
            }
        }

        return new GameEngine.GameSnapshot(
            pieces,
            msg.getBoardWidth() > 0 ? msg.getBoardWidth() : 8,
            msg.getBoardHeight() > 0 ? msg.getBoardHeight() : 8,
            localSelectedSquare,  // Use locally-tracked selection for highlight
            msg.isGameOver(),
            msg.getTurn() != null ? msg.getTurn() : "white",
            activeMotions,
            msg.getClock(),
            msg.getScoreWhite(),
            msg.getScoreBlack(),
            moveLog,
            rejectedDest,
            msg.getWinner()
        );
    }
    
    /**
     * Attaches mouse listener to renderer for handling clicks.
     * Call this after the renderer window is created.
     */
    public void attachMouseListenerNow() {
        attachMouseListener();
        mouseListenerAttached = true;
    }
    
    /**
     * Internal method to attach mouse listener to renderer.
     */
    private void attachMouseListener() {
        System.out.println("[Client] attachMouseListener() called on thread: " + Thread.currentThread().getName());
        SwingUtilities.invokeLater(() -> {
            System.out.println("[Client] Inside SwingUtilities.invokeLater on EDT");
            JLabel label = renderer.getLabel();
            if (label == null) {
                System.err.println("[Client] ERROR: renderer.getLabel() returned NULL!");
                return;
            }
            
            System.out.println("[Client] Got label from renderer: " + label.getClass().getName());
            System.out.println("[Client] Label visible: " + label.isVisible());
            System.out.println("[Client] Label showing: " + label.isShowing());
            System.out.println("[Client] Label bounds: " + label.getBounds());
            
            renderer.getLabel().addMouseListener(new MouseAdapter() {
                private Position selectedSquare = null;
                
                @Override
                public void mousePressed(MouseEvent e) {
                    if ("SPECTATOR".equals(assignedColor)) {
                        return; // spectators can watch but never select/move pieces
                    }
                    System.out.println("[Client] Mouse event received at pixel (" + e.getX() + "," + e.getY() + ")");
                    try {
                        int rawX = renderer.rawX(e.getX());
                        int rawY = renderer.rawY(e.getY());
                        System.out.println("[Client] Converted to raw coordinates: (" + rawX + "," + rawY + ")");
                        
                        // Use BoardMapper (single source of truth) to convert pixel to board position
                        Position clicked = BoardMapper.pixelToBoard(rawX, rawY);
                        System.out.println("[Client] BoardMapper.pixelToBoard returned: " + clicked);
                        
                        // Check if click is within board bounds
                        if (clicked.getRow() < 0 || clicked.getRow() >= 8 ||
                            clicked.getCol() < 0 || clicked.getCol() >= 8) {
                            System.out.println("[Client] Click outside board bounds");
                            selectedSquare = null;
                            localSelectedSquare = null;
                            return;
                        }

                        // Right-click: if a piece is selected, treat this as a Scream
                        // target (server validates adjacency and enemy occupancy).
                        // Clears the selection regardless of outcome.
                        if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                            if (selectedSquare != null) {
                                String from = positionToAlgebraic(selectedSquare);
                                String to = positionToAlgebraic(clicked);
                                System.out.println("[Client] Right-click, sending scream: " + from + " -> " + to);
                                if (screamCallback != null) {
                                    screamCallback.accept(from, to);
                                } else {
                                    System.err.println("[Client] ERROR: screamCallback is NULL!");
                                }
                            }
                            selectedSquare = null;
                            localSelectedSquare = null;
                            return;
                        }

                        if (selectedSquare == null) {
                            // First click: only your own pieces are selectable — clicking
                            // an opponent's piece never highlights it, just plays the
                            // illegal-move sound (no round-trip to the server needed).
                            String clickedColor = pieceColorAt(clicked);
                            if (clickedColor != null && !clickedColor.equalsIgnoreCase(assignedColor)) {
                                System.out.println("[Client] Cannot select opponent's piece at "
                                        + positionToAlgebraic(clicked));
                                if (soundManager != null) {
                                    soundManager.playIllegal();
                                }
                                return;
                            }
                            selectedSquare = clicked;
                            localSelectedSquare = clicked;  // Track for local highlight
                            System.out.println("[Client] Selected: " + positionToAlgebraic(selectedSquare));
                        } else if (clicked.equals(selectedSquare)) {
                            // Same-square second click: attempt a Dodge (piece is threatened)
                            String square = positionToAlgebraic(selectedSquare);
                            System.out.println("[Client] Same-square click, sending dodge: " + square);

                            if (dodgeCallback != null) {
                                dodgeCallback.accept(square);
                            } else {
                                System.err.println("[Client] ERROR: dodgeCallback is NULL!");
                            }

                            selectedSquare = null;
                            localSelectedSquare = null;
                        } else {
                            // Second click: send move
                            String from = positionToAlgebraic(selectedSquare);
                            String to = positionToAlgebraic(clicked);
                            System.out.println("[Client] Second click, sending move: " + from + " -> " + to);

                            if (moveCallback != null) {
                                moveCallback.accept(from, to);
                            } else {
                                System.err.println("[Client] ERROR: moveCallback is NULL!");
                            }

                            selectedSquare = null;
                            localSelectedSquare = null;  // Clear highlight after move
                        }
                    } catch (Exception ex) {
                        System.err.println("[Client] ERROR in click handler:");
                        ex.printStackTrace();
                    }
                }
            });
            
            System.out.println("[Client] Mouse listener successfully added to label");
        });
    }
    
    /**
    /**
     * Converts Position to algebraic notation (e.g., "e2").
     */
    private String positionToAlgebraic(Position pos) {
        char file = (char)('a' + pos.getCol());
        char rank = (char)('8' - pos.getRow()); // Row 0 = rank 8
        return "" + file + rank;
    }

    /** @return the color of the piece at {@code pos} in the most recent snapshot, or null if empty/unknown. */
    private String pieceColorAt(Position pos) {
        if (lastReceivedSnapshot == null) return null;
        for (GameEngine.GameSnapshot.PieceView pv : lastReceivedSnapshot.pieces()) {
            if (pv.position().equals(pos)) {
                return pv.color();
            }
        }
        return null;
    }
    
    /**
     * Converts BoardStateMessage to Board object.
     */
    private Board boardStateToBoard(BoardStateMessage msg) throws BoardParser.BoardParseException {
        String[][] grid = msg.getBoard();
        if (grid == null || grid.length == 0) {
            throw new BoardParser.BoardParseException("Empty board state");
        }
        
        // Convert 2D array to text format expected by BoardParser
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < grid.length; r++) {
            for (int c = 0; c < grid[r].length; c++) {
                if (c > 0) sb.append(" ");
                sb.append(grid[r][c]);
            }
            if (r < grid.length - 1) sb.append("\n");
        }
        
        return new BoardParser.TextParser().parse(sb.toString());
    }

    private void handleError(String message) {
        ErrorMessage msg = gson.fromJson(message, ErrorMessage.class);
        System.err.println("ERROR: " + msg.getReason());
        
        // Play illegal move sound for move rejections
        if (soundManager != null) {
            System.out.println("[Client] Move rejected, playing illegal sound");
            soundManager.playIllegal();
        }
    }

    private void handleAuthResult(String message) {
        AuthResultMessage msg = gson.fromJson(message, AuthResultMessage.class);
        if (msg.isSuccess()) {
            lastKnownElo = msg.getElo();
            System.out.println("[Client] Authentication successful! ELO: " + msg.getElo());
        } else {
            System.err.println("[Client] Authentication failed: " + msg.getReason());
        }
        if (onAuthResult != null) {
            onAuthResult.accept(msg.isSuccess(), msg.getReason());
        }
    }

    private void handleColor(String message) {
        ColorMessage msg = gson.fromJson(message, ColorMessage.class);
        System.out.println("[Client] Assigned color: " + msg.getColor());
        System.out.println("[Client] Received player names from server: WHITE=" + msg.getWhiteName() + ", BLACK=" + msg.getBlackName());
        assignedColor = msg.getColor();
        
        // Store player names (they may arrive before renderer exists - race condition)
        if (msg.getWhiteName() != null && msg.getBlackName() != null) {
            storedWhiteName = msg.getWhiteName();
            storedBlackName = msg.getBlackName();
            System.out.println("[Client] Stored player names for later application");

            // First time both real names are known, the opponent has joined.
            if (!opponentJoinedFired
                    && !"Waiting...".equals(storedWhiteName)
                    && !"Waiting...".equals(storedBlackName)) {
                opponentJoinedFired = true;
                if (onOpponentJoined != null) {
                    onOpponentJoined.run();
                }
            }

            // Update renderer if it exists now
            if (renderer != null) {
                renderer.setPlayerNames(msg.getWhiteName(), msg.getBlackName());
                System.out.println("[Client] Renderer exists, updated player names immediately: WHITE=" + msg.getWhiteName() + ", BLACK=" + msg.getBlackName());
            } else {
                System.out.println("[Client] Renderer does not exist yet, names will be applied when renderer is created");
            }
        } else {
            System.err.println("[DIAGNOSTIC] Player names NOT sent - whiteName or blackName was null");
        }
        
        if (onColorAssigned != null) {
            onColorAssigned.accept(msg.getColor());
        }
    }

    private void handleRoomInfo(String message) {
        RoomInfoMessage msg = gson.fromJson(message, RoomInfoMessage.class);
        System.out.println("[Client] Room info: room=" + msg.getRoomId() + " role=" + msg.getRole()
                + " started=" + msg.isStarted());

        currentRoomId = msg.getRoomId();
        assignedColor = msg.getRole();

        if (msg.getWhiteName() != null && msg.getBlackName() != null) {
            storedWhiteName = msg.getWhiteName();
            storedBlackName = msg.getBlackName();
            if (renderer != null) {
                renderer.setPlayerNames(msg.getWhiteName(), msg.getBlackName());
            }
        }

        if (onRoomInfo != null) {
            onRoomInfo.accept(msg);
        }
        // Reuses the existing color-assignment hook: known as soon as we're WHITE (creator),
        // BLACK/SPECTATOR (joiner), or matched — same latch ClientMain already waits on.
        if (onColorAssigned != null) {
            onColorAssigned.accept(msg.getRole());
        }
        if (msg.isStarted() && !opponentJoinedFired) {
            opponentJoinedFired = true;
            if (onOpponentJoined != null) {
                onOpponentJoined.run();
            }
        }
    }

    private void handleRoomError(String message) {
        RoomErrorMessage msg = gson.fromJson(message, RoomErrorMessage.class);
        System.err.println("[Client] Room error: " + msg.getReason());
        if (onRoomError != null) {
            onRoomError.accept(msg.getReason());
        }
    }

    private void handleServerFull(String message) {
        ServerFullMessage msg = gson.fromJson(message, ServerFullMessage.class);
        System.err.println("[Client] Server is full (max 2 players). Connection will be closed.");
    }

    private void handleNoMatchFound(String message) {
        NoMatchMessage msg = gson.fromJson(message, NoMatchMessage.class);
        System.err.println("[Client] No match found. You have been removed from the queue.");
        System.err.println("[Client] You can send another PLAY request to try again.");
        if (onNoMatch != null) {
            onNoMatch.run();
        }
    }

    private void handleOpponentDisconnected(String message) {
        DisconnectCountdownMessage msg = gson.fromJson(message, DisconnectCountdownMessage.class);
        System.out.println("[Client] WARNING: Opponent disconnected! They have " + msg.getSecondsLeft() + " seconds to reconnect or they forfeit.");
        if (onOpponentDisconnectedCountdown != null) {
            onOpponentDisconnectedCountdown.accept(msg.getSecondsLeft());
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("[Client] Connection closed: " + reason);
    }

    @Override
    public void onError(Exception ex) {
        System.err.println("[Client] Error: " + ex.getMessage());
    }

    /**
     * Sends a move to the server in square notation (e.g., from="e2", to="e4").
     *
     * @param from the source square (e.g., "e2")
     * @param to the destination square (e.g., "e4")
     */
    public void sendMove(String from, String to) {
        MoveMessage msg = new MoveMessage(from, to);
        String json = gson.toJson(msg);
        send(json);
        System.out.println("[Client] Sent move: " + from + " -> " + to);
        
        // Play move start sound immediately
        if (soundManager != null) {
            soundManager.playMoveStart();
        }
    }

    /**
     * Sends a Scream request to the server: an instant ranged capture of an adjacent
     * enemy piece, without the screaming piece moving.
     *
     * @param from the screaming piece's square (e.g., "e2")
     * @param to   the adjacent enemy square (e.g., "e3")
     */
    public void sendScream(String from, String to) {
        ScreamMessage msg = new ScreamMessage(from, to);
        String json = gson.toJson(msg);
        send(json);
        System.out.println("[Client] Sent scream: " + from + " -> " + to);

        // Play scream sound immediately
        if (soundManager != null) {
            soundManager.playScream();
        }
    }

    /**
     * Sends a Dodge request to the server: a same-square second click on a piece
     * that is currently threatened, attempting to counter-capture the attacker.
     *
     * @param square the piece's current square (e.g., "e2")
     */
    public void sendDodge(String square) {
        DodgeMessage msg = new DodgeMessage(square);
        String json = gson.toJson(msg);
        send(json);
        System.out.println("[Client] Sent dodge: " + square);

        // Unlike move/scream, dodge is NOT played optimistically — it's only valid
        // while the piece is actually threatened, so a wrong guess is common and
        // should sound like a rejection (ERROR -> playIllegal), not a real dodge.
        // The real dodge sound plays once the server confirms it (see handleBoardState,
        // which detects a newly-appeared dodge motion).
    }

    /**
     * Sends a login message to the server with the player's username and password.
     *
     * <p>This should be called immediately after connection is established,
     * before sending any moves.</p>
     *
     * @param username the player's chosen username
     * @param password the player's password
     */
    public void sendLogin(String username, String password) {
        LoginMessage msg = new LoginMessage(username, password);
        String json = gson.toJson(msg);
        send(json);
        System.out.println("[Client] Sent login with username: " + username);
    }

    /**
     * Sends a play request to join the matchmaking queue.
     *
     * <p>This should be called after successful authentication to request a game.
     * The server will pair you with another player of similar ELO rating.</p>
     */
    public void sendPlayRequest() {
        PlayRequestMessage msg = new PlayRequestMessage();
        String json = gson.toJson(msg);
        send(json);
        System.out.println("[Client] Sent play request. Waiting for match...");
    }
    
    /** Sends a request to create a new room. Server replies with ROOM_INFO (role WHITE + code). */
    public void sendRoomCreate() {
        send(gson.toJson(new RoomCreateMessage()));
        System.out.println("[Client] Sent room create request");
    }

    /** Sends a request to join an existing room by its code. */
    public void sendRoomJoin(String roomId) {
        send(gson.toJson(new RoomJoinMessage(roomId)));
        System.out.println("[Client] Sent room join request: " + roomId);
    }

    /** @return the current room's code, or null if not in a room yet. */
    public String getCurrentRoomId() {
        return currentRoomId;
    }

    /**
     * Starts a repeating timer for client-side cooldown animation.
     * Re-renders every 100ms with interpolated clock value for smooth real-time updates.
     */
    private void startCooldownAnimationTimer() {
        cooldownAnimationTimer = new javax.swing.Timer(100, e -> {
            if (lastReceivedSnapshot != null && renderer != null) {
                // Calculate interpolated clock: server's clock + elapsed wall-clock time
                long wallClockElapsed = System.currentTimeMillis() - lastSnapshotReceivedAt;
                long interpolatedClock = lastReceivedSnapshot.clock() + wallClockElapsed;
                
                // Create a new snapshot with the same data but interpolated clock
                GameEngine.GameSnapshot interpolatedSnapshot = new GameEngine.GameSnapshot(
                    lastReceivedSnapshot.pieces(),
                    lastReceivedSnapshot.boardWidth(),
                    lastReceivedSnapshot.boardHeight(),
                    localSelectedSquare,  // Use current selection
                    lastReceivedSnapshot.gameOver(),
                    lastReceivedSnapshot.turn(),
                    lastReceivedSnapshot.activeMotions(),
                    interpolatedClock,  // Interpolated clock for smooth cooldown animation
                    lastReceivedSnapshot.scoreWhite(),
                    lastReceivedSnapshot.scoreBlack(),
                    lastReceivedSnapshot.moveLog(),
                    lastReceivedSnapshot.rejectedDest(),
                    lastReceivedSnapshot.winner()
                );
                
                // Re-render with interpolated snapshot (timer fires on EDT automatically)
                renderer.render(interpolatedSnapshot);
            }
        });
        cooldownAnimationTimer.start();
    }
}
