package tests.unit;

import com.kungfuchess.engine.GameEngine;
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
 * Tests for Issues 1, 3, 4, 5, 7, 8 from the gameplay bug-fix session.
 */
class TestGameplayFixes {

    // =========================================================================
    // Issue 1 — Renderer coordinate fix (setContentPane)
    // The fix is in Renderer.java (setContentPane instead of add).
    // Test: verify BoardMapper pixel→board mapping is consistent with what
    // Controller.click uses — the integration contract that the fix preserves.
    // =========================================================================

    @Test
    void boardMapper_pixelAtBoardOrigin_mapsToCell00() {
        // Board top-left pixel is (BOARD_X_OFFSET, BOARD_Y_OFFSET)
        Position p = com.kungfuchess.input.BoardMapper.pixelToBoard(
            com.kungfuchess.input.BoardMapper.BOARD_X_OFFSET,
            com.kungfuchess.input.BoardMapper.BOARD_Y_OFFSET);
        assertEquals(new Position(0, 0), p,
            "Pixel at board left edge must map to (0,0) — verifies no inset offset");
    }

    @Test
    void controllerClick_selectsKnightAtCorrectPixel_afterRendererFix() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(7, 1), new Piece("Knight", "white"));
        GameEngine engine = new GameEngine().setBoard(b);
        Controller ctrl = engine.getController();

        // col=1 → x=224+1*100+50=374; row=7 → y=65+7*100+50=815
        ctrl.click(374, 815);
        assertEquals(new Position(7, 1), ctrl.getSelected().orElse(null),
            "Knight must be selected at its correct pixel position");

        // col=2 → x=474; row=5 → y=65+5*100+50=615 — L-shape destination
        Controller.ControllerResult r = ctrl.click(474, 615);
        assertTrue(r.moveResult().isAccepted(),
            "Knight jump must be accepted via pixel-coordinate click");
    }

    // =========================================================================
    // Issue 3 — Game-over: Controller short-circuits, no sound/selection
    // =========================================================================

    @Test
    void afterGameOver_clickProducesNoMoveResult_noSoundNoSelection() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        b.addPiece(new Position(0, 2), new Piece("King", "black"));
        GameEngine engine = new GameEngine().setBoard(b);

        List<String> played = new ArrayList<>();
        SoundManager sm = new SoundManager() {
            @Override public void playMoveStart() { played.add("moveStart"); }
            @Override public void playIllegal()   { played.add("illegal"); }
        };
        Controller ctrl = engine.getController();
        ctrl.setSoundManager(sm);

        // Capture the king → game over
        engine.requestMove(new Position(0, 0), new Position(0, 2));
        engine.waitMs(5000);
        assertTrue(engine.isGameOver());

        // Now click — must be a complete no-op
        Controller.ControllerResult r = ctrl.click(250, 50); // (0,0) — now empty
        assertFalse(r.moveRequested(), "click after game-over must not request a move");
        assertTrue(ctrl.getSelected().isEmpty(), "no selection must occur after game-over");
        assertTrue(played.isEmpty(), "no sound must be played after game-over");
    }

    // =========================================================================
    // Issue 4 — rejectedDest auto-expires after 500ms of game clock
    // =========================================================================

    @Test
    void rejectedDest_presentImmediatelyAfterRejection() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("King", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(0, 0), new Position(2, 2)); // illegal
        assertNotNull(engine.snapshot().rejectedDest(),
            "rejectedDest must be non-null immediately after rejection");
        assertEquals(new Position(2, 2), engine.snapshot().rejectedDest());
    }

    @Test
    void rejectedDest_clearsAfter500msGameClock_withNoFurtherClicks() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("King", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(0, 0), new Position(2, 2)); // illegal
        assertNotNull(engine.snapshot().rejectedDest(), "must be set immediately");

        // Advance 499ms — still present
        engine.waitMs(499);
        assertNotNull(engine.snapshot().rejectedDest(),
            "rejectedDest must still be present at 499ms");

        // Advance 1 more ms (total 500ms) — must be gone
        engine.waitMs(1);
        assertNull(engine.snapshot().rejectedDest(),
            "rejectedDest must be null after 500ms of game clock with no further clicks");
    }

    // =========================================================================
    // Issue 5 — Capturing an on-cooldown piece is intentional (design assertion)
    // =========================================================================

    /**
     * INTENTIONAL DESIGN: a piece on cooldown can still be captured by an opponent.
     * Cooldown only blocks the piece's OWN outgoing moves. This is the core
     * risk/reward mechanic of Kung-Fu Chess — do not "fix" this away.
     */
    @Test
    void onCooldownPiece_isCapturableByOpponent_intentionalDesign() throws Exception {
        Board b = Board.create(8, 8);
        Piece target   = new Piece("Rook", "white");
        Piece attacker = new Piece("Rook", "black");
        b.addPiece(new Position(0, 0), target);
        b.addPiece(new Position(7, 3), attacker);
        GameEngine engine = new GameEngine().setBoard(b);

        // Put target on cooldown by starting a move (3 cells, travel ~2000ms)
        engine.requestMove(new Position(0, 0), new Position(0, 3));
        // Advance just past travel time so it lands but rest chain is still active
        engine.waitMs(2100);
        assertTrue(engine.getArbiter().isOnCooldown(target),
            "target must still be on cooldown (rest chain) after landing");

        // Attacker captures the resting target — must be accepted
        GameEngine.MoveResult r = engine.requestMove(new Position(7, 3), new Position(0, 3));
        assertTrue(r.isAccepted(),
            "opponent must be able to capture a piece that is on cooldown — intentional design");

        engine.waitMs(5000);
        Piece atDest = b.pieceAt(new Position(0, 3)).orElse(null);
        assertNotNull(atDest);
        assertEquals("black", atDest.getColor(),
            "attacker must have captured the resting piece");
    }

    // =========================================================================
    // Issue 7 — Destination reservation
    // =========================================================================

    @Test
    void destinationReserved_secondMoveToSameSquare_isRejected() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        b.addPiece(new Position(7, 3), new Piece("Rook", "black"));
        GameEngine engine = new GameEngine().setBoard(b);

        // First motion targets (0,3)
        GameEngine.MoveResult r1 = engine.requestMove(new Position(0, 0), new Position(0, 3));
        assertTrue(r1.isAccepted(), "first move to (0,3) must be accepted");

        // Second motion also targets (0,3) — must be rejected
        GameEngine.MoveResult r2 = engine.requestMove(new Position(7, 3), new Position(0, 3));
        assertFalse(r2.isAccepted(), "second move to reserved destination must be rejected");
        assertEquals(GameEngine.MoveResult.DESTINATION_RESERVED, r2.reason(),
            "reason must be destination_reserved");
    }

    @Test
    void destinationReserved_becomesLegalAfterFirstMotionResolves() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        b.addPiece(new Position(7, 3), new Piece("Rook", "black"));
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(0, 0), new Position(0, 3));
        // Resolve the first motion
        engine.waitMs(5000);

        // Now (0,3) is occupied by white rook; black rook can capture it
        GameEngine.MoveResult r = engine.requestMove(new Position(7, 3), new Position(0, 3));
        assertTrue(r.isAccepted(),
            "move to formerly-reserved destination must be accepted after first motion resolves");
    }

    // =========================================================================
    // Issue 8 — cooldownStart reset to landing time (rest-chain fraction starts at 0%)
    // =========================================================================

    @Test
    void cooldownStartMs_isResetToLandingTime_notDepartureTime() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        // Start motion at clock=0
        engine.requestMove(new Position(0, 0), new Position(0, 3));
        // Travel time = round(3 / 1.5 * 1000 * SPEED_MULTIPLIER) = round(2000 * 0.70) = 1400ms
        long landingMs = Math.round(2000L * com.kungfuchess.realtime.RealTimeArbiter.SPEED_MULTIPLIER);
        engine.waitMs(landingMs);

        // cooldownStart must now equal the landing time, not 0
        Piece rook = b.pieceAt(new Position(0, 3)).orElse(null);
        assertNotNull(rook, "rook must have landed at (0,3)");
        long startMs = engine.getArbiter().cooldownStartMs(rook);
        assertEquals(landingMs, startMs,
            "cooldownStart must be reset to the landing time, not departure time (0ms)");
    }

    @Test
    void cooldownFraction_isNearZeroImmediatelyAfterLanding() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(0, 0), new Position(0, 3));
        long landingMs = Math.round(2000L * com.kungfuchess.realtime.RealTimeArbiter.SPEED_MULTIPLIER);
        engine.waitMs(landingMs); // land exactly

        Piece rook = b.pieceAt(new Position(0, 3)).orElse(null);
        assertNotNull(rook);
        long until = engine.getArbiter().cooldownUntilMs(rook);
        long start = engine.getArbiter().cooldownStartMs(rook);
        long clock = engine.getArbiter().getClock();

        assertTrue(until > clock, "cooldown must still be active just after landing");
        double fraction = (double)(until - clock) / (double)(until - start);
        assertEquals(1.0, fraction, 0.001,
            "cooldown fraction must be ~1.0 (100% remaining) immediately after landing");
    }
}
