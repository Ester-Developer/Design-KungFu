package tests.unit;

import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.engine.GameEngine.GameSnapshot;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that a piece landing on a square vacated by another piece is immediately
 * visible in the snapshot, even while the original piece's motion is still in-flight.
 */
class TestVacatedSquareVisibility {

    @Test
    void pieceLandingOnVacatedSquare_isImmediatelyVisibleInSnapshot() throws Exception {
        Board b = Board.create(8, 8);
        Piece blackRook = new Piece("Rook", "black");
        Piece whiteRook = new Piece("Rook", "white");
        
        Position sharedSquare = new Position(4, 4);
        Position blackDest = new Position(4, 0);   // Black moves 4 cells north: 2667ms
        Position whiteStart = new Position(3, 4);  // White is 1 cell west: 667ms
        
        b.addPiece(sharedSquare, blackRook);
        b.addPiece(whiteStart, whiteRook);
        
        GameEngine engine = new GameEngine().setBoard(b);
        
        // Black Rook departs (4,4) toward (4,0) — 4 cells = ~2667ms
        assertTrue(engine.requestMove(sharedSquare, blackDest).isAccepted());
        
        // White Rook targets the now-vacated (4,4) — 1 cell = ~667ms
        assertTrue(engine.requestMove(whiteStart, sharedSquare).isAccepted());
        
        // Advance time: White lands at t=667, Black still flying (lands at t=2667)
        engine.waitMs(700);
        
        // Verify: White Rook must be at (4,4) on the board
        Piece atShared = b.pieceAt(sharedSquare).orElse(null);
        assertNotNull(atShared, "White Rook must occupy (4,4) after landing");
        assertEquals("white", atShared.getColor());
        
        // Verify: White Rook must be present in the snapshot's piece list
        GameSnapshot snap = engine.snapshot();
        boolean whiteRookInSnapshot = snap.pieces().stream()
            .anyMatch(pv -> pv.position().equals(sharedSquare) && "white".equals(pv.color()));
        assertTrue(whiteRookInSnapshot,
            "White Rook at (4,4) must appear in snapshot.pieces() immediately after landing, " +
            "even while Black Rook's unrelated motion is still in-flight");
        
        // Verify: Black Rook motion is still active (hasn't landed yet)
        boolean blackStillMoving = snap.activeMotions().stream()
            .anyMatch(m -> m.getPiece() == blackRook);
        assertTrue(blackStillMoving, "Black Rook's motion should still be in-flight at t=700ms");
    }
}
