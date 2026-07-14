package com.kungfuchess.engine;
import com.kungfuchess.model.Board;

import com.kungfuchess.io.BoardParser;
import com.kungfuchess.input.BoardMapper;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import com.kungfuchess.realtime.RealTimeArbiter;
import com.kungfuchess.rules.RuleEngine;

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
 */
public class GameEngine {

    private Board board;
    private final RealTimeArbiter arbiter;
    private boolean gameOver;
    private com.kungfuchess.input.Controller controller;
    private static final RuleEngine RULE_ENGINE = new RuleEngine();

    /**
     * Initializes the engine with an empty standard 8×8 board.
     */
    public GameEngine() {
        this.board = Board.createStandard();
        this.arbiter = new RealTimeArbiter();
        this.gameOver = false;
    }

    /**
     * @return the {@link com.kungfuchess.input.Controller} for this engine, created on
     * first use. The Controller — not this class — owns selected-cell state; this
     * engine only asks it for the current selection when building a {@link
     * #snapshot()}.
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
    public RealTimeArbiter getArbiter() {
        return arbiter;
    }

    /** @return {@code true} once a King has been captured. */
    public boolean isGameOver() {
        return gameOver;
    }

    /** Marks the game as over (e.g. a King was just captured). Irreversible in practice. */
    public void setGameOver(boolean gameOver) {
        this.gameOver = gameOver;
    }

    /**
     * Attempts to start a move from {@code from} to {@code to}.
     *
     * <p>This is the single authority for "is starting this move allowed right now?":
     * <ol>
     *   <li>If the game is already over, reject with reason {@code "game_over"} —
     *       {@link RuleEngine} is never even consulted.</li>
     *   <li>If the common route already has an active motion (any color — see
     *       {@link RealTimeArbiter#hasActiveMotion()}), reject with reason
     *       {@code "motion_in_progress"}.</li>
     *   <li>Otherwise, delegate to {@link RuleEngine#validateMove}. If invalid, its
     *       reason is copied straight into the returned {@link MoveResult}.</li>
     *   <li>If valid, start the motion via {@link RealTimeArbiter#startMotion}. The
     *       board does not change immediately — only once the motion arrives, via
     *       {@link #wait}.</li>
     * </ol>
     *
     * <p>Callers such as {@code Controller} should only ever translate input into a
     * {@code from}/{@code to} request and call this method; they must not decide
     * legality or touch the {@link RealTimeArbiter} themselves.</p>
     *
     * @param from source position
     * @param to   destination position
     * @return the outcome, with a stable machine-readable reason
     * @throws Board.OutOfBoundsException if either position is out of bounds
     */
    public MoveResult requestMove(Position from, Position to) throws Board.OutOfBoundsException {
        if (gameOver) {
            return MoveResult.rejected(MoveResult.GAME_OVER);
        }

        if (arbiter.hasActiveMotion()) {
            return MoveResult.rejected(MoveResult.MOTION_IN_PROGRESS);
        }

        Optional<Piece> movingOpt = board.pieceAt(from);
        boolean pawnHasMoved = movingOpt.isPresent() && arbiter.hasMoved(movingOpt.get());

        RuleEngine.MoveValidation validation = RULE_ENGINE.validateMove(board, from, to, pawnHasMoved);
        if (!validation.isValid()) {
            return MoveResult.rejected(validation.reason());
        }

        arbiter.startMotion(movingOpt.get(), from, to);
        return MoveResult.ok();
    }

    /**
     * Advances simulated time by {@code ms} and resolves any motions that have now
     * arrived. Delegates entirely to {@link RealTimeArbiter#advanceTime}; this method
     * must not directly manipulate board or motion state itself.
     *
     * <p>Named {@code waitMs} rather than {@code wait} — the design spec calls this
     * {@code GameEngine.wait(ms)}, but Java forbids overriding {@link Object#wait(long)},
     * which is {@code final}.</p>
     *
     * <p>If a King was captured on arrival, {@code game_over} is set.</p>
     *
     * @param ms milliseconds to advance
     */
    public void waitMs(long ms) {
        RealTimeArbiter.ArrivalEvents events = arbiter.advanceTime(ms, board);
        for (RealTimeArbiter.ArrivalEvents.ArrivalEvent event : events.arrivals()) {
            Piece captured = event.capturedPiece();
            if (captured != null && "King".equals(captured.getKind())) {
                gameOver = true;
            }
        }
    }

