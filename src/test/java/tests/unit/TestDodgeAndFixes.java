package tests.unit;

import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.input.Controller;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import com.kungfuchess.realtime.Motion;
import com.kungfuchess.realtime.RealTimeArbiter;
import com.kungfuchess.view.SoundManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Issue 1 (Dodge), Issue 2 (source reservation), Issue 3 (cooldown first-click).
 * Issue 4 is a rendering-only change with no testable logic contract beyond compile.
 */
class TestDodgeAndFixes {

    // =========================================================================
    // Issue 1 — Dodge: rejected with no_active_threat when no enemy motion targets square
    // =========================================================================

    @Test
    void dodge_rejectedWithNoActiveThreat_whenNoEnemyMotionTargetsSquare() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(4, 4), new Piece("Rook", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        GameEngine.MoveResult r = engine.requestDodge(new Position(4, 4));
        assertFalse(r.isAccepted());
        assertEquals(GameEngine.MoveResult.NO_ACTIVE_THREAT, r.reason());
    }

    // =========================================================================
    // Issue 1 — Dodge: rejected with piece_on_cooldown even with active threat
    // =========================================================================

    @Test
    void dodge_rejectedWithPieceOnCooldown_evenWithActiveThreat() throws Exception {
        Board b = Board.create(8, 8);
        Piece defender = new Piece("Rook", "white");
        Piece attacker = new Piece("Rook", "black");
        b.addPiece(new Position(4, 4), defender);
        b.addPiece(new Position(4, 0), attacker);
        GameEngine engine = new GameEngine().setBoard(b);

        // Put defender on cooldown by starting a move
        engine.requestMove(new Position(4, 4), new Position(4, 7));
        // Advance just past travel so it lands but rest chain is still active
        engine.waitMs(2100);
        assertTrue(engine.getArbiter().isOnCooldown(
            b.pieceAt(new Position(4, 7)).orElseThrow()));

        // Start attacker motion toward (4,7) — now there IS an active threat
        engine.requestMove(new Position(4, 0), new Position(4, 7));

        // Defender (now at 4,7) is on cooldown — dodge must be rejected
        GameEngine.MoveResult r = engine.requestDodge(new Position(4, 7));
        assertFalse(r.isAccepted());
        assertEquals(GameEngine.MoveResult.PIECE_ON_COOLDOWN, r.reason());
    }

    // =========================================================================
    // Issue 1 — Dodge: full end-to-end scenario (Rook attacker)
    // =========================================================================

    @Test
    void dodge_endToEnd_attackerCapturedByDefender_scoreAndLogUpdated() throws Exception {
        Board b = Board.create(8, 8);
        Piece defender = new Piece("Rook", "white");
        Piece attacker = new Piece("Rook", "black");
        Position defPos = new Position(4, 4);
        Position atkStart = new Position(4, 0);
        b.addPiece(defPos, defender);
        b.addPiece(atkStart, attacker);
        GameEngine engine = new GameEngine().setBoard(b);

        // Attacker starts moving toward (4,4) — 4 cells at 1.5 m/s = ~2667ms
        GameEngine.MoveResult atkResult = engine.requestMove(atkStart, defPos);
        assertTrue(atkResult.isAccepted(), "attacker move must be accepted");

        // Find the attacker's motion to know its dueTime
        Motion threat = null;
        for (Motion m : engine.getArbiter().getPendingMotions()) {
            if (m.getPiece() == attacker) { threat = m; break; }
        }
        assertNotNull(threat);
        long attackerDue = threat.getDueTime();

        // Defender triggers Dodge before attacker arrives
        GameEngine.MoveResult dodgeResult = engine.requestDodge(defPos);
        assertTrue(dodgeResult.isAccepted(), "dodge must be accepted: " + dodgeResult.reason());

        // Verify dodge motion exists with dueTime = attackerDue + DODGE_BUFFER_MS
        Motion dodgeMotion = null;
        for (Motion m : engine.getArbiter().getPendingMotions()) {
            if (m.isDodge() && m.getPiece() == defender) { dodgeMotion = m; break; }
        }
        assertNotNull(dodgeMotion, "dodge motion must be registered");
        assertEquals(attackerDue + RealTimeArbiter.DODGE_BUFFER_MS, dodgeMotion.getDueTime());

        // Advance to just after attacker arrives but before dodge resolves
        engine.waitMs(attackerDue + 1);

        // Attacker occupies (4,4); defender piece still exists (not captured)
        Piece atSquare = b.pieceAt(defPos).orElse(null);
        assertNotNull(atSquare, "attacker must occupy the square after arriving");
        assertEquals("black", atSquare.getColor(), "attacker (black) must be at the square");

        // Defender piece instance must still be alive (referenced by pending dodge motion)
        boolean defenderStillPending = false;
        for (Motion m : engine.getArbiter().getPendingMotions()) {
            if (m.isDodge() && m.getPiece() == defender) { defenderStillPending = true; break; }
        }
        assertTrue(defenderStillPending, "defender's dodge motion must still be pending");

        // Advance past dodge resolution
        engine.waitMs(RealTimeArbiter.DODGE_BUFFER_MS + 50);

        // Defender must now occupy (4,4), attacker captured
        Piece finalOccupant = b.pieceAt(defPos).orElse(null);
        assertNotNull(finalOccupant);
        assertEquals("white", finalOccupant.getColor(), "defender must occupy square after dodge resolves");
        assertSame(defender, finalOccupant, "must be the exact same defender piece instance");

        // Score: white captured black Rook (value 5)
        assertEquals(5, engine.snapshot().scoreWhite(), "white score must reflect captured Rook");

        // Move log: dodge entry must be present
        boolean dodgeInLog = engine.snapshot().moveLog().stream()
            .anyMatch(e -> e.isDodge() && "white".equals(e.color()));
        assertTrue(dodgeInLog, "move log must contain a dodge entry for white");
    }

