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
        // Travel time is distance/speed: 3 cells / 1.5 m/s * 1000 = 2000ms
        engine.waitMs(2000);

        assertTrue(engine.getBoard().pieceAt(new Position(0, 0)).isEmpty());
        assertTrue(engine.getBoard().pieceAt(new Position(0, 3)).isPresent());
    }

    @Test
    void sameMovingPieceIsRejectedWhileOnCooldown() throws Exception {
        // The rook starts moving; trying to move it again immediately is blocked
        engine.requestMove(new Position(0, 0), new Position(0, 3));
        GameEngine.MoveResult second = engine.requestMove(new Position(0, 0), new Position(0, 1));

        assertFalse(second.isAccepted());
        assertEquals(GameEngine.MoveResult.PIECE_ON_COOLDOWN, second.reason());
    }

    @Test
    void differentPieceCanMoveWhileFirstIsStillInFlight() throws Exception {
        // Kung-Fu Chess: a different piece is NOT blocked by another piece's motion
        Board board = engine.getBoard();
        board.addPiece(new Position(2, 0), new Piece("Bishop", "white"));

        engine.requestMove(new Position(0, 0), new Position(0, 3)); // rook in flight

        // Bishop at (2,0) has its own cooldown — it should be free to move
        GameEngine.MoveResult bishopMove = engine.requestMove(new Position(2, 0), new Position(3, 1));
        assertTrue(bishopMove.isAccepted(),
            "A different piece must be movable while another piece is in flight");
    }

    @Test
    void oppositeColorPieceCanMoveWhileFirstIsStillInFlight() throws Exception {
        Board board = engine.getBoard();
        board.addPiece(new Position(7, 7), new Piece("Rook", "black"));

        engine.requestMove(new Position(0, 0), new Position(0, 3)); // white rook in flight

        // Black rook at (7,7) should be free to move concurrently
        GameEngine.MoveResult blackMove = engine.requestMove(new Position(7, 7), new Position(7, 4));
        assertTrue(blackMove.isAccepted(),
            "Opposite-color piece must be movable while white piece is in flight");
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
        // 2 cells / 1.5 m/s * 1000 = ~1333ms; use 2000ms to be safe
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

    @Test
    void pieceIsOnCooldownAfterMoveStartsAndClearsAfterRestChain() throws Exception {
        Piece rook = engine.getBoard().pieceAt(new Position(0, 0)).orElseThrow();
        engine.requestMove(new Position(0, 0), new Position(0, 1));

        // Immediately after starting: on cooldown
        assertTrue(engine.getArbiter().isOnCooldown(rook));

        // After travel (1 cell / 1.5 m/s ≈ 667ms) + full rest chain (long_rest: 5 frames @ 2fps = 2500ms)
        // Total ≈ 3167ms; advance well past that
        engine.waitMs(5000);
        assertFalse(engine.getArbiter().isOnCooldown(rook),
            "Cooldown must expire after travel + full rest chain");
    }
}
