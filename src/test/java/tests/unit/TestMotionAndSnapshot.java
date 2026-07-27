package tests.unit;

import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import com.kungfuchess.realtime.Motion;
import com.kungfuchess.realtime.RealTimeArbiter;
import com.kungfuchess.view.ImageView;
import com.kungfuchess.view.PieceState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Motion timing, GameSnapshot.clock(), ArrivalEvents, pawn promotion,
 * Knight jump protection, and snapshot isolation (Fix 2/3/4/5).
 */
class TestMotionAndSnapshot {

    private RealTimeArbiter arbiter;
    private Board board;

    @BeforeEach
    void setUp() {
        arbiter = new RealTimeArbiter();
        board   = Board.create(8, 8);
    }

    // -------------------------------------------------------------------------
    // Motion timing
    // -------------------------------------------------------------------------

    @Test
    void motionStartTimeEqualsClockAtCreation() throws Exception {
        Piece rook = new Piece("Rook", "white");
        board.addPiece(new Position(0, 0), rook);
        arbiter.advanceTime(500, board);
        arbiter.startMotion(rook, new Position(0, 0), new Position(0, 3));

        Motion m = arbiter.getPendingMotions().get(0);
        assertEquals(500L, m.getStartTime(),
            "startTime must equal the clock value at the moment startMotion was called");
    }

    @Test
    void motionDueTimeIsStartTimePlusTravelTime() throws Exception {
        Piece rook = new Piece("Rook", "white");
        board.addPiece(new Position(0, 0), rook);
        arbiter.advanceTime(200, board);
        arbiter.startMotion(rook, new Position(0, 0), new Position(0, 3));

        Motion m = arbiter.getPendingMotions().get(0);
        // travel = round(3 / 1.5 * 1000 * SPEED_MULTIPLIER) = round(2000 * 0.70) = 1400ms
        long expectedTravel = Math.round(2000L * RealTimeArbiter.SPEED_MULTIPLIER);
        assertEquals(m.getStartTime() + expectedTravel, m.getDueTime());
    }