    // =========================================================================
    // Issue 1 — Dodge: same scenario with Knight as attacker (no directional logic)
    // =========================================================================

    @Test
    void dodge_endToEnd_knightAttacker_identicalOutcome() throws Exception {
        Board b = Board.create(8, 8);
        Piece defender = new Piece("Rook", "white");
        Piece knight   = new Piece("Knight", "black");
        Position defPos    = new Position(4, 4);
        Position knightPos = new Position(2, 3); // L-shape to (4,4): +2 rows, +1 col
        b.addPiece(defPos, defender);
        b.addPiece(knightPos, knight);
        GameEngine engine = new GameEngine().setBoard(b);

        GameEngine.MoveResult atkResult = engine.requestMove(knightPos, defPos);
        assertTrue(atkResult.isAccepted(), "knight move must be accepted");

        Motion threat = null;
        for (Motion m : engine.getArbiter().getPendingMotions()) {
            if (m.getPiece() == knight) { threat = m; break; }
        }
        assertNotNull(threat);
        long attackerDue = threat.getDueTime();

        GameEngine.MoveResult dodgeResult = engine.requestDodge(defPos);
        assertTrue(dodgeResult.isAccepted(), "dodge vs Knight must be accepted: " + dodgeResult.reason());

        // Advance past both resolutions
        engine.waitMs(attackerDue + RealTimeArbiter.DODGE_BUFFER_MS + 100);

        Piece finalOccupant = b.pieceAt(defPos).orElse(null);
        assertNotNull(finalOccupant);
        assertEquals("white", finalOccupant.getColor(), "defender must win the dodge vs Knight");
        assertEquals(3, engine.snapshot().scoreWhite(), "white score must reflect captured Knight (value 3)");
    }

    // =========================================================================
    // Issue 1 — Dodge: third unrelated piece targeting same square is still rejected
    // =========================================================================

    @Test
    void dodge_thirdPieceTargetingSameSquare_stillRejectedByDestinationReservation() throws Exception {
        Board b = Board.create(8, 8);
        Piece defender  = new Piece("Rook", "white");
        Piece attacker  = new Piece("Rook", "black");
        Piece bystander = new Piece("Rook", "black");
        Position defPos      = new Position(4, 4);
        Position atkStart    = new Position(4, 0);
        Position bystanderPos = new Position(0, 4);
        b.addPiece(defPos, defender);
        b.addPiece(atkStart, attacker);
        b.addPiece(bystanderPos, bystander);
        GameEngine engine = new GameEngine().setBoard(b);

        // Attacker starts toward (4,4)
        assertTrue(engine.requestMove(atkStart, defPos).isAccepted());
        // Defender dodges
        assertTrue(engine.requestDodge(defPos).isAccepted());

        // Bystander also tries to target (4,4) — must be rejected (destination reserved)
        GameEngine.MoveResult r = engine.requestMove(bystanderPos, defPos);
        assertFalse(r.isAccepted(), "third piece must not be able to target the contested square");
        assertEquals(GameEngine.MoveResult.DESTINATION_RESERVED, r.reason());
    }

    // =========================================================================
    // Issue 1 — Dodge: double-clicking with no active threat is still a no-op/rejection
    // =========================================================================

