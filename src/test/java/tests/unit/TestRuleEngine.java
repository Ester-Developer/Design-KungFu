package tests.unit;

import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import com.kungfuchess.rules.RuleEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestRuleEngine {

    private RuleEngine ruleEngine;
    private Board board;

    @BeforeEach
    void setUp() {
        ruleEngine = new RuleEngine();
        board = Board.create(8, 8);
    }

    @Test
    void outOfBoundsDestinationIsRejected() throws Exception {
        board.addPiece(new Position(0, 0), new Piece("King", "white"));
        RuleEngine.MoveValidation result = ruleEngine.validateMove(board, new Position(0, 0), new Position(9, 9), true);
        assertFalse(result.isValid());
        assertEquals(RuleEngine.MoveValidation.OUTSIDE_BOARD, result.reason());
    }

    @Test
    void emptySourceIsRejected() throws Exception {
        RuleEngine.MoveValidation result = ruleEngine.validateMove(board, new Position(0, 0), new Position(1, 1), true);
        assertFalse(result.isValid());
        assertEquals(RuleEngine.MoveValidation.EMPTY_SOURCE, result.reason());
    }

    @Test
    void friendlyDestinationIsRejected() throws Exception {
        board.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        board.addPiece(new Position(0, 3), new Piece("Pawn", "white"));

        RuleEngine.MoveValidation result = ruleEngine.validateMove(board, new Position(0, 0), new Position(0, 3), true);

        assertFalse(result.isValid());
        assertEquals(RuleEngine.MoveValidation.FRIENDLY_DESTINATION, result.reason());
    }

    @Test
    void illegalShapeIsRejected() throws Exception {
        board.addPiece(new Position(0, 0), new Piece("King", "white"));
        RuleEngine.MoveValidation result = ruleEngine.validateMove(board, new Position(0, 0), new Position(2, 2), true);
        assertFalse(result.isValid());
        assertEquals(RuleEngine.MoveValidation.ILLEGAL_PIECE_MOVE, result.reason());
    }

    @Test
    void legalMoveIsAccepted() throws Exception {
        board.addPiece(new Position(0, 0), new Piece("King", "white"));
        RuleEngine.MoveValidation result = ruleEngine.validateMove(board, new Position(0, 0), new Position(1, 1), true);
        assertTrue(result.isValid());
        assertEquals(RuleEngine.MoveValidation.OK, result.reason());
    }

    @Test
    void blockedSlidingPieceIsRejected() throws Exception {
        board.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        board.addPiece(new Position(0, 2), new Piece("Pawn", "black"));

        RuleEngine.MoveValidation result = ruleEngine.validateMove(board, new Position(0, 0), new Position(0, 4), true);

        assertFalse(result.isValid());
        assertEquals(RuleEngine.MoveValidation.ILLEGAL_PIECE_MOVE, result.reason());
    }

    @Test
    void pawnCanDoubleStepOnlyBeforeItsFirstMove() throws Exception {
        board.addPiece(new Position(6, 0), new Piece("Pawn", "white"));

        assertTrue(ruleEngine.isMoveLegal(board, new Position(6, 0), new Position(4, 0), false));
        assertFalse(ruleEngine.isMoveLegal(board, new Position(6, 0), new Position(4, 0), true));
    }

    @Test
    void isMoveLegalConvenienceMethodMatchesValidateMove() throws Exception {
        board.addPiece(new Position(0, 0), new Piece("King", "white"));
        assertTrue(ruleEngine.isMoveLegal(board, new Position(0, 0), new Position(1, 0), true));
        assertFalse(ruleEngine.isMoveLegal(board, new Position(0, 0), new Position(2, 0), true));
    }
}
