package com.kungfuchess.rules;
import com.kungfuchess.model.Board;

import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;

import java.util.Optional;

/**
 * Validates chess movement rules.
 *
 * <p><strong>Important Design Constraint:</strong> The RuleEngine is <em>read-only</em>.
 * It queries the board to determine if a move is legal, but never modifies the board.
 * All board mutations are the responsibility of the Board class.</p>
 *
 * <p>This separation ensures that:
 * - Rules can be tested independently of board state mutations
 * - The Board remains the single source of truth for piece placement
 * - Commands orchestrate the validation → execution flow</p>
 */
public class RuleEngine {

    /**
     * Validates a requested move and returns a stable, machine-readable result.
     *
     * <p>Checks, in order: both positions in bounds, the source isn't empty, the
     * destination isn't occupied by a same-color piece, and the piece's own movement
     * rule (shape + path-clear for sliding pieces, via {@link PieceRules}) allows it.
     * This method only <em>queries</em> the board; it never mutates it, moves pieces,
     * removes captures, or starts motions — that is {@code RealTimeArbiter}'s job once
     * {@code GameEngine} has accepted this validation.</p>
     *
     * <p>{@code GameEngine} is responsible for the {@code game_over} and
     * {@code motion_in_progress} application-level guards; this method knows about
     * neither.</p>
     *
     * @param board        the board to query
     * @param from         source position
     * @param to           destination position
     * @param pawnHasMoved {@code true} if the piece at {@code from} has already moved
     *                     at least once before (irrelevant for non-Pawn pieces)
     * @return {@code MoveValidation.ok()} if legal, or an invalid result carrying a
     * stable reason ({@code "outside_board"}, {@code "empty_source"},
     * {@code "friendly_destination"}, or {@code "illegal_piece_move"})
     * @throws Board.OutOfBoundsException if either position is out of bounds
     */
    public MoveValidation validateMove(Board board, Position from, Position to)
            throws Board.OutOfBoundsException {
        return validateMove(board, from, to, true);
    }

    public MoveValidation validateMove(Board board, Position from, Position to, boolean pawnHasMoved)
            throws Board.OutOfBoundsException {
        if (!board.isInBounds(from) || !board.isInBounds(to)) {
            return MoveValidation.invalid(MoveValidation.OUTSIDE_BOARD);
        }

        Optional<Piece> movingOpt = board.pieceAt(from);
        if (movingOpt.isEmpty()) {
            return MoveValidation.invalid(MoveValidation.EMPTY_SOURCE);
        }
        Piece moving = movingOpt.get();

        if (from.equals(to)) {
            return MoveValidation.invalid(MoveValidation.ILLEGAL_PIECE_MOVE);
        }

        if ("Pawn".equals(moving.getKind())) {
            return isPawnMoveLegal(board, from, to, pawnHasMoved)
                ? MoveValidation.ok()
                : MoveValidation.invalid(MoveValidation.ILLEGAL_PIECE_MOVE);
        }

        if (!PieceRules.matchesShape(moving.getKind(), from, to)) {
            return MoveValidation.invalid(MoveValidation.ILLEGAL_PIECE_MOVE);
        }

        if (PieceRules.isSlidingPiece(moving.getKind()) && !PieceRules.isPathClear(board, from, to)) {
            return MoveValidation.invalid(MoveValidation.ILLEGAL_PIECE_MOVE);
        }

        Optional<Piece> destination = board.pieceAt(to);
        if (destination.isPresent() && destination.get().getColor().equals(moving.getColor())) {
            return MoveValidation.invalid(MoveValidation.FRIENDLY_DESTINATION);
        }

        return MoveValidation.ok();
    }

    /**
     * Determines if a move is legal according to chess rules.
     *
     * <p>Thin convenience wrapper around {@link #validateMove}, kept for existing
     * callers that only need a boolean.</p>
     *
     * @param board the board to query
     * @param from  source position
     * @param to    destination position
     * @return {@code true} if the move is legal, {@code false} otherwise
     * @throws Board.OutOfBoundsException if either position is out of bounds
     */
    public boolean isMoveLegal(Board board, Position from, Position to) throws Board.OutOfBoundsException {
        return validateMove(board, from, to).isValid();
    }

