package tests.unit;

import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import com.kungfuchess.realtime.Motion;
import com.kungfuchess.realtime.RealTimeArbiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Motion.startTime, GameSnapshot.clock(), GameSnapshot.cooldownUntilMs(),
 * and the ArrivalEvents return value of GameEngine.waitMs().
 */
class TestMotionAndSnapshot {

    private RealTimeArbiter arbiter;
    private Board board;

    @BeforeEach
    void setUp() {
        arbiter = new RealTimeArbiter();
        board   = Board.create(8, 8);
    }

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
        // dueTime = startTime + travel; travel = 3 / 1.5 * 1000 = 2000ms
        assertEquals(m.getStartTime() + 2000L, m.getDueTime());
    }

    @Test
    void snapshotClockReflectsArbiterClock() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        engine.waitMs(750);
        assertEquals(750L, engine.snapshot().clock());
    }

    @Test
    void waitMsReturnsArrivalEventsWithLanding() throws Exception {
        Board b = Board.create(8, 8);
        Piece rook = new Piece("Rook", "white");
        b.addPiece(new Position(0, 0), rook);
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(0, 0), new Position(0, 2)); // 2 cells / 1.5 = ~1333ms
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
        RealTimeArbiter.ArrivalEvents events = engine.waitMs(500); // not yet arrived

        assertTrue(events.arrivals().isEmpty());
    }

    @Test
    void snapshotCooldownMapReflectsOnCooldownPiece() throws Exception {
        Board b = Board.create(8, 8);
        Piece rook = new Piece("Rook", "white");
        b.addPiece(new Position(0, 0), rook);
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(0, 0), new Position(0, 1));
        GameEngine.GameSnapshot snap = engine.snapshot();

        Long until = snap.cooldownUntilMs().get(rook);
        assertNotNull(until, "cooldown entry must be present after move starts");
        assertTrue(until > snap.clock(), "cooldownUntil must be in the future");
    }
}
