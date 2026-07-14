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
        arbiter.startMotion(rook, new Position(0, 0), new Position(0, 3)); // 3 cells = 3000ms

        arbiter.advanceTime(2000, board);

        assertTrue(arbiter.hasActiveMotion());
        assertTrue(board.pieceAt(new Position(0, 0)).isPresent());
        assertTrue(board.pieceAt(new Position(0, 3)).isEmpty());
    }

    @Test
    void motionArrivesAndMovesThePieceOnceEnoughTimeElapses() throws Exception {
        Piece rook = new Piece("Rook", "white");
        board.addPiece(new Position(0, 0), rook);
        arbiter.startMotion(rook, new Position(0, 0), new Position(0, 3));

        arbiter.advanceTime(3000, board);

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
        RealTimeArbiter.ArrivalEvents events = arbiter.advanceTime(2000, board);

        assertEquals(1, events.arrivals().size());
        assertEquals(defender, events.arrivals().get(0).capturedPiece());
    }

    @Test
    void travelTimeScalesWithChebyshevDistance() {
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
}
