package tests.unit;

import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Issue 1 (capture-lock flee rejection) and the vacated-square no-capture rule.
 *
 * <p>Removed test: {@code vacatedSquare_fastAttackerArrivesFirst_capturesInFlightPiece_noFreeze}
 * — that test asserted the old (incorrect) behavior where B arriving at A's vacated origin
 * would capture A mid-flight. That rule has been reverted.</p>
 */
class TestIssues1And2 {

    // =========================================================================
    // Issue 1 — Threatened piece cannot flee; only Dodge is allowed
    // =========================================================================

    @Test
    void threatenedPiece_normalFleeMoveRejected_withCaptureLocked() throws Exception {
        Board b = Board.create(8, 8);
        Piece defender = new Piece("Rook", "white");
        Piece attacker = new Piece("Rook", "black");
        b.addPiece(new Position(4, 4), defender);
        b.addPiece(new Position(4, 0), attacker);
        GameEngine engine = new GameEngine().setBoard(b);

        assertTrue(engine.requestMove(new Position(4, 0), new Position(4, 4)).isAccepted());

        GameEngine.MoveResult flee = engine.requestMove(new Position(4, 4), new Position(4, 7));
        assertFalse(flee.isAccepted(), "Threatened piece must not be able to flee");
        assertEquals(GameEngine.MoveResult.CAPTURE_LOCKED, flee.reason());
    }

    @Test
    void threatenedPiece_dodgeStillAccepted() throws Exception {
        Board b = Board.create(8, 8);
        Piece defender = new Piece("Rook", "white");
        Piece attacker = new Piece("Rook", "black");
        b.addPiece(new Position(4, 4), defender);
        b.addPiece(new Position(4, 0), attacker);
        GameEngine engine = new GameEngine().setBoard(b);

        assertTrue(engine.requestMove(new Position(4, 0), new Position(4, 4)).isAccepted());
        assertTrue(engine.requestDodge(new Position(4, 4)).isAccepted(),
            "Dodge must still be accepted while threatened");
    }

    @Test
    void unthreatenedPiece_normalMoveUnaffected() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        GameEngine.MoveResult r = engine.requestMove(new Position(0, 0), new Position(0, 4));
        assertTrue(r.isAccepted(), "Unthreatened piece must move normally");
        assertEquals(GameEngine.MoveResult.OK, r.reason());
    }

    // =========================================================================
    // Vacated-square rule: enemy landing on A's vacated origin never captures A
    // =========================================================================

    /**
     * A departs S→D1; while in-flight B targets S; both resolve cleanly.
     * A lands at D1, B lands at S, no capture, no freeze.
     * This is the canonical regression test for the reverted "ghost capture" rule.
     */
    @Test
    void vacatedSquare_enemyTargetsIt_bothLandCleanly_noCapture_noFreeze() throws Exception {
        Board b = Board.create(8, 8);
        Piece pieceA = new Piece("Rook", "white");
        Piece pieceB = new Piece("Rook", "black");
        Position S  = new Position(0, 0);
        Position D1 = new Position(0, 7);
        Position X  = new Position(7, 0);
        b.addPiece(S, pieceA);
        b.addPiece(X, pieceB);
        GameEngine engine = new GameEngine().setBoard(b);

        assertTrue(engine.requestMove(S, D1).isAccepted(), "A's move must be accepted");
        assertTrue(engine.requestMove(X, S).isAccepted(),  "B's move to vacated S must be accepted");
        assertEquals(2, engine.getArbiter().getPendingMotions().size());

        engine.waitMs(10000);

        // A must have landed at D1 — unaffected by B landing on S
        assertTrue(b.pieceAt(D1).isPresent(), "A must have landed at D1");
        assertSame(pieceA, b.pieceAt(D1).orElse(null), "A must be at D1, not captured");

        // B must have landed at S
        assertTrue(b.pieceAt(S).isPresent(), "B must have landed at S");
        assertSame(pieceB, b.pieceAt(S).orElse(null), "B must be at S");

        // No capture occurred
        assertEquals(0, engine.snapshot().scoreWhite(), "No capture — white score must be 0");
        assertEquals(0, engine.snapshot().scoreBlack(), "No capture — black score must be 0");

        // Game still accepts input
        assertTrue(engine.requestMove(D1, new Position(0, 4)).isAccepted(),
            "Game must accept further input after both motions resolve");
    }

    /**
     * Same scenario but B is faster (1 cell) and arrives at S before A (7 cells) lands.
     * A is still shown on the board at S when B arrives. A must still land at D1 — not captured.
     */
    @Test
    void vacatedSquare_fastEnemyArrivesFirst_departerStillLandsAtDestination() throws Exception {
        Board b = Board.create(8, 8);
        Piece pieceA = new Piece("Rook", "white");  // 7 cells — slow
        Piece pieceB = new Piece("Rook", "black");  // 1 cell  — fast
        Position S  = new Position(0, 0);
        Position D1 = new Position(0, 7);
        Position X  = new Position(1, 0);
        b.addPiece(S, pieceA);
        b.addPiece(X, pieceB);
        GameEngine engine = new GameEngine().setBoard(b);

        assertTrue(engine.requestMove(S, D1).isAccepted());
        assertTrue(engine.requestMove(X, S).isAccepted());

        engine.waitMs(10000);

        // A must still land at D1 — B arriving at S first must NOT capture A
        assertTrue(b.pieceAt(D1).isPresent(), "A must land at D1 even when B arrives at S first");
        assertSame(pieceA, b.pieceAt(D1).orElse(null), "A must be at D1, not captured");

        // B must be at S
        assertTrue(b.pieceAt(S).isPresent(), "B must be at S");
        assertSame(pieceB, b.pieceAt(S).orElse(null));

        // No capture
        assertEquals(0, engine.snapshot().scoreWhite());
        assertEquals(0, engine.snapshot().scoreBlack());
    }
}
