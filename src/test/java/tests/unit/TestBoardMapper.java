package tests.unit;

import com.kungfuchess.input.BoardMapper;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Position;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestBoardMapper {

    @Test
    void originPixelMapsToOriginCell() {
        assertEquals(new Position(0, 0), BoardMapper.pixelToBoard(0, 0));
    }

    @Test
    void pixelMapsToCellByFloorDivisionOfCellSize() {
        assertEquals(new Position(1, 2), BoardMapper.pixelToBoard(250, 150));
    }

    @Test
    void negativePixelsMapToNegativeOutOfBoundsCell() {
        // Math.floorDiv (not plain "/") must be used, or -1 truncates to 0.
        Position result = BoardMapper.pixelToBoard(-1, -1);
        assertEquals(-1, result.getRow());
        assertEquals(-1, result.getCol());
    }

    @Test
    void boardToPixelReturnsTopLeftCornerOfCell() {
        int[] pixel = BoardMapper.boardToPixel(new Position(2, 3));
        assertArrayEquals(new int[]{300, 200}, pixel);
    }

    @Test
    void isPixelInBoundsTrueWithinBoardFalseOutside() {
        Board board = Board.create(3, 3);
        assertTrue(BoardMapper.isPixelInBounds(board, 250, 250));
        assertFalse(BoardMapper.isPixelInBounds(board, 350, 50));
        assertFalse(BoardMapper.isPixelInBounds(board, -10, 50));
    }
}
