package tests.unit;

import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import com.kungfuchess.rules.PieceRules;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestPieceRules {

    @Test
    void kingMatchesOneSquareInAnyDirection() {
        Position from = new Position(3, 3);
        assertTrue(PieceRules.matchesShape("King", from, new Position(4, 4)));
        assertTrue(PieceRules.matchesShape("King", from, new Position(2, 3)));
        assertFalse(PieceRules.matchesShape("King", from, new Position(5, 5)));
    }

    @Test
    void rookMatchesOnlyStraightLines() {
        Position from = new Position(0, 0);
        assertTrue(PieceRules.matchesShape("Rook", from, new Position(0, 5)));
        assertTrue(PieceRules.matchesShape("Rook", from, new Position(5, 0)));
        assertFalse(PieceRules.matchesShape("Rook", from, new Position(2, 2)));
    }

    @Test
    void bishopMatchesOnlyDiagonals() {
        Position from = new Position(0, 0);
        assertTrue(PieceRules.matchesShape("Bishop", from, new Position(3, 3)));
        assertFalse(PieceRules.matchesShape("Bishop", from, new Position(3, 0)));
    }

    @Test
    void knightMatchesOnlyLShapes() {
        Position from = new Position(3, 3);
        assertTrue(PieceRules.matchesShape("Knight", from, new Position(1, 4)));
        assertTrue(PieceRules.matchesShape("Knight", from, new Position(5, 2)));
        assertFalse(PieceRules.matchesShape("Knight", from, new Position(4, 4)));
    }

    @Test
    void queenMatchesStraightOrDiagonal() {
        Position from = new Position(3, 3);
        assertTrue(PieceRules.matchesShape("Queen", from, new Position(3, 6)));
        assertTrue(PieceRules.matchesShape("Queen", from, new Position(6, 6)));
        assertFalse(PieceRules.matchesShape("Queen", from, new Position(5, 4)));
    }

    @Test
    void zeroLengthVectorNeverMatches() {
        Position p = new Position(2, 2);
        assertFalse(PieceRules.matchesShape("Queen", p, p));
    }

    @Test
    void isSlidingPieceIsTrueOnlyForRookBishopQueen() {
        assertTrue(PieceRules.isSlidingPiece("Rook"));
        assertTrue(PieceRules.isSlidingPiece("Bishop"));
        assertTrue(PieceRules.isSlidingPiece("Queen"));
        assertFalse(PieceRules.isSlidingPiece("King"));
        assertFalse(PieceRules.isSlidingPiece("Knight"));
    }

    @Test
    void pathIsClearWhenNothingBetween() throws Exception {
        Board board = Board.create(8, 8);
        assertTrue(PieceRules.isPathClear(board, new Position(0, 0), new Position(0, 5)));
    }

    @Test
    void pathIsBlockedByAnyInterveningPiece() throws Exception {
        Board board = Board.create(8, 8);
        board.addPiece(new Position(0, 3), new Piece("Pawn", "white"));
        assertFalse(PieceRules.isPathClear(board, new Position(0, 0), new Position(0, 5)));
    }

    @Test
    void legalMovesExcludesSquaresOccupiedBySameColor() {
        Board board = Board.create(8, 8);
        Piece rook = new Piece("Rook", "white");
        try {
            board.addPiece(new Position(0, 0), rook);
            board.addPiece(new Position(0, 3), new Piece("Pawn", "white"));
        } catch (Exception e) {
            fail(e);
        }

        List<Position> moves = PieceRules.legalMoves(rook, new Position(0, 0), board);

        assertTrue(moves.contains(new Position(0, 1)));
        assertTrue(moves.contains(new Position(0, 2)));
        assertFalse(moves.contains(new Position(0, 3)));
        assertFalse(moves.contains(new Position(0, 4)));
    }

    @Test
    void legalMovesIncludesCaptureOfEnemyPieceButStopsThere() {
        Board board = Board.create(8, 8);
        Piece rook = new Piece("Rook", "white");
        try {
            board.addPiece(new Position(0, 0), rook);
            board.addPiece(new Position(0, 3), new Piece("Pawn", "black"));
        } catch (Exception e) {
            fail(e);
        }

        List<Position> moves = PieceRules.legalMoves(rook, new Position(0, 0), board);

        assertTrue(moves.contains(new Position(0, 3)));
        assertFalse(moves.contains(new Position(0, 4)));
    }

    @Test
    void knightLegalMovesIgnoreBlockingPieces() {
        Board board = Board.create(8, 8);
        Piece knight = new Piece("Knight", "white");
        try {
            board.addPiece(new Position(3, 3), knight);
            board.addPiece(new Position(3, 4), new Piece("Pawn", "white"));
            board.addPiece(new Position(2, 3), new Piece("Pawn", "white"));
        } catch (Exception e) {
            fail(e);
        }

        List<Position> moves = PieceRules.legalMoves(knight, new Position(3, 3), board);

        assertTrue(moves.contains(new Position(1, 4)));
        assertTrue(moves.contains(new Position(5, 2)));
    }
}
