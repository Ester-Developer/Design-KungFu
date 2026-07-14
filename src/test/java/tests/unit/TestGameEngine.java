package tests.unit;

import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestGameEngine {

    private GameEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        Board board = Board.create(8, 8);
        board.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        engine = new GameEngine().setBoard(board);
    }

    @Test
    void requestMoveAcceptsALegalMove() throws Exception {
        GameEngine.MoveResult result = engine.requestMove(new Position(0, 0), new Position(0, 3));
        assertTrue(result.isAccepted());
        assertEquals(GameEngine.MoveResult.OK, result.reason());
    }

    @Test
    void requestMoveRejectsAnIllegalMoveWithRuleReason() throws Exception {
        GameEngine.MoveResult result = engine.requestMove(new Position(0, 0), new Position(1, 1));
        assertFalse(result.isAccepted());
        assertEquals("illegal_piece_move", result.reason());
    }

    @Test
    void requestMoveDoesNotMutateTheBoardImmediately() throws Exception {
        engine.requestMove(new Position(0, 0), new Position(0, 3));
        assertTrue(engine.getBoard().pieceAt(new Position(0, 0)).isPresent());
        assertTrue(engine.getBoard().pieceAt(new Position(0, 3)).isEmpty());
    }

    @Test
    void waitMsResolvesTheMotionOnceItsTravelTimeElapses() throws Exception {
        engine.requestMove(new Position(0, 0), new Position(0, 3));
        engine.waitMs(3000);

        assertTrue(engine.getBoard().pieceAt(new Position(0, 0)).isEmpty());
        assertTrue(engine.getBoard().pieceAt(new Position(0, 3)).isPresent());
    }

    @Test
    void secondMoveIsRejectedWhileFirstIsStillInFlight() throws Exception {
        Board board = engine.getBoard();
        board.addPiece(new Position(2, 0), new Piece("Bishop", "white"));

        engine.requestMove(new Position(0, 0), new Position(0, 3));
        GameEngine.MoveResult second = engine.requestMove(new Position(2, 0), new Position(3, 1));

        assertFalse(second.isAccepted());
        assertEquals(GameEngine.MoveResult.MOTION_IN_PROGRESS, second.reason());
    }

    @Test
    void requestMoveIsRejectedOnceGameIsOver() throws Exception {
        engine.setGameOver(true);
        GameEngine.MoveResult result = engine.requestMove(new Position(0, 0), new Position(0, 3));
        assertFalse(result.isAccepted());
        assertEquals(GameEngine.MoveResult.GAME_OVER, result.reason());
    }

    @Test
    void capturingAKingSetsGameOver() throws Exception {
        Board board = engine.getBoard();
        board.addPiece(new Position(0, 2), new Piece("King", "black"));

        engine.requestMove(new Position(0, 0), new Position(0, 2));
        engine.waitMs(2000);

        assertTrue(engine.isGameOver());
    }

    @Test
    void snapshotReflectsCurrentGameOverAndSelectionState() throws Exception {
        engine.getController().click(0, 0); // pixel (0,0) selects the rook at (0,0)

        GameEngine.GameSnapshot snapshot = engine.snapshot();

        assertEquals(new Position(0, 0), snapshot.selectedCell());
        assertFalse(snapshot.gameOver());
        assertEquals(8, snapshot.boardWidth());
        assertEquals(8, snapshot.boardHeight());
    }
}
