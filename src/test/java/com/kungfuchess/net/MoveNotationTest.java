package com.kungfuchess.net;

import com.kungfuchess.model.Position;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MoveNotationTest {

    @Test
    void testParseSquare() {
        // a1 = bottom-left (row 7, col 0)
        Position a1 = MoveNotation.parseSquare('a', '1');
        assertEquals(7, a1.getRow());
        assertEquals(0, a1.getCol());

        // e2 = white pawn starting position (row 6, col 4)
        Position e2 = MoveNotation.parseSquare('e', '2');
        assertEquals(6, e2.getRow());
        assertEquals(4, e2.getCol());

        // e4 = common opening square (row 4, col 4)
        Position e4 = MoveNotation.parseSquare('e', '4');
        assertEquals(4, e4.getRow());
        assertEquals(4, e4.getCol());

        // h8 = top-right (row 0, col 7)
        Position h8 = MoveNotation.parseSquare('h', '8');
        assertEquals(0, h8.getRow());
        assertEquals(7, h8.getCol());
    }

    @Test
    void testEncodeSquare() {
        assertEquals("a1", MoveNotation.encodeSquare(new Position(7, 0)));
        assertEquals("e2", MoveNotation.encodeSquare(new Position(6, 4)));
        assertEquals("e4", MoveNotation.encodeSquare(new Position(4, 4)));
        assertEquals("h8", MoveNotation.encodeSquare(new Position(0, 7)));
    }

    @Test
    void testParseMove() {
        // e2e4 - common opening move
        Position[] move = MoveNotation.parseMove("e2e4");
        assertEquals(2, move.length);
        assertEquals(new Position(6, 4), move[0]); // e2
        assertEquals(new Position(4, 4), move[1]); // e4

        // a1h8 - diagonal from corner to corner
        Position[] move2 = MoveNotation.parseMove("a1h8");
        assertEquals(new Position(7, 0), move2[0]); // a1
        assertEquals(new Position(0, 7), move2[1]); // h8
    }

    @Test
    void testEncodeMove() {
        assertEquals("e2e4", MoveNotation.encodeMove(
            new Position(6, 4), new Position(4, 4)));
        assertEquals("a1h8", MoveNotation.encodeMove(
            new Position(7, 0), new Position(0, 7)));
    }

    @Test
    void testRoundTrip() {
        // Test that parsing and encoding are inverse operations
        String original = "d2d4";
        Position[] parsed = MoveNotation.parseMove(original);
        String encoded = MoveNotation.encodeMove(parsed[0], parsed[1]);
        assertEquals(original, encoded);
    }

    @Test
    void testInvalidMoveLength() {
        assertThrows(IllegalArgumentException.class, () -> 
            MoveNotation.parseMove("e2"));
        assertThrows(IllegalArgumentException.class, () -> 
            MoveNotation.parseMove("e2e4e5"));
    }

    @Test
    void testInvalidColumn() {
        assertThrows(IllegalArgumentException.class, () -> 
            MoveNotation.parseSquare('i', '1'));
        assertThrows(IllegalArgumentException.class, () -> 
            MoveNotation.parseSquare('z', '5'));
    }

    @Test
    void testInvalidRow() {
        assertThrows(IllegalArgumentException.class, () -> 
            MoveNotation.parseSquare('e', '0'));
        assertThrows(IllegalArgumentException.class, () -> 
            MoveNotation.parseSquare('e', '9'));
    }
}
