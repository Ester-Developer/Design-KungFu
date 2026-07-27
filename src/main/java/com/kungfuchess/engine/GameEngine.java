package com.kungfuchess.engine;

import com.kungfuchess.input.BoardMapper;
import com.kungfuchess.io.BoardParser;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import com.kungfuchess.realtime.RealTimeArbiter;
import com.kungfuchess.rules.PieceValues;
import com.kungfuchess.rules.RuleEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Service-layer container for the chess application.
 *
 * <p>Holds the board instance and provides methods for commands to interact with
 * the model. Acts as the central dependency that all commands receive.</p>
 *
 * <p>Also manages selection state for interactive gameplay:
 * - Tracks the currently selected piece
 * - Provides coordinate conversion (pixels to board coordinates)
 * - Handles piece selection and deselection logic</p>
 *
 * <p>Score tracking: each capture increments the capturing player's running score
 * by the captured piece's material value (see {@link PieceValues}). Scores are
 * exposed through {@link GameSnapshot} as plain ints.</p>
 *
 * <p>Move log: every arrival event produces a structured {@link GameSnapshot.MoveLogEntry}
 * record. The full log is exposed through {@link GameSnapshot} as an immutable list.
 * Formatting to display text belongs in the renderer, not here.</p>
 */
public class GameEngine {

    private Board board;
    private final RealTimeArbiter arbiter;
    private boolean gameOver;
    private String turn = "white";
    private com.kungfuchess.input.Controller controller;
    private static final RuleEngine RULE_ENGINE = new RuleEngine();

    // Score per color (material points accumulated from captures)
    private int scoreWhite = 0;
    private int scoreBlack = 0;

    // Winner color — set when a King is captured; null until game over
    private String winner = null;

    // Last rejected destination — exposed in snapshot for visual warning.
    // Cleared automatically once REJECTED_FLASH_MS of game clock has elapsed.
    private Position lastRejectedDest = null;
    private long     rejectedAtClock  = -1L;
    private static final long REJECTED_FLASH_MS = 500L;

    // Structured move log — appended in waitMs, exposed immutably via snapshot
    private final List<GameSnapshot.MoveLogEntry> moveLog = new ArrayList<>();

    /**
     * Initializes the engine with an empty standard 8×8 board.
     */
    public GameEngine() {
        this.board    = Board.createStandard();
        this.arbiter  = new RealTimeArbiter();
        this.gameOver = false;
    }

    /**
     * @return the {@link com.kungfuchess.input.Controller} for this engine, created on
     * first use.
     */
    public com.kungfuchess.input.Controller getController() {
        if (controller == null) {
            controller = new com.kungfuchess.input.Controller(this);
        }
        return controller;
    }

    // -------------------------------------------------------------------------
    // Real-time arbitration / game-over state
    // -------------------------------------------------------------------------

    /**
     * @return the arbiter tracking in-flight motions, jump protections, and which
     * pieces have already made their first move.
     */
    public RealTimeArbiter getArbiter() { return arbiter; }

    /** @return {@code true} once a King has been captured. */
    public boolean isGameOver() { return gameOver; }

    /** Marks the game as over (e.g. a King was just captured). Irreversible in practice. */
    public void setGameOver(boolean gameOver) { this.gameOver = gameOver; }

