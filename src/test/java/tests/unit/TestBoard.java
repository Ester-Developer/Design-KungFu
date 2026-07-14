package tests.unit;

import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TestBoard {

    private Board board;

    @BeforeEach
    void setUp() {
        board = Board.create(3, 3);
    }

    @Test
    void newBoardIsEmpty() throws Exception {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                assertTrue(board.pieceAt(new Position(r, c)).isEmpty());
            }
        }
    }

    @Test
    void addPiecePlacesItOnTheBoard() throws Exception {
        Piece king = new Piece("King", "white");
        board.addPiece(new Position(1, 1), king);
        assertEquals(Optional.of(king), board.pieceAt(new Position(1, 1)));
    }

    @Test
    void addPieceOnOccupiedCellThrows() throws Exception {
        board.addPiece(new Position(0, 0), new Piece("King", "white"));
        assertThrows(Board.OccupiedCellException.class,
            () -> board.addPiece(new Position(0, 0), new Piece("Queen", "black")));
    }

    @Test
    void pieceAtOutOfBoundsThrows() {
        assertThrows(Board.OutOfBoundsException.class, () -> board.pieceAt(new Position(5, 5)));
    }

    @Test
    void isInBoundsRejectsNegativeAndOverflowingPositions() {
        assertTrue(board.isInBounds(new Position(0, 0)));
        assertTrue(board.isInBounds(new Position(2, 2)));
        assertFalse(board.isInBounds(new Position(-1, 0)));
        assertFalse(board.isInBounds(new Position(0, 3)));
    }

    @Test
    void movePieceRelocatesIt() throws Exception {
        Piece rook = new Piece("Rook", "white");
        board.addPiece(new Position(0, 0), rook);
        board.movePiece(new Position(0, 0), new Position(0, 2));

        assertTrue(board.pieceAt(new Position(0, 0)).isEmpty());
        assertEquals(Optional.of(rook), board.pieceAt(new Position(0, 2)));
    }

    @Test
    void movePieceOntoOccupiedCellCapturesSilently() throws Exception {
        Piece attacker = new Piece("Rook", "white");
        Piece defender = new Piece("Rook", "black");
        board.addPiece(new Position(0, 0), attacker);
        board.addPiece(new Position(0, 2), defender);

        board.movePiece(new Position(0, 0), new Position(0, 2));

        assertEquals(Optional.of(attacker), board.pieceAt(new Position(0, 2)));
    }

    @Test
    void movePieceFromEmptyCellThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> board.movePiece(new Position(0, 0), new Position(0, 1)));
    }

    @Test
    void removePieceClearsTheCellAndReturnsIt() throws Exception {
        Piece piece = new Piece("Pawn", "white");
        board.addPiece(new Position(1, 1), piece);

        Optional<Piece> removed = board.removePiece(new Position(1, 1));

        assertEquals(Optional.of(piece), removed);
        assertTrue(board.pieceAt(new Position(1, 1)).isEmpty());
    }

    @Test
    void createStandardBoardIsEightByEight() {
        Board standard = Board.createStandard();
        assertEquals(8, standard.getWidth());
        assertEquals(8, standard.getHeight());
    }

    @Test
    void createWithNonPositiveDimensionsThrows() {
        assertThrows(IllegalArgumentException.class, () -> Board.create(0, 5));
        assertThrows(IllegalArgumentException.class, () -> Board.create(5, -1));
    }
}