    @Test
    void snapshotClockReflectsArbiterClock() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        GameEngine engine = new GameEngine().setBoard(b);
        engine.waitMs(750);
        assertEquals(750L, engine.snapshot().clock());
    }

    // -------------------------------------------------------------------------
    // ArrivalEvents
    // -------------------------------------------------------------------------

    @Test
    void waitMsReturnsArrivalEventsWithLanding() throws Exception {
        Board b = Board.create(8, 8);
        Piece rook = new Piece("Rook", "white");
        b.addPiece(new Position(0, 0), rook);
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(0, 0), new Position(0, 2));
        RealTimeArbiter.ArrivalEvents events = engine.waitMs(2000);

        assertEquals(1, events.arrivals().size());
        assertNull(events.arrivals().get(0).capturedPiece(), "no capture expected");
    }

    @Test
    void waitMsReturnsArrivalEventsWithCapture() throws Exception {
        Board b = Board.create(8, 8);
        Piece attacker = new Piece("Rook", "white");
        Piece defender = new Piece("Rook", "black");
        b.addPiece(new Position(0, 0), attacker);
        b.addPiece(new Position(0, 2), defender);
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(0, 0), new Position(0, 2));
        RealTimeArbiter.ArrivalEvents events = engine.waitMs(2000);

        assertEquals(1, events.arrivals().size());
        assertEquals(defender, events.arrivals().get(0).capturedPiece());
    }

    @Test
    void waitMsReturnsEmptyEventsWhenNothingLands() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(0, 0), new Position(0, 3)); // 3 cells / 1.5 = 2000ms
        RealTimeArbiter.ArrivalEvents events = engine.waitMs(500);   // not yet arrived

        assertTrue(events.arrivals().isEmpty());
    }

    // -------------------------------------------------------------------------
    // Fix 3: Knight jump — isJump flag and landing protection
    // -------------------------------------------------------------------------

    @Test
    void knightMotionHasIsJumpTrue() throws Exception {
        Piece knight = new Piece("Knight", "white");
        board.addPiece(new Position(3, 3), knight);
        arbiter.startMotion(knight, new Position(3, 3), new Position(1, 4));

        Motion m = arbiter.getPendingMotions().get(0);
        assertTrue(m.isJump(), "Knight motion must have isJump=true");
    }

    @Test
    void rookMotionHasIsJumpFalse() throws Exception {
        Piece rook = new Piece("Rook", "white");
        board.addPiece(new Position(0, 0), rook);
        arbiter.startMotion(rook, new Position(0, 0), new Position(0, 3));

        Motion m = arbiter.getPendingMotions().get(0);
        assertFalse(m.isJump(), "Rook motion must have isJump=false");
    }

    @Test
    void knightIsCapturableImmediatelyAfterLanding() throws Exception {
        // Fix 1: a Knight that has landed must be capturable like any other stationary piece.
        // The old test asserted landing-immunity (a bug); this replacement proves the fix.
        Piece knight   = new Piece("Knight", "white");
        Piece attacker = new Piece("Rook",   "black");
        board.addPiece(new Position(3, 3), knight);
        board.addPiece(new Position(1, 0), attacker);

        // Knight jumps to (1,4); let it land fully
        arbiter.startMotion(knight, new Position(3, 3), new Position(1, 4));
        arbiter.advanceTime(5000, board); // well past travel + rest chain

        // Knight is now stationary at (1,4). Attacker captures it.
        arbiter.startMotion(attacker, new Position(1, 0), new Position(1, 4));
        arbiter.advanceTime(5000, board);

        // Attacker must have landed and captured the Knight
        assertTrue(board.pieceAt(new Position(1, 4)).isPresent(),
            "Attacker must be present at the Knight's former square after capture");
        assertEquals(attacker, board.pieceAt(new Position(1, 4)).orElse(null),
            "The piece at the destination must be the attacker, not the Knight");
    }

    // -------------------------------------------------------------------------
    // Fix 4: pawn double-step end-to-end through GameEngine
    // -------------------------------------------------------------------------

    @Test
    void pawnDoubleStepIsAcceptedOnFirstMove() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(6, 0), new Piece("Pawn", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        // hasMoved=false (fresh pawn) — double-step must be accepted
        GameEngine.MoveResult result =
            engine.requestMove(new Position(6, 0), new Position(4, 0));
        assertTrue(result.isAccepted(), "Pawn double-step must be accepted on first move");
    }

    @Test
    void pawnDoubleStepIsRejectedAfterFirstMove() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(6, 0), new Piece("Pawn", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        // First move: single step
        engine.requestMove(new Position(6, 0), new Position(5, 0));
        engine.waitMs(5000); // let it land and cooldown expire

        // Second move: attempt double-step — must be rejected
        GameEngine.MoveResult result =
            engine.requestMove(new Position(5, 0), new Position(3, 0));
        assertFalse(result.isAccepted(),
            "Pawn double-step must be rejected after the pawn has already moved");
        assertEquals("illegal_piece_move", result.reason());
    }

    @Test
    void pawnDoubleStepIsRejectedWhenIntermediateSquareIsOccupied() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(6, 0), new Piece("Pawn", "white"));
        b.addPiece(new Position(5, 0), new Piece("Pawn", "black")); // blocker
        GameEngine engine = new GameEngine().setBoard(b);

        GameEngine.MoveResult result =
            engine.requestMove(new Position(6, 0), new Position(4, 0));
        assertFalse(result.isAccepted(),
            "Pawn double-step must be rejected when intermediate square is occupied");
    }

    // -------------------------------------------------------------------------
    // Fix 5: pawn promotion
    // -------------------------------------------------------------------------

    @Test
    void pawnThatReachesLastRowBecomesAQueen() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(1, 0), new Piece("Pawn", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(1, 0), new Position(0, 0));
        engine.waitMs(2000);

        Piece arrived = b.pieceAt(new Position(0, 0)).orElse(null);
        assertNotNull(arrived, "A piece must be at row 0 after the pawn arrives");
        assertEquals("Queen", arrived.getKind(),
            "Pawn must be promoted to Queen on reaching the last row");
        assertEquals("white", arrived.getColor());
    }

    @Test
    void pawnThatDoesNotReachLastRowStaysAPawn() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(3, 0), new Piece("Pawn", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(3, 0), new Position(2, 0));
        engine.waitMs(2000);

        Piece arrived = b.pieceAt(new Position(2, 0)).orElse(null);
        assertNotNull(arrived);
        assertEquals("Pawn", arrived.getKind(),
            "Pawn must remain a Pawn when it does not reach the last row");
    }

    // -------------------------------------------------------------------------
    // Fix 2: snapshot isolation
    // -------------------------------------------------------------------------

    @Test
    void snapshotPiecesListIsImmutable() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        GameEngine.GameSnapshot snap = engine.snapshot();
        int sizeBefore = snap.pieces().size();
        try {
            snap.pieces().clear();
        } catch (UnsupportedOperationException ignored) {}

        GameEngine.GameSnapshot snap2 = engine.snapshot();
        assertEquals(sizeBefore, snap2.pieces().size(),
            "Mutating the snapshot pieces list must not affect the engine's board");
    }

    @Test
    void snapshotPiecesAreValueRecords_notLiveDomainObjects() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        GameEngine.GameSnapshot snap = engine.snapshot();
        GameEngine.GameSnapshot.PieceView pv = snap.pieces().get(0);

        assertEquals("Rook",  pv.kind());
        assertEquals("white", pv.color());
        assertEquals(new Position(0, 0), pv.position());
    }

    // -------------------------------------------------------------------------
    // Fix 2: animator key — after landing, destination key is in rest state
    // -------------------------------------------------------------------------

    @Test
    void afterMotionResolves_animatorAtDestinationKeyIsInRestState() throws Exception {
        // Verifies Fix 2: the animator stored under "code@toPosition" (the key drawBoard
        // uses for stationary pieces) is in a non-idle rest state after landing, not
        // "idle" (which would mean a brand-new animator was lazily created instead).
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        ImageView view = new ImageView();

        // Frame 1: piece is in-flight — animator registered under from-key
        engine.requestMove(new Position(0, 0), new Position(0, 3));
        view.updateAnimatorsOnly(engine.snapshot());

        // Advance past travel time so the piece lands
        engine.waitMs(3000);

        // Frame 2: piece has landed — updateAnimatorStates must re-key to destination
        view.updateAnimatorsOnly(engine.snapshot());

        // The animator at the destination key must NOT be in "idle" state
        String destKey = "wR@" + new Position(0, 3);
        String state = view.animatorStateForKey(destKey);
        assertNotEquals(PieceState.IDLE.folderName, state,
            "After landing, the animator at the destination key must be in a rest state, "
            + "not idle (which would indicate a lazily-created fresh animator)");
    }
}