    /**
     * Attempts to start a move from {@code from} to {@code to}.
     *
     * <p>Check order:
     * <ol>
     *   <li>(a) {@code game_over} — reject with {@code "game_over"}.</li>
     *   <li>(b) Per-piece cooldown — if the piece at {@code from} is still within its
     *       travel+rest window, reject with {@code "piece_on_cooldown"}. Different pieces
     *       are independent: this is the core Kung-Fu Chess concurrent-movement mechanic.</li>
     *   <li>(c) {@link RuleEngine#validateMove} — shape, path, occupancy rules.</li>
     *   <li>(d) If valid, start the motion via {@link RealTimeArbiter#startMotion}.</li>
     * </ol>
     *
     * @param from source position
     * @param to   destination position
     * @return the outcome, with a stable machine-readable reason
     * @throws Board.OutOfBoundsException if either position is out of bounds
     */
    public MoveResult requestMove(Position from, Position to) throws Board.OutOfBoundsException {
        // (a) game_over
        if (gameOver) {
            lastRejectedDest = to;
            rejectedAtClock  = arbiter.getClock();
            return MoveResult.rejected(MoveResult.GAME_OVER);
        }

        // (b) per-piece cooldown — only blocks the specific piece being moved
        Optional<Piece> movingOpt = board.pieceAt(from);
        if (movingOpt.isPresent() && arbiter.isOnCooldown(movingOpt.get())) {
            lastRejectedDest = to;
            rejectedAtClock  = arbiter.getClock();
            return MoveResult.rejected(MoveResult.PIECE_ON_COOLDOWN);
        }

        // (c) rule validation — pass pawnHasMoved so double-step is gated correctly
        boolean pawnHasMoved = movingOpt.isPresent() && arbiter.hasMoved(movingOpt.get());
        RuleEngine.MoveValidation validation = RULE_ENGINE.validateMove(board, from, to, pawnHasMoved);
        if (!validation.isValid()) {
            lastRejectedDest = to;
            rejectedAtClock  = arbiter.getClock();
            return MoveResult.rejected(validation.reason());
        }

        // (d) destination reservation — reject if another motion is already targeting this square
        if (arbiter.isDestinationReserved(to)) {
            lastRejectedDest = to;
            rejectedAtClock  = arbiter.getClock();
            return MoveResult.rejected(MoveResult.DESTINATION_RESERVED);
        }

        // (e) capture-lock: if an enemy motion is already targeting the piece's current square,
        //     that piece may only dodge — it cannot flee to a different square.
        if (movingOpt.isPresent() && !to.equals(from)) {
            Piece moving = movingOpt.get();
            for (com.kungfuchess.realtime.Motion m : arbiter.getPendingMotions()) {
                if (m.getTo().equals(from) && !m.getPiece().getColor().equals(moving.getColor())) {
                    lastRejectedDest = to;
                    rejectedAtClock  = arbiter.getClock();
                    return MoveResult.rejected(MoveResult.CAPTURE_LOCKED);
                }
            }
        }

        // (f) start motion
        lastRejectedDest = null;
        rejectedAtClock  = -1L;
        arbiter.startMotion(movingOpt.get(), from, to);
        turn = turn.equals("white") ? "black" : "white";
        return MoveResult.ok();
    }

    /**
     * Attempts to register a Dodge for the piece at {@code square} (same-square
     * second click). Eligibility:
     * <ol>
     *   <li>Game must not be over.</li>
     *   <li>Piece must not be on cooldown.</li>
     *   <li>At least one enemy motion must currently target {@code square}; if multiple,
     *       the one with the earliest dueTime is used.</li>
     * </ol>
     *
     * @param square the piece's current position (from == to)
     * @return the outcome with a stable machine-readable reason
     * @throws Board.OutOfBoundsException if the position is out of bounds
     */
    public MoveResult requestDodge(Position square) throws Board.OutOfBoundsException {
        if (gameOver) return MoveResult.rejected(MoveResult.GAME_OVER);

        Optional<Piece> pieceOpt = board.pieceAt(square);
        if (pieceOpt.isEmpty()) return MoveResult.rejected("no_piece");
        Piece piece = pieceOpt.get();

        if (arbiter.isOnCooldown(piece)) {
            lastRejectedDest = square;
            rejectedAtClock  = arbiter.getClock();
            return MoveResult.rejected(MoveResult.PIECE_ON_COOLDOWN);
        }

        // Find the earliest-dueTime enemy motion targeting this square
        com.kungfuchess.realtime.Motion threat = null;
        for (com.kungfuchess.realtime.Motion m : arbiter.getPendingMotions()) {
            if (!m.getTo().equals(square)) continue;
            if (m.getPiece().getColor().equals(piece.getColor())) continue;
            if (threat == null || m.getDueTime() < threat.getDueTime()) threat = m;
        }
        if (threat == null) {
            lastRejectedDest = square;
            rejectedAtClock  = arbiter.getClock();
            return MoveResult.rejected(MoveResult.NO_ACTIVE_THREAT);
        }

        lastRejectedDest = null;
        rejectedAtClock  = -1L;
        arbiter.startDodge(piece, square, threat);
        turn = turn.equals("white") ? "black" : "white";
        return MoveResult.ok();
    }

