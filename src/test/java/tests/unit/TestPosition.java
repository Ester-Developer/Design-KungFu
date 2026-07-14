package tests.unit;

import com.kungfuchess.model.Position;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestPosition {

    @Test
    void equalPositionsAreEqual() {
        assertEquals(new Position(2, 3), new Position(2, 3));
    }

    @Test
    void differentRowMeansNotEqual() {
        assertNotEquals(new Position(2, 3), new Position(1, 3));
    }

    @Test
    void differentColMeansNotEqual() {
        assertNotEquals(new Position(2, 3), new Position(2, 4));
    }

    @Test
    void equalPositionsHaveEqualHashCode() {
        assertEquals(new Position(5, 7).hashCode(), new Position(5, 7).hashCode());
    }

    @Test
    void notEqualToNullOrOtherType() {
        Position p = new Position(0, 0);
        assertNotEquals(null, p);
        assertNotEquals("not a position", p);
    }

    @Test
    void gettersReturnConstructorArguments() {
        Position p = new Position(4, 9);
        assertEquals(4, p.getRow());
        assertEquals(9, p.getCol());
    }
}