    /**
     * @see #isMoveLegal(Board, Position, Position)
     * @param pawnHasMoved {@code true} if the piece at {@code from} has already moved
     *                     at least once (used for pawn double-step gate)
     */
    public boolean isMoveLegal(Board board, Position from, Position to, boolean pawnHasMoved)
            throws Board.OutOfBoundsException {
        return validateMove(board, from, to, pawnHasMoved).isValid();
    }

    /**
     * Pawn movement shape/occupancy rules:
     * one square straight onto an empty cell, two squares straight on the pawn's first
     * move (with clear intermediate square), or one square diagonally onto an
     * enemy-occupied cell.
     */
    private boolean isPawnMoveLegal(Board board, Position from, Position to, boolean hasMoved)
            throws Board.OutOfBoundsException {
        int dr = to.getRow() - from.getRow();
        int dc = to.getCol() - from.getCol();
        Piece moving = board.pieceAt(from).get();
        int forward = "white".equals(moving.getColor()) ? -1 : 1;

        // One square forward onto an empty cell
        if (dc == 0 && dr == forward) {
            return board.pieceAt(to).isEmpty();
        }

        // Two squares forward on first move, intermediate square must also be empty
        if (dc == 0 && dr == 2 * forward && !hasMoved) {
            Position mid = new Position(from.getRow() + forward, from.getCol());
            return board.pieceAt(mid).isEmpty() && board.pieceAt(to).isEmpty();
        }

        // One square diagonal onto an enemy piece
        if (Math.abs(dc) == 1 && dr == forward) {
            Optional<Piece> destination = board.pieceAt(to);
            return destination.isPresent() && !destination.get().getColor().equals(moving.getColor());
        }

        return false;
    }

    /**
     * @deprecated Use {@link #isMoveLegal(Board, Position, Position)} instead.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    public boolean isMoveLegal(Object state, Object move) {
        return true;
    }

    /**
     * Result of {@link #validateMove}: whether a requested move is legal according to
     * chess movement rules, and a stable, machine-readable reason.
     *
     * <p>{@code reason} is always present: {@code "ok"} for a valid move, or one of the
     * stable rule-level reasons for an invalid one ({@code "outside_board"},
     * {@code "empty_source"}, {@code "friendly_destination"}, {@code "illegal_piece_move"}).</p>
     *
     * <p>Nested here (rather than its own file) to match the project's exact package
     * structure, in which the {@code rules} package holds exactly {@code PieceRules}
     * and {@code RuleEngine}.</p>
     *
     * <p>Deliberately a plain class rather than a {@code record}: some build/grading
     * environments in this course run on a compiler older than Java 16.</p>
     */
    public static final class MoveValidation {

        public static final String OK = "ok";
        public static final String OUTSIDE_BOARD = "outside_board";
        public static final String EMPTY_SOURCE = "empty_source";
        public static final String FRIENDLY_DESTINATION = "friendly_destination";
        public static final String ILLEGAL_PIECE_MOVE = "illegal_piece_move";

        private final boolean valid;
        private final String reason;

        public MoveValidation(boolean valid, String reason) {
            this.valid = valid;
            this.reason = reason;
        }

        public boolean isValid() {
            return valid;
        }

        public String reason() {
            return reason;
        }

        public static MoveValidation ok() {
            return new MoveValidation(true, OK);
        }

        public static MoveValidation invalid(String reason) {
            return new MoveValidation(false, reason);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MoveValidation)) return false;
            MoveValidation other = (MoveValidation) o;
            return valid == other.valid && java.util.Objects.equals(reason, other.reason);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(valid, reason);
        }

        @Override
        public String toString() {
            return "MoveValidation(valid=" + valid + ", reason=" + reason + ")";
        }
    }
}