    /**
     * Advances simulated time by {@code ms}, resolves any motions that have now
     * arrived, updates scores and the move log for each arrival, and returns the
     * resulting {@link RealTimeArbiter.ArrivalEvents}.
     *
     * <p>Named {@code waitMs} rather than {@code wait} — Java forbids overriding
     * {@link Object#wait(long)}, which is {@code final}.</p>
     *
     * @param ms milliseconds to advance
     * @return every arrival that occurred during this advance (possibly empty)
     */
    public RealTimeArbiter.ArrivalEvents waitMs(long ms) {
        RealTimeArbiter.ArrivalEvents events = arbiter.advanceTime(ms, board);
        for (RealTimeArbiter.ArrivalEvents.ArrivalEvent event : events.arrivals()) {
            Piece captured = event.capturedPiece();

            // Update game-over flag
            if (captured != null && "King".equals(captured.getKind())) {
                gameOver = true;
                winner   = event.piece().getColor(); // the capturer wins
            }

            // Update score: the capturing player is the mover (event.piece().getColor())
            if (captured != null) {
                int points = PieceValues.valueOf(captured.getKind());
                if ("white".equals(event.piece().getColor())) {
                    scoreWhite += points;
                } else {
                    scoreBlack += points;
                }
            }

            // Append structured move log entry
            moveLog.add(buildLogEntry(event));
        }
        return events;
    }

    /** Builds a structured log entry from an arrival event. */
    private GameSnapshot.MoveLogEntry buildLogEntry(RealTimeArbiter.ArrivalEvents.ArrivalEvent event) {
        long clockMs = arbiter.getClock();
        long totalSec = clockMs / 1000;
        String timestamp = String.format("%d:%02d", totalSec / 60, totalSec % 60);
        return new GameSnapshot.MoveLogEntry(
            timestamp,
            event.piece().getColor(),
            event.piece().getKind(),
            event.source(),
            event.destination(),
            event.capturedPiece() != null ? event.capturedPiece().getKind() : null,
            event.isJump(),
            event.isPromoted(),
            event.isDodge(),
            event.isScream()
        );
    }

    /**
     * Attempts a Scream: an instant ranged capture of an adjacent enemy piece, without
     * the screaming piece moving. The screaming piece must not be on cooldown.
     *
     * <p>Eligible targets are enemy pieces in the 8 adjacent squares (king-distance 1).
     * If the target square has no enemy, the request is rejected.</p>
     *
     * @param screamerPos the screaming piece's position
     * @param targetPos   the target square (must be adjacent and have an enemy)
     * @return the outcome with a stable machine-readable reason
     * @throws Board.OutOfBoundsException if either position is out of bounds
     */
    public MoveResult requestScream(Position screamerPos, Position targetPos)
            throws Board.OutOfBoundsException {
        if (gameOver) return MoveResult.rejected(MoveResult.GAME_OVER);

        Optional<Piece> screamerOpt = board.pieceAt(screamerPos);
        if (screamerOpt.isEmpty()) return MoveResult.rejected("no_piece");
        Piece screamer = screamerOpt.get();

        if (arbiter.isOnCooldown(screamer)) {
            lastRejectedDest = targetPos;
            rejectedAtClock  = arbiter.getClock();
            return MoveResult.rejected(MoveResult.PIECE_ON_COOLDOWN);
        }

        // Target must be adjacent (Chebyshev distance == 1)
        int dr = Math.abs(targetPos.getRow() - screamerPos.getRow());
        int dc = Math.abs(targetPos.getCol() - screamerPos.getCol());
        if (dr > 1 || dc > 1 || (dr == 0 && dc == 0)) {
            lastRejectedDest = targetPos;
            rejectedAtClock  = arbiter.getClock();
            return MoveResult.rejected("not_adjacent");
        }

        // Target must have an enemy piece
        Optional<Piece> targetOpt = board.pieceAt(targetPos);
        if (targetOpt.isEmpty()) {
            lastRejectedDest = targetPos;
            rejectedAtClock  = arbiter.getClock();
            return MoveResult.rejected("empty_target");
        }
        if (targetOpt.get().getColor().equals(screamer.getColor())) {
            lastRejectedDest = targetPos;
            rejectedAtClock  = arbiter.getClock();
            return MoveResult.rejected("friendly_target");
        }

        lastRejectedDest = null;
        rejectedAtClock  = -1L;

        RealTimeArbiter.ArrivalEvents events = arbiter.startScream(screamer, screamerPos, targetPos, board);
        for (RealTimeArbiter.ArrivalEvents.ArrivalEvent event : events.arrivals()) {
            Piece captured = event.capturedPiece();
            if (captured != null && "King".equals(captured.getKind())) {
                gameOver = true;
            }
            if (captured != null) {
                int points = PieceValues.valueOf(captured.getKind());
                if ("white".equals(event.piece().getColor())) {
                    scoreWhite += points;
                } else {
                    scoreBlack += points;
                }
            }
            moveLog.add(buildLogEntry(event));
        }
        turn = turn.equals("white") ? "black" : "white";
        return MoveResult.ok();
    }

