package tests.unit;

import com.kungfuchess.input.BoardMapper;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Position;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestBoardMapper {

    // -------------------------------------------------------------------------
    // pixelToBoard — offset-aware (board starts at BOARD_X_OFFSET, BOARD_Y_OFFSET)
    // -------------------------------------------------------------------------

    @Test
    void boardTopLeftPixelMapsToOriginCell() {
        // The board's actual top-left pixel is (BOARD_X_OFFSET, BOARD_Y_OFFSET)
        assertEquals(new Position(0, 0),
            BoardMapper.pixelToBoard(BoardMapper.BOARD_X_OFFSET, BoardMapper.BOARD_Y_OFFSET));
    }

    @Test
    void leftSidebarPixelIsOutOfBounds() {
        // x=50 is inside the left sidebar — must map to a negative column
        Position result = BoardMapper.pixelToBoard(50, BoardMapper.BOARD_Y_OFFSET + 100);
        assertTrue(result.getCol() < 0,
            "A click in the left sidebar must map to a negative (out-of-bounds) column");
    }

    @Test
    void titleBarClickIsOutOfBounds() {
        // y < BOARD_Y_OFFSET is the title bar — must map to a negative row
        Board board = Board.create(8, 8);
        int titleBarY = BoardMapper.BOARD_Y_OFFSET - 1;
        assertFalse(BoardMapper.isPixelInBounds(board, BoardMapper.BOARD_X_OFFSET + 50, titleBarY),
            "A click in the title bar (y < BOARD_Y_OFFSET) must be out of bounds");
    }

    @Test
    void pixelMapsToCellByFloorDivisionOfCellSizeWithOffset() {
        // pixel (450, BOARD_Y_OFFSET+150) → col=(450-200)/100=2, row=150/100=1
        assertEquals(new Position(1, 2),
            BoardMapper.pixelToBoard(450, BoardMapper.BOARD_Y_OFFSET + 150));
    }

    @Test
    void negativePixelsMapToNegativeOutOfBoundsCell() {
        // Math.floorDiv (not plain "/") must be used
        Position result = BoardMapper.pixelToBoard(-1, -1);
        assertTrue(result.getRow() < 0);
        assertTrue(result.getCol() < 0);
    }

    @Test
    void lastRowBottomEdgeStillMapsToLastRow() {
        // Bottom of row 7 (last row of 8x8): y = BOARD_Y_OFFSET + 7*100 + 99
        int lastRowBottomY = BoardMapper.BOARD_Y_OFFSET + 7 * BoardMapper.CELL_SIZE_PIXELS + 99;
        Position p = BoardMapper.pixelToBoard(BoardMapper.BOARD_X_OFFSET + 50, lastRowBottomY);
        assertEquals(7, p.getRow(), "Bottom edge of last row must still map to row 7");
    }

    // -------------------------------------------------------------------------
    // boardToPixel — offset-aware
    // -------------------------------------------------------------------------

    @Test
    void boardToPixelReturnsTopLeftCornerOfCellWithOffset() {
        // cell (2,3) → x = BOARD_X_OFFSET + 3*100, y = BOARD_Y_OFFSET + 2*100
        int[] pixel = BoardMapper.boardToPixel(new Position(2, 3));
        assertArrayEquals(new int[]{BoardMapper.BOARD_X_OFFSET + 300, BoardMapper.BOARD_Y_OFFSET + 200}, pixel);
    }

    @Test
    void boardToPixelOriginCellHasCorrectOffsets() {
        int[] pixel = BoardMapper.boardToPixel(new Position(0, 0));
        assertArrayEquals(new int[]{BoardMapper.BOARD_X_OFFSET, BoardMapper.BOARD_Y_OFFSET}, pixel);
    }

    // -------------------------------------------------------------------------
    // isPixelInBounds — offset-aware
    // -------------------------------------------------------------------------

    @Test
    void isPixelInBoundsTrueWithinBoardFalseOutside() {
        Board board = Board.create(3, 3);
        int yo = BoardMapper.BOARD_Y_OFFSET;
        // col=2, row=2 → in bounds for 3×3
        assertTrue(BoardMapper.isPixelInBounds(board, 450, yo + 250));
        // col=3 → out of bounds for 3×3
        assertFalse(BoardMapper.isPixelInBounds(board, 550, yo + 50));
        // x=50 → sidebar → out of bounds
        assertFalse(BoardMapper.isPixelInBounds(board, 50, yo + 50));
        // y in title bar → out of bounds
        assertFalse(BoardMapper.isPixelInBounds(board, 300, yo - 1));
    }

    // -------------------------------------------------------------------------
    // Round-trip: boardToPixel → pixelToBoard must be identity
    // -------------------------------------------------------------------------

    @Test
    void roundTripBoardToPixelToBoard() {
        Position original = new Position(3, 5);
        int[] px = BoardMapper.boardToPixel(original);
        assertEquals(original, BoardMapper.pixelToBoard(px[0], px[1]));
    }
}
