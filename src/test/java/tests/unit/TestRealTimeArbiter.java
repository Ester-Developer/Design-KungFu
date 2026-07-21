package tests.unit;

import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import com.kungfuchess.realtime.RealTimeArbiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestRealTimeArbiter {

    private RealTimeArbiter arbiter;
    private Board board;

    @BeforeEach
    void setUp() {
        arbiter = new RealTimeArbiter();
        board = Board.create(8, 8);
    }

    @Test
    void noMotionInitially() {
        assertFalse(arbiter.hasActiveMotion());
        assertEquals(0, arbiter.getClock());
    }

    @Test
    void startMotionMarksArbiterAsBusy() throws Exception {
        Piece rook = new Piece("Rook", "white");
        board.addPiece(new Position(0, 0), rook);

        arbiter.startMotion(rook, new Position(0, 0), new Position(0, 3));

        assertTrue(arbiter.hasActiveMotion());
    }

    @Test
    void startMotionMarksThePieceAsMoved() throws Exception {
        Piece pawn = new Piece("Pawn", "white");
        board.addPiece(new Position(6, 0), pawn);

        assertFalse(arbiter.hasMoved(pawn));
        arbiter.startMotion(pawn, new Position(6, 0), new Position(4, 0));
        assertTrue(arbiter.hasMoved(pawn));
    }

    @Test
    void motionDoesNotArriveBeforeItsTravelTimeElapses() throws Exception {
        Piece rook = new Piece("Rook", "white");
        board.addPiece(new Position(0, 0), rook);
        arbiter.startMotion(rook, new Position(0, 0), new Position(0, 3));

        // Travel: 3 cells / 1.5 m/s * 1000 = 2000ms; advance only 1000ms
        arbiter.advanceTime(1000, board);

        assertTrue(arbiter.hasActiveMotion());
        assertTrue(board.pieceAt(new Position(0, 0)).isPresent());
        assertTrue(board.pieceAt(new Position(0, 3)).isEmpty());
    }

    @Test
    void motionArrivesAndMovesThePieceOnceEnoughTimeElapses() throws Exception {
        Piece rook = new Piece("Rook", "white");
        board.addPiece(new Position(0, 0), rook);
        arbiter.startMotion(rook, new Position(0, 0), new Position(0, 3));

        // 3 cells / 1.5 m/s * 1000 = 2000ms
        arbiter.advanceTime(2000, board);

        assertFalse(arbiter.hasActiveMotion());
        assertTrue(board.pieceAt(new Position(0, 0)).isEmpty());
        assertEquals(rook, board.pieceAt(new Position(0, 3)).orElse(null));
    }

    @Test
    void advanceTimeReportsArrivalsIncludingCapturedPiece() throws Exception {
        Piece attacker = new Piece("Rook", "white");
        Piece defender = new Piece("Rook", "black");
        board.addPiece(new Position(0, 0), attacker);
        board.addPiece(new Position(0, 2), defender);

        arbiter.startMotion(attacker, new Position(0, 0), new Position(0, 2));
        // 2 cells / 1.5 m/s * 1000 ≈ 1333ms
        RealTimeArbiter.ArrivalEvents events = arbiter.advanceTime(2000, board);

        assertEquals(1, events.arrivals().size());
        assertEquals(defender, events.arrivals().get(0).capturedPiece());
    }

    @Test
    void travelTimeScalesWithChebyshevDistance() {
        // Static travelTime(from, to) uses CELL_DURATION_MS — unchanged for backward compat
        assertEquals(RealTimeArbiter.CELL_DURATION_MS * 3,
            RealTimeArbiter.travelTime(new Position(0, 0), new Position(3, 0)));
        assertEquals(RealTimeArbiter.CELL_DURATION_MS * 3,
            RealTimeArbiter.travelTime(new Position(0, 0), new Position(3, 3)));
    }

    @Test
    void clockAccumulatesAcrossMultipleAdvances() {
        arbiter.advanceTime(500, board);
        arbiter.advanceTime(700, board);
        assertEquals(1200, arbiter.getClock());
    }

    // -------------------------------------------------------------------------
    // New: per-piece cooldown tests
    // -------------------------------------------------------------------------

    @Test
    void pieceIsOnCooldownImmediatelyAfterStartMotion() throws Exception {
        Piece rook = new Piece("Rook", "white");
        board.addPiece(new Position(0, 0), rook);

        assertFalse(arbiter.isOnCooldown(rook));
        arbiter.startMotion(rook, new Position(0, 0), new Position(0, 3));
        assertTrue(arbiter.isOnCooldown(rook));
    }

    @Test
    void pieceIsStillOnCooldownAfterLandingDuringRestChain() throws Exception {
        Piece rook = new Piece("Rook", "white");
        board.addPiece(new Position(0, 0), rook);
        arbiter.startMotion(rook, new Position(0, 0), new Position(0, 3));

        // Advance past travel time (2000ms) but not past rest chain
        arbiter.advanceTime(2500, board);

        assertFalse(arbiter.hasActiveMotion(), "motion should have resolved");
        assertTrue(arbiter.isOnCooldown(rook), "piece should still be in rest cooldown");
    }

    @Test
    void cooldownExpiresAfterFullRestChain() throws Exception {
        Piece rook = new Piece("Rook", "white");
        board.addPiece(new Position(0, 0), rook);
        arbiter.startMotion(rook, new Position(0, 0), new Position(0, 1));

        // 1 cell / 1.5 m/s ≈ 667ms travel + long_rest (5 frames @ 2fps = 2500ms) = ~3167ms
        arbiter.advanceTime(5000, board);

        assertFalse(arbiter.isOnCooldown(rook), "cooldown must expire after full rest chain");
    }

    @Test
    void twoPiecesCanBeInFlightConcurrently() throws Exception {
        Piece rook   = new Piece("Rook",   "white");
        Piece bishop = new Piece("Bishop", "black");
        board.addPiece(new Position(0, 0), rook);
        board.addPiece(new Position(7, 7), bishop);

        arbiter.startMotion(rook,   new Position(0, 0), new Position(0, 3));
        arbiter.startMotion(bishop, new Position(7, 7), new Position(4, 4));

        assertEquals(2, arbiter.getPendingMotions().size(),
            "Both motions must be tracked concurrently");
        assertTrue(arbiter.isOnCooldown(rook));
        assertTrue(arbiter.isOnCooldown(bishop));
    }

    @Test
    void unrelatedPieceIsNotOnCooldownWhileOtherPieceMoves() throws Exception {
        Piece rook   = new Piece("Rook",   "white");
        Piece bishop = new Piece("Bishop", "white");
        board.addPiece(new Position(0, 0), rook);
        board.addPiece(new Position(2, 0), bishop);

        arbiter.startMotion(rook, new Position(0, 0), new Position(0, 3));

        assertFalse(arbiter.isOnCooldown(bishop),
            "An unrelated piece must not be on cooldown just because another piece is moving");
    }

    @Test
    void jumpProtectionPreventsLandingOnProtectedSquare() throws Exception {
        Piece attacker = new Piece("Rook",   "white");
        Piece defender = new Piece("Knight", "black");
        board.addPiece(new Position(0, 0), attacker);
        board.addPiece(new Position(0, 3), defender);

        // Protect the destination square for 5000ms
        arbiter.addProtection(new Position(0, 3), 5000);
        arbiter.startMotion(attacker, new Position(0, 0), new Position(0, 3));
        arbiter.advanceTime(2000, board);

        // Attacker should have crashed (removed), defender still present
        assertTrue(board.pieceAt(new Position(0, 0)).isEmpty(),
            "Attacker should be removed after crashing into protected square");
        assertTrue(board.pieceAt(new Position(0, 3)).isPresent(),
            "Defender should remain on protected square");
    }
}