    /**
     * @return a read-only snapshot for the renderer: immutable per-piece view records
     * (assembled fresh from the live board), the selected cell, and whether the game is
     * over. The live {@link Board} is never exposed.
     */
    public GameSnapshot snapshot() {
        // Auto-expire the rejection flash after REJECTED_FLASH_MS of game clock
        if (lastRejectedDest != null
                && arbiter.getClock() - rejectedAtClock >= REJECTED_FLASH_MS) {
            lastRejectedDest = null;
            rejectedAtClock  = -1L;
        }
        List<GameSnapshot.PieceView> pieces = buildPieceViews();
        return new GameSnapshot(pieces, board.getWidth(), board.getHeight(),
                                getSelectedPosition().orElse(null), gameOver, turn,
                                arbiter.getPendingMotions(), arbiter.getClock(),
                                scoreWhite, scoreBlack, moveLog, lastRejectedDest, winner);
    }

    /**
     * Assembles immutable {@link GameSnapshot.PieceView} records from the live board.
     * Cooldown data is read from the arbiter and embedded as a plain {@code long} in
     * each record — no live Piece reference is exposed.
     */
    private List<GameSnapshot.PieceView> buildPieceViews() {
        List<GameSnapshot.PieceView> views = new ArrayList<>();
        for (int row = 0; row < board.getHeight(); row++) {
            for (int col = 0; col < board.getWidth(); col++) {
                Position pos = new Position(row, col);
                try {
                    board.pieceAt(pos).ifPresent(p -> {
                        long restUntil = arbiter.cooldownUntilMs(p);
                        long restStart = arbiter.cooldownStartMs(p);
                        views.add(new GameSnapshot.PieceView(
                            p.getKind(), p.getColor(), pos, restUntil, restStart));
                    });
                } catch (Board.OutOfBoundsException ignored) {}
            }
        }
        return views;
    }

    // -------------------------------------------------------------------------
    // Board Management
    // -------------------------------------------------------------------------

    /**
     * Loads a board from a string configuration using the provided parser.
     *
     * @param config the raw board representation
     * @param parser the parser strategy to use
     * @return this engine (for method chaining)
     * @throws BoardParser.BoardParseException if the configuration cannot be parsed
     */
    public GameEngine loadBoard(String config, BoardParser<String> parser)
            throws BoardParser.BoardParseException {
        this.board = parser.parse(config);
        return this;
    }

    /** @return the current board instance. */
    public Board getBoard() { return board; }

    /**
     * Replaces the board with a new instance.
     *
     * @param board the new board
     * @return this engine (for method chaining)
     */
    public GameEngine setBoard(Board board) {
        this.board = board;
        return this;
    }

    // -------------------------------------------------------------------------
    // Selection State Management
    // -------------------------------------------------------------------------

    /**
     * Returns the currently selected piece's position, or empty if nothing is selected.
     *
     * @return the selected position
     */
    public Optional<Position> getSelectedPosition() {
        return getController().getSelected();
    }

    /** @return {@code true} if a piece is currently selected. */
    public boolean hasSelection() {
        return getSelectedPosition().isPresent();
    }

    // -------------------------------------------------------------------------
    // Coordinate Conversion
    // -------------------------------------------------------------------------

    /**
     * Converts pixel coordinates to board coordinates.
     *
     * @param pixelX the x-coordinate in pixels
     * @param pixelY the y-coordinate in pixels
     * @return the board position
     */
    public Position pixelToBoard(int pixelX, int pixelY) {
        return BoardMapper.pixelToBoard(pixelX, pixelY);
    }

    /**
     * Converts board coordinates to pixel coordinates (top-left corner of cell).
     *
     * @param position the board position
     * @return the pixel coordinates as [x, y]
     */
    public int[] boardToPixel(Position position) {
        return BoardMapper.boardToPixel(position);
    }

