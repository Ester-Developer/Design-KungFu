package tests.unit;

import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.input.BoardMapper;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestGameEngine {

    private GameEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        Board board = Board.create(8, 8);
        board.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        engine = new GameEngine().setBoard(board);
    }

    // -------------------------------------------------------------------------
    // Basic acceptance / rule rejection
    // -------------------------------------------------------------------------

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
        engine.waitMs(2000);
        assertTrue(engine.getBoard().pieceAt(new Position(0, 0)).isEmpty());
        assertTrue(engine.getBoard().pieceAt(new Position(0, 3)).isPresent());
    }

    // -------------------------------------------------------------------------
    // Check order: (a) game_over, (b) piece_on_cooldown, (c) rule validation
    // -------------------------------------------------------------------------

    @Test
    void checkOrder_gameOverIsRejectedFirst() throws Exception {
        engine.setGameOver(true);
        GameEngine.MoveResult result = engine.requestMove(new Position(0, 0), new Position(0, 3));
        assertFalse(result.isAccepted());
        assertEquals(GameEngine.MoveResult.GAME_OVER, result.reason());
    }

    @Test
    void checkOrder_pieceOnCooldownIsRejectedBeforeRuleCheck() throws Exception {
        // Put the rook on cooldown
        engine.requestMove(new Position(0, 0), new Position(0, 3));
        // Try an ILLEGAL move on the same piece — must get piece_on_cooldown, not
        // illegal_piece_move, proving cooldown is checked before RuleEngine
        GameEngine.MoveResult result = engine.requestMove(new Position(0, 0), new Position(1, 1));
        assertFalse(result.isAccepted());
        assertEquals(GameEngine.MoveResult.PIECE_ON_COOLDOWN, result.reason(),
            "piece_on_cooldown must be returned before rule validation");
    }

    // -------------------------------------------------------------------------
    // Per-piece cooldown gate — Kung-Fu Chess concurrent movement
    // -------------------------------------------------------------------------

    @Test
    void sameMovingPieceIsRejectedWithPieceOnCooldownWhileInFlight() throws Exception {
        engine.requestMove(new Position(0, 0), new Position(0, 3));
        GameEngine.MoveResult second = engine.requestMove(new Position(0, 0), new Position(0, 1));
        assertFalse(second.isAccepted());
        assertEquals(GameEngine.MoveResult.PIECE_ON_COOLDOWN, second.reason(),
            "Same piece must be rejected with piece_on_cooldown while its motion is active");
    }

    @Test
    void differentPieceCanMoveWhileFirstIsStillInFlight() throws Exception {
        // Core Kung-Fu Chess mechanic: different pieces move concurrently
        engine.getBoard().addPiece(new Position(2, 0), new Piece("Bishop", "white"));
        engine.requestMove(new Position(0, 0), new Position(0, 3)); // rook in flight
        GameEngine.MoveResult bishopMove =
            engine.requestMove(new Position(2, 0), new Position(3, 1));
        assertTrue(bishopMove.isAccepted(),
            "A different piece must be movable while another piece is in flight");
    }

    @Test
    void oppositeColorPieceCanMoveWhileFirstIsStillInFlight() throws Exception {
        engine.getBoard().addPiece(new Position(7, 7), new Piece("Rook", "black"));
        engine.requestMove(new Position(0, 0), new Position(0, 3)); // white rook in flight
        GameEngine.MoveResult blackMove =
            engine.requestMove(new Position(7, 7), new Position(7, 4));
        assertTrue(blackMove.isAccepted(),
            "Opposite-color piece must be movable while white piece is in flight");
    }

    @Test
    void moveIsAcceptedAgainAfterCooldownExpires() throws Exception {
        engine.requestMove(new Position(0, 0), new Position(0, 3));
        engine.waitMs(5000); // travel + full rest chain
        GameEngine.MoveResult second =
            engine.requestMove(new Position(0, 3), new Position(0, 6));
        assertTrue(second.isAccepted(),
            "A new move must be accepted once the piece's cooldown has expired");
    }

    // -------------------------------------------------------------------------
    // game_over
    // -------------------------------------------------------------------------

    @Test
    void requestMoveIsRejectedOnceGameIsOver() throws Exception {
        engine.setGameOver(true);
        GameEngine.MoveResult result = engine.requestMove(new Position(0, 0), new Position(0, 3));
        assertFalse(result.isAccepted());
        assertEquals(GameEngine.MoveResult.GAME_OVER, result.reason());
    }

    @Test
    void capturingAKingSetsGameOver() throws Exception {
        engine.getBoard().addPiece(new Position(0, 2), new Piece("King", "black"));
        engine.requestMove(new Position(0, 0), new Position(0, 2));
        engine.waitMs(2000);
        assertTrue(engine.isGameOver());
    }

    // -------------------------------------------------------------------------
    // Snapshot
    // -------------------------------------------------------------------------

    @Test
    void snapshotReflectsCurrentGameOverAndSelectionState() throws Exception {
        engine.getController().click(BoardMapper.BOARD_X_OFFSET, BoardMapper.BOARD_Y_OFFSET); // board top-left pixel → cell (0,0)
        GameEngine.GameSnapshot snapshot = engine.snapshot();
        assertEquals(new Position(0, 0), snapshot.selectedCell());
        assertFalse(snapshot.gameOver());
        assertEquals(8, snapshot.boardWidth());
        assertEquals(8, snapshot.boardHeight());
    }

    @Test
    void snapshotPiecesListContainsPieceAtExpectedPosition() throws Exception {
        GameEngine.GameSnapshot snapshot = engine.snapshot();
        boolean found = snapshot.pieces().stream()
            .anyMatch(pv -> "Rook".equals(pv.kind())
                         && "white".equals(pv.color())
                         && new Position(0, 0).equals(pv.position()));
        assertTrue(found, "Snapshot pieces list must contain the rook at (0,0)");
    }

    @Test
    void snapshotIsolation_mutatingReturnedListDoesNotAffectEngine() throws Exception {
        GameEngine.GameSnapshot snap = engine.snapshot();
        int sizeBefore = snap.pieces().size();
        try {
            snap.pieces().clear();
        } catch (UnsupportedOperationException ignored) {}
        GameEngine.GameSnapshot snap2 = engine.snapshot();
        assertEquals(sizeBefore, snap2.pieces().size(),
            "Mutating the snapshot list must not affect the engine's board");
    }

    @Test
    void snapshotPieceView_restUntilMsIsNonZeroWhileOnCooldown() throws Exception {
        engine.requestMove(new Position(0, 0), new Position(0, 3));
        GameEngine.GameSnapshot snap = engine.snapshot();
        GameEngine.GameSnapshot.PieceView pv = snap.pieces().stream()
            .filter(p -> "Rook".equals(p.kind()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Rook not found in snapshot"));
        assertTrue(pv.restUntilMs() > snap.clock(),
            "restUntilMs must be in the future while the piece is on cooldown");
    }

    @Test
    void snapshotPieceView_restUntilMsIsZeroForIdlePiece() throws Exception {
        GameEngine.GameSnapshot snap = engine.snapshot();
        GameEngine.GameSnapshot.PieceView pv = snap.pieces().stream()
            .filter(p -> "Rook".equals(p.kind()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Rook not found in snapshot"));
        assertEquals(0L, pv.restUntilMs(),
            "restUntilMs must be 0 for a piece that has never moved");
    }

    // -------------------------------------------------------------------------
    // Score tracking (Fix 3)
    // -------------------------------------------------------------------------

    @Test
    void captureIncrementsCapturingPlayerScoreByCorrectValue() throws Exception {
        // White rook captures black rook (value=5)
        engine.getBoard().addPiece(new Position(0, 2), new Piece("Rook", "black"));
        engine.requestMove(new Position(0, 0), new Position(0, 2));
        engine.waitMs(2000);

        GameEngine.GameSnapshot snap = engine.snapshot();
        assertEquals(5, snap.scoreWhite(), "White must gain 5 points for capturing a Rook");
        assertEquals(0, snap.scoreBlack(), "Black score must remain 0");
    }

    @Test
    void nonCapturingArrivalDoesNotChangeAnyScore() throws Exception {
        engine.requestMove(new Position(0, 0), new Position(0, 3));
        engine.waitMs(2000);

        GameEngine.GameSnapshot snap = engine.snapshot();
        assertEquals(0, snap.scoreWhite(), "No capture — white score must stay 0");
        assertEquals(0, snap.scoreBlack(), "No capture — black score must stay 0");
    }

    // -------------------------------------------------------------------------
    // Move log (Fix 3)
    // -------------------------------------------------------------------------

    @Test
    void captureByWhiteProducesLogEntryAttributedToWhite() throws Exception {
        engine.getBoard().addPiece(new Position(0, 2), new Piece("Knight", "black"));
        engine.requestMove(new Position(0, 0), new Position(0, 2));
        engine.waitMs(2000);

        GameEngine.GameSnapshot snap = engine.snapshot();
        List<GameEngine.GameSnapshot.MoveLogEntry> whiteMoves = snap.moveLog().stream()
            .filter(e -> "white".equals(e.color()))
            .collect(java.util.stream.Collectors.toList());
        List<GameEngine.GameSnapshot.MoveLogEntry> blackMoves = snap.moveLog().stream()
            .filter(e -> "black".equals(e.color()))
            .collect(java.util.stream.Collectors.toList());

        assertEquals(1, whiteMoves.size(), "Exactly one white move must be logged");
        assertFalse(whiteMoves.get(0).timestamp().isEmpty(),
            "Log entry must have a non-empty timestamp");
        assertTrue(whiteMoves.get(0).isCapture(), "Entry must be marked as a capture");
        assertEquals("Knight", whiteMoves.get(0).capturedKind());
        assertTrue(blackMoves.isEmpty(), "Capture by white must not appear in black's log");
    }

    @Test
    void moveLogIsImmutable() throws Exception {
        engine.requestMove(new Position(0, 0), new Position(0, 3));
        engine.waitMs(2000);
        GameEngine.GameSnapshot snap = engine.snapshot();
        try {
            snap.moveLog().clear();
        } catch (UnsupportedOperationException ignored) {}
        GameEngine.GameSnapshot snap2 = engine.snapshot();
        assertEquals(1, snap2.moveLog().size(), "Move log must not be mutable from outside");
    }
}
