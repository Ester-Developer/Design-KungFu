package com.kungfuchess.rules;
import com.kungfuchess.model.Board;

import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pure geometry rules for how each piece kind is allowed to move.
 *
 * <p>This class knows nothing about turns, check, or game state — it only answers two
 * questions: "does this {@code from -> to} vector have the right shape for this piece?"
 * and "is the straight-line path between them clear?". Occupancy/capture rules that
 * depend on whose turn it is belong to {@link RuleEngine}, which composes these
 * primitives with board state.</p>
 */
public final class PieceRules {

    private PieceRules() {}

    /**
     * @return {@code true} if this piece kind moves along a straight/diagonal line of
     * arbitrary length and is therefore blocked by intervening pieces (Rook, Bishop, Queen).
     * King and Knight are excluded: the King only ever moves one square (nothing to block),
     * and the Knight jumps over intervening pieces by definition.
     */
    public static boolean isSlidingPiece(String kind) {
        return "Rook".equals(kind) || "Bishop".equals(kind) || "Queen".equals(kind);
    }

    /**
     * Checks whether the vector {@code from -> to} matches the movement shape of the given
     * piece kind, ignoring board occupancy entirely.
     *
     * @return {@code true} if the shape is legal for this piece kind
     */
    public static boolean matchesShape(String kind, Position from, Position to) {
        int dr = to.getRow() - from.getRow();
        int dc = to.getCol() - from.getCol();
        if (dr == 0 && dc == 0) return false;

        switch (kind) {
            case "King":
                return Math.abs(dr) <= 1 && Math.abs(dc) <= 1;
            case "Rook":
                return dr == 0 || dc == 0;
            case "Bishop":
                return Math.abs(dr) == Math.abs(dc);
            case "Queen":
                return dr == 0 || dc == 0 || Math.abs(dr) == Math.abs(dc);
            case "Knight":
                return (Math.abs(dr) == 1 && Math.abs(dc) == 2) || (Math.abs(dr) == 2 && Math.abs(dc) == 1);
            case "Pawn":
                return Math.abs(dc) <= 1 && Math.abs(dr) == 1; // one square forward, diagonal capture
            default:
                return false;
        }
    }

    /**
     * Walks the straight/diagonal line strictly between {@code from} and {@code to}
     * (both endpoints excluded) and reports whether every intermediate square is empty.
     *
     * <p>Only meaningful for {@link #isSlidingPiece sliding pieces}; the caller is
     * expected to have already confirmed the vector is a straight line via
     * {@link #matchesShape}.</p>
     */
    public static boolean isPathClear(Board board, Position from, Position to) throws Board.OutOfBoundsException {
        int dr = Integer.signum(to.getRow() - from.getRow());
        int dc = Integer.signum(to.getCol() - from.getCol());

        int r = from.getRow() + dr;
        int c = from.getCol() + dc;
        while (r != to.getRow() || c != to.getCol()) {
            if (board.pieceAt(new Position(r, c)).isPresent()) {
                return false;
            }
            r += dr;
            c += dc;
        }
        return true;
    }

    /**
     * Enumerates every square this piece could legally move to from {@code pos} on the
     * given board: correct shape, unblocked path (for sliding pieces), and not occupied
     * by a piece of the same color.
     *
     * @return the list of reachable positions (never {@code null}, possibly empty)
     */
    public static List<Position> legalMoves(Piece piece, Position pos, Board board) {
        List<Position> moves = new ArrayList<>();
        for (int r = 0; r < board.getHeight(); r++) {
            for (int c = 0; c < board.getWidth(); c++) {
                Position target = new Position(r, c);
                if (target.equals(pos) || !matchesShape(piece.getKind(), pos, target)) {
                    continue;
                }
                try {
                    if (isSlidingPiece(piece.getKind()) && !isPathClear(board, pos, target)) {
                        continue;
                    }
                    Optional<Piece> occupant = board.pieceAt(target);
                    if (occupant.isPresent() && occupant.get().getColor().equals(piece.getColor())) {
                        continue;
                    }
                    moves.add(target);
                } catch (Board.OutOfBoundsException e) {
                    // Unreachable: r/c are always within [0, height)/[0, width).
                }
            }
        }
        return moves;
    }
}
