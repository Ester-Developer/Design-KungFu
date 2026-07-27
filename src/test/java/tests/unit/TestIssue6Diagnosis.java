package tests.unit;

import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.input.Controller;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue 6 diagnosis: can a captured piece's square still be selected after the
 * capturing motion has fully resolved?
 */
class TestIssue6Diagnosis {

    /**
     * After waitMs resolves a capture, clicking the captured piece's old square
     * must NOT produce a selection (the board is empty there).
     */
    @Test
    void capturedPieceSquare_cannotBeSelectedAfterCapture() throws Exception {
        Board b = Board.create(8, 8);
        Piece attacker = new Piece("Rook", "white");
        Piece defender = new Piece("Rook", "black");
        // attacker at (0,0), defender at (0,2)
        b.addPiece(new Position(0, 0), attacker);
        b.addPiece(new Position(0, 2), defender);
        GameEngine engine = new GameEngine().setBoard(b);
        Controller ctrl = engine.getController();

        // Start the capture motion
        engine.requestMove(new Position(0, 0), new Position(0, 2));
        // Fully resolve it
        engine.waitMs(5000);

        // Board must be correct: defender gone, attacker at (0,2)
        assertTrue(b.pieceAt(new Position(0, 0)).isEmpty(), "attacker's origin must be empty");
        assertTrue(b.pieceAt(new Position(0, 2)).isPresent(), "attacker must be at destination");
        assertEquals(attacker, b.pieceAt(new Position(0, 2)).orElse(null));

        // Now try to click the defender's old square (0,2) — it has the attacker on it,
        // so this SHOULD select the attacker (that is correct behavior).
        // The real question: can we click the attacker's OLD square (0,0) and get a selection?
        // col=0 → x=224+0*100+50=274; row=0 → y=65+0*100+50=115
        Controller.ControllerResult r1 = ctrl.click(274, 115); // click (0,0) — should be empty
        assertFalse(r1.moveRequested(), "click on empty square must not request a move");
        assertTrue(ctrl.getSelected().isEmpty(),
            "clicking the attacker's vacated origin must not produce a selection");
    }

    /**
     * After waitMs resolves a capture, clicking the attacker's new square (destination)
     * correctly selects the attacker — no stale reference issue.
     */
    @Test
    void attackerAtDestination_canBeSelectedAfterCapture() throws Exception {
        Board b = Board.create(8, 8);
        Piece attacker = new Piece("Rook", "white");
        Piece defender = new Piece("Rook", "black");
        b.addPiece(new Position(0, 0), attacker);
        b.addPiece(new Position(0, 2), defender);
        GameEngine engine = new GameEngine().setBoard(b);
        Controller ctrl = engine.getController();

        engine.requestMove(new Position(0, 0), new Position(0, 2));
        engine.waitMs(5000);

        // Click the attacker's new position (0,2): col=2 → x=224+2*100+50=474; row=0 → y=115
        ctrl.click(474, 115);
        assertEquals(new Position(0, 2), ctrl.getSelected().orElse(null),
            "attacker at its destination must be selectable after capture resolves");
    }

    /**
     * After waitMs resolves a capture, attempting to move from the captured piece's
     * old square (now empty) must be rejected with empty_source, not accepted.
     * This is the critical path: if a stale reference existed, requestMove might
     * accept a move from an empty square.
     */
    @Test
    void moveFromCapturedSquare_isRejectedWithEmptySource() throws Exception {
        Board b = Board.create(8, 8);
        Piece attacker = new Piece("Rook", "white");
        Piece defender = new Piece("Rook", "black");
        b.addPiece(new Position(0, 0), attacker);
        b.addPiece(new Position(0, 2), defender);
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(0, 0), new Position(0, 2));
        engine.waitMs(5000);

        // Try to move from the defender's old square — it's now occupied by the attacker.
        // Try to move from the attacker's old square (0,0) — it's empty.
        GameEngine.MoveResult r = engine.requestMove(new Position(0, 0), new Position(0, 5));
        assertFalse(r.isAccepted(), "move from empty square must be rejected");
        assertEquals("empty_source", r.reason(),
            "actual observed reason for move from vacated square");
    }
}