    /**
     * Checks if pixel coordinates are within the board boundaries.
     *
     * @param pixelX the x-coordinate in pixels
     * @param pixelY the y-coordinate in pixels
     * @return {@code true} if the coordinates are within the board
     */
    public boolean isPixelInBounds(int pixelX, int pixelY) {
        return BoardMapper.isPixelInBounds(board, pixelX, pixelY);
    }

    // -------------------------------------------------------------------------
    // Game Clock
    // -------------------------------------------------------------------------

    /**
     * @return the current simulated game clock in milliseconds.
     */
    public long getGameClock() { return arbiter.getClock(); }

    // -------------------------------------------------------------------------
    // Nested types
    // -------------------------------------------------------------------------

    /**
     * Result of {@link #requestMove}: whether the move request was accepted, and a
     * stable, machine-readable reason.
     *
     * <p>Accepted moves carry reason {@code "ok"}. Rejections carry one of:
     * {@code "game_over"}, {@code "piece_on_cooldown"}, or a rule-level reason from
     * {@link RuleEngine.MoveValidation}.</p>
     */
    public static final class MoveResult {

        public static final String OK               = "ok";
        public static final String GAME_OVER        = "game_over";
        public static final String PIECE_ON_COOLDOWN = "piece_on_cooldown";
        /**
         * Retained as a named constant for tests/callers that reference it by name,
         * but {@link GameEngine#requestMove} no longer produces this reason — the gate
         * is per-piece ({@link #PIECE_ON_COOLDOWN}), not global.
         */
        public static final String MOTION_IN_PROGRESS = "motion_in_progress";
        /** Destination square is already targeted by an active in-flight motion. */
        public static final String DESTINATION_RESERVED = "destination_reserved";
        /** Source square is the FROM of an active in-flight motion (piece already departing). */
        public static final String SOURCE_RESERVED = "source_reserved";
        /** Dodge rejected: no enemy motion currently targets this piece's square. */
        public static final String NO_ACTIVE_THREAT = "no_active_threat";
        /** Piece is targeted by an incoming enemy motion and may only dodge, not flee. */
        public static final String CAPTURE_LOCKED = "capture_locked";

        private final boolean accepted;
        private final String  reason;

        public MoveResult(boolean accepted, String reason) {
            this.accepted = accepted;
            this.reason   = reason;
        }

        public boolean isAccepted() { return accepted; }
        public String  reason()     { return reason; }