    /**
     * @return a read-only snapshot for the renderer/{@code BoardPrinter}: board
     * dimensions and pieces (via the board reference), the selected cell, and whether
     * the game is over. Never exposes a live, mutable {@code Board} for writing.
     */
    public GameSnapshot snapshot() {
        return new GameSnapshot(board, getSelectedPosition().orElse(null), gameOver);
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
    public GameEngine loadBoard(String config, BoardParser<String> parser) throws BoardParser.BoardParseException {
        this.board = parser.parse(config);
        return this;
    }

    /**
     * Returns the current board instance.
     *
     * @return the board
     */
    public Board getBoard() {
        return board;
    }

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
     * Delegated to {@link com.kungfuchess.input.Controller}, the sole owner of this state.
     *
     * @return the selected position
     */
    public Optional<Position> getSelectedPosition() {
        return getController().getSelected();
    }

    /**
     * Checks if a piece is currently selected (via the {@link com.kungfuchess.input.Controller}).
     *
     * @return {@code true} if a piece is selected
     */
    public boolean hasSelection() {
        return getSelectedPosition().isPresent();
    }

    // -------------------------------------------------------------------------
    // Coordinate Conversion
    // -------------------------------------------------------------------------

    /**
     * Converts pixel coordinates to board coordinates.
     *
     * <p>Delegates to {@link BoardMapper}, the sole Coordinate Adapter — this engine
     * (the model layer) does not itself know that a cell is 100×100 pixels.</p>
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
     * Returns the current simulated game clock in milliseconds, as tracked by
     * {@link RealTimeArbiter}.
     *
     * @return the game clock
     */
    public long getGameClock() {
        return arbiter.getClock();
    }

    /**
     * Result of {@link #requestMove}: whether the move request was accepted, and a
     * stable, machine-readable reason.
     *
     * <p>For an accepted move, {@code reason} is {@code "ok"}. Application-level
     * rejections use {@code "game_over"} or {@code "motion_in_progress"}. Rule-level
     * rejections copy the reason straight from {@link RuleEngine.MoveValidation}.</p>
     *
     * <p>Nested here (rather than its own file) to match the project's exact package
     * structure, in which the {@code engine} package holds exactly {@code GameEngine}.</p>
     */
    public static final class MoveResult {

        public static final String OK = "ok";
        public static final String GAME_OVER = "game_over";
        public static final String MOTION_IN_PROGRESS = "motion_in_progress";

        private final boolean accepted;
        private final String reason;

        public MoveResult(boolean accepted, String reason) {
            this.accepted = accepted;
            this.reason = reason;
        }

        public boolean isAccepted() {
            return accepted;
        }

        public String reason() {
            return reason;
        }

        public static MoveResult ok() {
            return new MoveResult(true, OK);
        }

        public static MoveResult rejected(String reason) {
            return new MoveResult(false, reason);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MoveResult)) return false;
            MoveResult other = (MoveResult) o;
            return accepted == other.accepted && java.util.Objects.equals(reason, other.reason);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(accepted, reason);
        }

        @Override
        public String toString() {
            return "MoveResult(accepted=" + accepted + ", reason=" + reason + ")";
        }
    }

    /**
     * Read-only view model handed to the renderer and {@code BoardPrinter}.
     *
     * <p>The renderer never receives a live {@code Board} for writing — only this
     * snapshot — so it cannot accidentally mutate game state.</p>
     *
     * <p>Nested here (rather than its own file) to match the project's exact package
     * structure, in which the {@code engine} package holds exactly {@code GameEngine}.</p>
     */
    public static final class GameSnapshot {

        private final Board board;
        private final Position selectedCell;
        private final boolean gameOver;

        public GameSnapshot(Board board, Position selectedCell, boolean gameOver) {
            this.board = board;
            this.selectedCell = selectedCell;
            this.gameOver = gameOver;
        }

        public int boardWidth() {
            return board.getWidth();
        }

        public int boardHeight() {
            return board.getHeight();
        }

        /** @return the underlying board, for read-only queries (piece kind/color/position). */
        public Board board() {
            return board;
        }

        public Position selectedCell() {
            return selectedCell;
        }

        public boolean gameOver() {
            return gameOver;
        }

        @Override
        public String toString() {
            return "GameSnapshot(width=" + boardWidth() + ", height=" + boardHeight()
                + ", selectedCell=" + selectedCell + ", gameOver=" + gameOver + ")";
        }
    }
}
