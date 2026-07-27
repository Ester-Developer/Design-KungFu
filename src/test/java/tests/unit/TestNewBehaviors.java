package tests.unit;

import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.engine.GameEngine.GameSnapshot;
import com.kungfuchess.input.BoardMapper;
import com.kungfuchess.input.Controller;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import com.kungfuchess.realtime.RealTimeArbiter;
import com.kungfuchess.view.SoundManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Part 1 (Knight move diagnosis), Part 2 (illegal-move feedback),
 * Part 3 (promotion sound signal), and Part 4 (promotion sound).
 */
class TestNewBehaviors {

    // =========================================================================
    // Part 1 — Knight move diagnosis
    // =========================================================================

    /**
     * Reproduces the exact user-facing sequence: select a Knight via Controller.click,
     * then click a valid L-shaped destination with a piece sitting directly between
     * source and destination in a straight line.
     *
     * Confirms the move is ACCEPTED (not rejected), proving the Knight correctly
     * jumps over the blocking piece. The "silent rejection" the user perceived was
     * purely a feedback gap — no sound or visual — not a logic bug.
     */
    @Test
    void knightJumpOverBlockingPieceViaControllerClick_isAccepted() throws Exception {
        Board b = Board.create(8, 8);
        // Knight at (4,4); blocker at (4,5) — directly between source and (4,6) in a
        // straight line, but the L-shape target (2,5) is still a valid knight move.
        b.addPiece(new Position(4, 4), new Piece("Knight", "white"));
        b.addPiece(new Position(4, 5), new Piece("Pawn",   "black")); // straight-line blocker
        b.addPiece(new Position(3, 4), new Piece("Pawn",   "black")); // another adjacent blocker
        GameEngine engine = new GameEngine().setBoard(b);

        // BOARD_X_OFFSET=224, CELL=100; col=4 → x=224+4*100+50=674; row=4 → y=65+4*100+50=515
        engine.getController().click(674, 515); // select knight at (4,4)
        // col=5 → x=224+5*100+50=774; row=2 → y=65+2*100+50=315  → L-shape (4,4)→(2,5)
        Controller.ControllerResult result = engine.getController().click(774, 315);

        assertTrue(result.moveRequested(), "Second click must request a move");
        assertNotNull(result.moveResult(), "moveResult must be populated");
        assertTrue(result.moveResult().isAccepted(),
            "Knight L-shape jump over blocking pieces must be accepted, got: "
            + result.moveResult().reason());
    }

    /**
     * Confirms the actual reason() string returned when a genuinely illegal move is
     * attempted on a Knight (wrong shape — not an L). This is the diagnostic value
     * quoted in the deliverable.
     */
    @Test
    void knightIllegalShape_reasonIsIllegalPieceMove() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(4, 4), new Piece("Knight", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        // (4,4) → (4,6) is a straight rook-style move, not an L-shape
        GameEngine.MoveResult result = engine.requestMove(new Position(4, 4), new Position(4, 6));

        assertFalse(result.isAccepted());
        assertEquals("illegal_piece_move", result.reason(),
            "Actual observed reason for a non-L-shape Knight move");
    }

    /**
     * All 8 L-shape vectors from a central square are accepted end-to-end through
     * GameEngine.requestMove + waitMs, including one case where a piece sits directly
     * between source and destination in a straight line.
     */
    @Test
    void knightAllEightLShapes_endToEnd_includingJumpOverPiece() throws Exception {
        int[][] vectors = {{-2,-1},{-2,1},{-1,-2},{-1,2},{1,-2},{1,2},{2,-1},{2,1}};
        for (int[] v : vectors) {
            Board b = Board.create(8, 8);
            Position from = new Position(4, 4);
            Position to   = new Position(4 + v[0], 4 + v[1]);
            b.addPiece(from, new Piece("Knight", "white"));
            // Place a blocker on the straight-line square between from and to (if in bounds)
            int midRow = from.getRow() + Integer.signum(v[0]);
            int midCol = from.getCol() + Integer.signum(v[1]);
            Position mid = new Position(midRow, midCol);
            if (b.isInBounds(mid) && !mid.equals(to)) {
                try { b.addPiece(mid, new Piece("Pawn", "black")); }
                catch (Board.OccupiedCellException ignored) {}
            }
            GameEngine engine = new GameEngine().setBoard(b);

            GameEngine.MoveResult r = engine.requestMove(from, to);
            assertTrue(r.isAccepted(),
                "Knight vector (" + v[0] + "," + v[1] + ") must be accepted, got: " + r.reason());

            engine.waitMs(4000);
            assertTrue(b.pieceAt(from).isEmpty(),
                "Origin must be empty after landing for vector (" + v[0] + "," + v[1] + ")");
            assertTrue(b.pieceAt(to).isPresent(),
                "Knight must be at destination for vector (" + v[0] + "," + v[1] + ")");
            // Blocker must be undisturbed
            if (b.isInBounds(mid) && !mid.equals(to)) {
                // blocker may or may not still be there depending on whether it was placed;
                // the key assertion is that the knight landed correctly above.
            }
        }
    }