        public static MoveResult ok()                    { return new MoveResult(true,  OK); }
        public static MoveResult rejected(String reason) { return new MoveResult(false, reason); }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MoveResult)) return false;
            MoveResult other = (MoveResult) o;
            return accepted == other.accepted && java.util.Objects.equals(reason, other.reason);
        }

        @Override public int hashCode() { return java.util.Objects.hash(accepted, reason); }

        @Override
        public String toString() {
            return "MoveResult(accepted=" + accepted + ", reason=" + reason + ")";
        }
    }

    /**
     * Read-only view model handed to the renderer.
     *
     * <p>The renderer receives only immutable data records — never live {@link Board}
     * or {@link Piece} objects — to prevent accidental mutation from the view layer.</p>
     */
    public static final class GameSnapshot {

        /**
         * Immutable per-piece view record. Contains only value data — no live domain
         * object references.
         *
         * <p>{@link #restUntilMs()} carries the absolute game-clock time (ms) at which
         * this piece's cooldown/rest window ends, or {@code 0} if the piece is not
         * currently resting. This lets the renderer show a rest-window overlay without
         * needing a live Piece reference or a separate {@code Map<Piece, Long>}.</p>
         */
        public static final class PieceView {
            private final String   kind;
            private final String   color;
            private final Position position;
            private final long     restUntilMs;
            private final long     restStartMs;

            public PieceView(String kind, String color, Position position,
                             long restUntilMs, long restStartMs) {
                this.kind        = kind;
                this.color       = color;
                this.position    = position;
                this.restUntilMs = restUntilMs;
                this.restStartMs = restStartMs;
            }

            /** Backward-compatible constructor (restUntilMs and restStartMs default to 0). */
            public PieceView(String kind, String color, Position position, long restUntilMs) {
                this(kind, color, position, restUntilMs, 0L);
            }

            /** Backward-compatible constructor (all timing defaults to 0). */
            public PieceView(String kind, String color, Position position) {
                this(kind, color, position, 0L, 0L);
            }

            public String   kind()        { return kind; }
            public String   color()       { return color; }
            public Position position()    { return position; }
            /**
             * @return absolute game-clock ms when this piece's cooldown ends, or
             *         {@code 0} if the piece is not on cooldown.
             */
            public long     restUntilMs() { return restUntilMs; }
            /**
             * @return absolute game-clock ms when this piece's current cooldown window
             *         began, or {@code 0} if the piece is not on cooldown.
             *         Together with {@link #restUntilMs()} allows computing a progress
             *         fraction: {@code (restUntilMs - clock) / (restUntilMs - restStartMs)}.
             */
            public long     restStartMs() { return restStartMs; }

            @Override
            public String toString() {
                return "PieceView(" + kind + "," + color + "," + position
                    + ",restUntil=" + restUntilMs + ",restStart=" + restStartMs + ")";
            }
        }

        /**
         * Immutable record for one move-log entry.
         *
         * <p>Raw structured data — formatting to display text belongs in the renderer
         * ({@link com.kungfuchess.view.ImageView}), not here.</p>
         *
         * <p>{@link #capturedKind()} is {@code null} for non-capturing moves.</p>
         */
        public static final class MoveLogEntry {
            private final String   timestamp;    // "mm:ss" elapsed from game start
            private final String   color;        // "white" or "black" — the mover
            private final String   pieceKind;
            private final Position from;
            private final Position to;
            private final String   capturedKind; // null if no capture
            private final boolean  jump;         // true if the motion was a jump
            private final boolean  promoted;     // true if a pawn promotion occurred
            private final boolean  dodge;        // true if this was a dodge motion
            private final boolean  scream;       // true if this was a scream move

            public MoveLogEntry(String timestamp, String color, String pieceKind,
                                Position from, Position to, String capturedKind,
                                boolean jump, boolean promoted, boolean dodge, boolean scream) {
                this.timestamp    = timestamp;
                this.color        = color;
                this.pieceKind    = pieceKind;
                this.from         = from;
                this.to           = to;
                this.capturedKind = capturedKind;
                this.jump         = jump;
                this.promoted     = promoted;
                this.dodge        = dodge;
                this.scream       = scream;
            }

            /** Backward-compatible constructor (scream defaults to false). */
            public MoveLogEntry(String timestamp, String color, String pieceKind,
                                Position from, Position to, String capturedKind,
                                boolean jump, boolean promoted, boolean dodge) {
                this(timestamp, color, pieceKind, from, to, capturedKind, jump, promoted, dodge, false);
            }

            /** Backward-compatible constructor (promoted and dodge default to false). */
            public MoveLogEntry(String timestamp, String color, String pieceKind,
                                Position from, Position to, String capturedKind,
                                boolean jump) {
                this(timestamp, color, pieceKind, from, to, capturedKind, jump, false, false, false);
            }

            /** @return "mm:ss" elapsed game time when this move landed. */
            public String   timestamp()    { return timestamp; }
            /** @return {@code "white"} or {@code "black"} — the moving player. */
            public String   color()        { return color; }
            public String   pieceKind()    { return pieceKind; }
            public Position from()         { return from; }
            public Position to()           { return to; }
            /** @return the kind of the captured piece, or {@code null} if no capture. */
            public String   capturedKind() { return capturedKind; }
            public boolean  isCapture()    { return capturedKind != null; }
            /** @return {@code true} if the motion that produced this entry was a jump. */
            public boolean  isJump()       { return jump; }
            /** @return {@code true} if this arrival triggered a pawn promotion. */
            public boolean  isPromoted()   { return promoted; }
            /** @return {@code true} if this was a Dodge motion (counter-capture). */
            public boolean  isDodge()      { return dodge; }
            /** @return {@code true} if this was a Scream move (ranged instant capture, no movement). */
            public boolean  isScream()     { return scream; }

            @Override
            public String toString() {
                return "MoveLogEntry(" + timestamp + " " + color + " " + pieceKind
                    + " " + from + "->" + to
                    + (capturedKind != null ? " x" + capturedKind : "")
                    + (jump ? " jump" : "")
                    + (promoted ? " promoted" : "")
                    + (dodge ? " dodge" : "")
                    + (scream ? " scream" : "") + ")";
            }
        }

        private final List<PieceView>    pieces;
        private final int                boardWidth;
        private final int                boardHeight;
        private final Position           selectedCell;
        private final boolean            gameOver;
        private final String             turn;
        private final List<com.kungfuchess.realtime.Motion> activeMotions;
        private final long               clock;
        private final int                scoreWhite;
        private final int                scoreBlack;
        private final List<MoveLogEntry> moveLog;
        private final Position           rejectedDest; // null if no recent rejection
        private final String             winner;       // null until game over; "white" or "black"

        public GameSnapshot(List<PieceView> pieces, int boardWidth, int boardHeight,
                            Position selectedCell, boolean gameOver, String turn,
                            List<com.kungfuchess.realtime.Motion> activeMotions,
                            long clock, int scoreWhite, int scoreBlack,
                            List<MoveLogEntry> moveLog, Position rejectedDest, String winner) {
            this.pieces        = Collections.unmodifiableList(new ArrayList<>(pieces));
            this.boardWidth    = boardWidth;
            this.boardHeight   = boardHeight;
            this.selectedCell  = selectedCell;
            this.gameOver      = gameOver;
            this.turn          = turn;
            this.activeMotions = Collections.unmodifiableList(new ArrayList<>(activeMotions));
            this.clock         = clock;
            this.scoreWhite    = scoreWhite;
            this.scoreBlack    = scoreBlack;
            this.moveLog       = Collections.unmodifiableList(new ArrayList<>(moveLog));
            this.rejectedDest  = rejectedDest;
            this.winner        = winner;
        }

        /** Backward-compatible constructor (winner defaults to null). */
        public GameSnapshot(List<PieceView> pieces, int boardWidth, int boardHeight,
                            Position selectedCell, boolean gameOver, String turn,
                            List<com.kungfuchess.realtime.Motion> activeMotions,
                            long clock, int scoreWhite, int scoreBlack,
                            List<MoveLogEntry> moveLog, Position rejectedDest) {
            this(pieces, boardWidth, boardHeight, selectedCell, gameOver, turn,
                 activeMotions, clock, scoreWhite, scoreBlack, moveLog, rejectedDest, null);
        }

        /** Backward-compatible constructor (rejectedDest defaults to null). */
        public GameSnapshot(List<PieceView> pieces, int boardWidth, int boardHeight,
                            Position selectedCell, boolean gameOver, String turn,
                            List<com.kungfuchess.realtime.Motion> activeMotions,
                            long clock, int scoreWhite, int scoreBlack,
                            List<MoveLogEntry> moveLog) {
            this(pieces, boardWidth, boardHeight, selectedCell, gameOver, turn,
                 activeMotions, clock, scoreWhite, scoreBlack, moveLog, null);
        }

        public int      boardWidth()   { return boardWidth; }
        public int      boardHeight()  { return boardHeight; }

        /** @return immutable list of per-piece view records (assembled at snapshot time). */
        public List<PieceView> pieces() { return pieces; }
        public Position selectedCell()  { return selectedCell; }
        public boolean  gameOver()      { return gameOver; }

        /** @return {@code "white"} or {@code "black"} — whose turn it is to move. */
        public String   turn()          { return turn; }

        /** @return motions currently in flight (read-only snapshot). */
        public List<com.kungfuchess.realtime.Motion> activeMotions() { return activeMotions; }

        /** @return the simulation clock (ms) at the moment this snapshot was taken. */
        public long     clock()         { return clock; }

        /** @return white's accumulated material score (sum of captured piece values). */
        public int      scoreWhite()    { return scoreWhite; }

        /** @return black's accumulated material score (sum of captured piece values). */
        public int      scoreBlack()    { return scoreBlack; }

        /**
         * @return immutable list of all move-log entries since game start, in
         *         chronological order. Each entry is a structured record; the renderer
         *         is responsible for formatting it as display text.
         */
        public List<MoveLogEntry> moveLog() { return moveLog; }

        /**
         * @return the destination cell of the most recently rejected move request, or
         *         {@code null} if the last move was accepted (or no move has been made).
         *         Used by the renderer to flash a warning on the attempted destination
         *         square for one render frame.
         */
        public Position rejectedDest() { return rejectedDest; }

        /**
         * @return the color that won ({@code "white"} or {@code "black"}), or
         *         {@code null} if the game is not yet over.
         */
        public String   winner()        { return winner; }

        @Override
        public String toString() {
            return "GameSnapshot(width=" + boardWidth + ", height=" + boardHeight
                + ", selectedCell=" + selectedCell + ", gameOver=" + gameOver
                + ", turn=" + turn + ", scoreWhite=" + scoreWhite
                + ", scoreBlack=" + scoreBlack + ")";
        }
    }
}