    @Test
    void dodge_doubleClickNoThreat_behavesAsBeforeNoRegression() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(4, 4), new Piece("Rook", "white"));
        GameEngine engine = new GameEngine().setBoard(b);
        Controller ctrl = engine.getController();

        // First click: select the piece at (4,4) — pixel: x=224+4*100+50=674, y=65+4*100+50=515
        ctrl.click(674, 515);
        assertEquals(new Position(4, 4), ctrl.getSelected().orElse(null));

        // Second click on same square: dodge attempted, rejected (no threat), selection cleared
        Controller.ControllerResult r = ctrl.click(674, 515);
        assertTrue(r.moveRequested(), "second same-square click must produce a move request");
        assertFalse(r.moveResult().isAccepted(), "must be rejected (no active threat)");
        assertEquals(GameEngine.MoveResult.NO_ACTIVE_THREAT, r.moveResult().reason());
        assertTrue(ctrl.getSelected().isEmpty(), "selection must be cleared after second click");
    }

    // =========================================================================
    // Issue 2 — Source square freed immediately when motion starts
    // =========================================================================

    @Test
    void sourceSquare_isImmediatelyFreeForOtherPieces_whenMotionStarts() throws Exception {
        Board b = Board.create(8, 8);
        Piece rook1 = new Piece("Rook", "white");
        Piece rook2 = new Piece("Rook", "black");
        b.addPiece(new Position(0, 0), rook1);
        b.addPiece(new Position(7, 0), rook2); // same column
        GameEngine engine = new GameEngine().setBoard(b);

        // Start rook1 moving from (0,0) to (0,7)
        assertTrue(engine.requestMove(new Position(0, 0), new Position(0, 7)).isAccepted());

        // Board still shows rook1 at (0,0) (motion hasn't resolved yet)
        assertTrue(b.pieceAt(new Position(0, 0)).isPresent(),
            "board still shows piece at source before motion resolves");

        // rook2 (black) moves TO (0,0) — must now be ACCEPTED (source no longer reserved)
        GameEngine.MoveResult toSource = engine.requestMove(new Position(7, 0), new Position(0, 0));
        assertTrue(toSource.isAccepted(),
            "move TO the in-flight source must be accepted — source freed immediately");
    }

    @Test
    void sourceSquare_afterMotionResolves_boardIsCorrect() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(0, 0), new Position(0, 7));
        engine.waitMs(5000); // resolve motion
        assertTrue(b.pieceAt(new Position(0, 0)).isEmpty(),
            "source square must be empty after motion resolves");
        assertTrue(b.pieceAt(new Position(0, 7)).isPresent(),
            "piece must be at destination after motion resolves");
    }

    // =========================================================================
    // Issue 3 — First click on cooldown piece: no selection, playIllegal()
    // =========================================================================

    @Test
    void firstClick_onCooldownPiece_noSelectionAndPlayIllegal() throws Exception {
        Board b = Board.create(8, 8);
        Piece rook = new Piece("Rook", "white");
        b.addPiece(new Position(0, 0), rook);
        GameEngine engine = new GameEngine().setBoard(b);

        // Move rook 1 cell: travel ~667ms at 1.5 m/s; advance 800ms so it lands but rest chain active
        engine.requestMove(new Position(0, 0), new Position(0, 1));
        engine.waitMs(800);
        Piece landed = b.pieceAt(new Position(0, 1)).orElse(null);
        assertNotNull(landed, "rook must have landed at (0,1)");
        assertTrue(engine.getArbiter().isOnCooldown(landed), "rook must be on cooldown");

        List<String> sounds = new ArrayList<>();
        SoundManager sm = new SoundManager() {
            @Override public void playIllegal()   { sounds.add("illegal"); }
            @Override public void playMoveStart() { sounds.add("moveStart"); }
        };
        Controller ctrl = engine.getController();
        ctrl.setSoundManager(sm);

        // First click on the cooldown rook at (0,1) — pixel: x=224+1*100+50=374, y=65+0*100+50=115
        Controller.ControllerResult r = ctrl.click(374, 115);
        assertFalse(r.moveRequested(), "first click on cooldown piece must not request a move");
        assertTrue(ctrl.getSelected().isEmpty(), "no selection must occur for cooldown piece");
        assertEquals(List.of("illegal"), sounds, "playIllegal() must be called exactly once");
    }

    @Test
    void firstClick_onIdlePiece_selectsNormally_noRegression() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(3, 3), new Piece("Rook", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        List<String> sounds = new ArrayList<>();
        SoundManager sm = new SoundManager() {
            @Override public void playIllegal()   { sounds.add("illegal"); }
            @Override public void playMoveStart() { sounds.add("moveStart"); }
        };
        Controller ctrl = engine.getController();
        ctrl.setSoundManager(sm);

        // First click on idle rook at (3,3) — pixel: x=224+3*100+50=574, y=65+3*100+50=415
        Controller.ControllerResult r = ctrl.click(574, 415);
        assertFalse(r.moveRequested(), "first click is selection only, not a move request");
        assertEquals(new Position(3, 3), ctrl.getSelected().orElse(null),
            "idle piece must be selected normally");
        assertTrue(sounds.isEmpty(), "no sound must play on first click of idle piece");
    }
}