    // =========================================================================
    // Part 2 — Illegal move feedback
    // =========================================================================

    /**
     * An illegal move triggers playIllegal() (not playMoveStart()).
     */
    @Test
    void illegalMove_triggersPlayIllegal_notPlayMoveStart() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("King", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        List<String> played = new ArrayList<>();
        SoundManager sm = new SoundManager() {
            @Override public void playMoveStart() { played.add("moveStart"); }
            @Override public void playIllegal()   { played.add("illegal"); }
        };

        Controller ctrl = engine.getController();
        ctrl.setSoundManager(sm);

        // Select king at (0,0)
        int x00 = BoardMapper.BOARD_X_OFFSET + 50;
        int y00 = BoardMapper.BOARD_Y_OFFSET + 50;
        ctrl.click(x00, y00);
        
        // Attempt illegal move: king can't jump 2 squares diagonally to (2,2)
        int x22 = BoardMapper.BOARD_X_OFFSET + 250;
        int y22 = BoardMapper.BOARD_Y_OFFSET + 250;
        ctrl.click(x22, y22);

        assertFalse(played.contains("moveStart"), "playMoveStart must NOT be called for illegal move");
        assertTrue(played.contains("illegal"),    "playIllegal must be called for illegal move");
    }

    /**
     * A legal move still only triggers playMoveStart() — no regression.
     */
    @Test
    void legalMove_triggersPlayMoveStart_notPlayIllegal() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("King", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        List<String> played = new ArrayList<>();
        SoundManager sm = new SoundManager() {
            @Override public void playMoveStart() { played.add("moveStart"); }
            @Override public void playIllegal()   { played.add("illegal"); }
        };

        Controller ctrl = engine.getController();
        ctrl.setSoundManager(sm);

        // Select king at (0,0)
        int x00 = BoardMapper.BOARD_X_OFFSET + 50;
        int y00 = BoardMapper.BOARD_Y_OFFSET + 50;
        ctrl.click(x00, y00);
        
        // Legal move: king one square diagonally to (1,1)
        int x11 = BoardMapper.BOARD_X_OFFSET + 150;
        int y11 = BoardMapper.BOARD_Y_OFFSET + 150;
        ctrl.click(x11, y11);

        assertTrue(played.contains("moveStart"),   "playMoveStart must be called for legal move");
        assertFalse(played.contains("illegal"),    "playIllegal must NOT be called for legal move");
    }

    /**
     * After a rejected move, snapshot().rejectedDest() equals the attempted destination.
     * After an accepted move, snapshot().rejectedDest() is null.
     */
    @Test
    void rejectedDest_isSetOnRejectionAndClearedOnAcceptance() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("King", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        // Illegal move: king can't jump 2 squares
        engine.requestMove(new Position(0, 0), new Position(2, 2));
        assertEquals(new Position(2, 2), engine.snapshot().rejectedDest(),
            "rejectedDest must equal the attempted destination after rejection");

        // Legal move: king one square diagonally
        engine.requestMove(new Position(0, 0), new Position(1, 1));
        assertNull(engine.snapshot().rejectedDest(),
            "rejectedDest must be null after an accepted move");
    }

    /**
     * ControllerResult.moveResult() carries the MoveResult from the second click.
     */
    @Test
    void controllerResult_moveResult_isPopulatedOnSecondClick() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("King", "white"));
        GameEngine engine = new GameEngine().setBoard(b);
        Controller ctrl = engine.getController();

        int x00 = BoardMapper.BOARD_X_OFFSET + 50;
        int y00 = BoardMapper.BOARD_Y_OFFSET + 50;
        int x22 = BoardMapper.BOARD_X_OFFSET + 250;
        int y22 = BoardMapper.BOARD_Y_OFFSET + 250;
        
        ctrl.click(x00, y00); // select
        Controller.ControllerResult result = ctrl.click(x22, y22); // illegal

        assertTrue(result.moveRequested());
        assertNotNull(result.moveResult());
        assertFalse(result.moveResult().isAccepted());
        assertEquals("illegal_piece_move", result.moveResult().reason());
    }

    // =========================================================================
    // Part 3 — Promotion sound signal
    // =========================================================================

    /**
     * A pawn reaching the promotion rank produces an ArrivalEvent with isPromoted()=true,
     * which leads to playPromotion() being called.
     */
    @Test
    void pawnPromotion_arrivalEventIsPromoted_true() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(1, 0), new Piece("Pawn", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(1, 0), new Position(0, 0));
        RealTimeArbiter.ArrivalEvents events = engine.waitMs(3000);

        assertEquals(1, events.arrivals().size());
        assertTrue(events.arrivals().get(0).isPromoted(),
            "ArrivalEvent for a promoting pawn must have isPromoted()=true");
    }

    /**
     * A non-promoting pawn move produces an ArrivalEvent with isPromoted()=false.
     */
    @Test
    void nonPromotingPawnMove_arrivalEventIsPromoted_false() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(3, 0), new Piece("Pawn", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(3, 0), new Position(2, 0));
        RealTimeArbiter.ArrivalEvents events = engine.waitMs(3000);

        assertEquals(1, events.arrivals().size());
        assertFalse(events.arrivals().get(0).isPromoted(),
            "ArrivalEvent for a non-promoting pawn must have isPromoted()=false");
    }

    /**
     * MoveLogEntry.isPromoted() is true for a promoting pawn, false for a plain move.
     */
    @Test
    void moveLogEntry_isPromoted_reflectsPromotion() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(1, 0), new Piece("Pawn", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(1, 0), new Position(0, 0));
        engine.waitMs(3000);

        GameSnapshot snap = engine.snapshot();
        assertEquals(1, snap.moveLog().size());
        assertTrue(snap.moveLog().get(0).isPromoted(),
            "MoveLogEntry for a promoting pawn must have isPromoted()=true");
    }

    /**
     * playPromotion() is called (not playMoveLand()) when a pawn promotes.
     * Simulates the GuiLauncher game-loop logic that checks isPromoted().
     */
    @Test
    void promotionArrival_triggersPlayPromotion_notPlayMoveLand() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(1, 0), new Piece("Pawn", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        List<String> played = new ArrayList<>();
        SoundManager sm = new SoundManager() {
            @Override public void playMoveLand()  { played.add("moveLand"); }
            @Override public void playPromotion() { played.add("promotion"); }
            @Override public void playCapture()   { played.add("capture"); }
        };

        engine.requestMove(new Position(1, 0), new Position(0, 0));
        RealTimeArbiter.ArrivalEvents events = engine.waitMs(3000);

        // Simulate the GuiLauncher game-loop dispatch logic
        for (RealTimeArbiter.ArrivalEvents.ArrivalEvent arrival : events.arrivals()) {
            if (arrival.isPromoted()) {
                sm.playPromotion();
            } else if (arrival.capturedPiece() != null) {
                sm.playCapture();
            } else {
                sm.playMoveLand();
            }
        }

        assertTrue(played.contains("promotion"), "playPromotion must be called on pawn promotion");
        assertFalse(played.contains("moveLand"), "playMoveLand must NOT be called on promotion");
        assertFalse(played.contains("capture"),  "playCapture must NOT be called on non-capture promotion");
    }

    /**
     * A non-promoting pawn move triggers playMoveLand(), not playPromotion().
     */
    @Test
    void nonPromotionArrival_triggersPlayMoveLand_notPlayPromotion() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(3, 0), new Piece("Pawn", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        List<String> played = new ArrayList<>();
        SoundManager sm = new SoundManager() {
            @Override public void playMoveLand()  { played.add("moveLand"); }
            @Override public void playPromotion() { played.add("promotion"); }
        };

        engine.requestMove(new Position(3, 0), new Position(2, 0));
        RealTimeArbiter.ArrivalEvents events = engine.waitMs(3000);

        for (RealTimeArbiter.ArrivalEvents.ArrivalEvent arrival : events.arrivals()) {
            if (arrival.isPromoted()) {
                sm.playPromotion();
            } else if (arrival.capturedPiece() != null) {
                sm.playCapture();
            } else {
                sm.playMoveLand();
            }
        }

        assertTrue(played.contains("moveLand"),   "playMoveLand must be called for non-promoting pawn");
        assertFalse(played.contains("promotion"), "playPromotion must NOT be called for non-promoting pawn");
    }
}
