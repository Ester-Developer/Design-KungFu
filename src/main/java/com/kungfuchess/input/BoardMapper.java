package com.kungfuchess.input;
import com.kungfuchess.model.Board;

import com.kungfuchess.model.Position;
import com.kungfuchess.view.ViewConstants;

/**
 * Coordinate Adapter: translates between screen pixel coordinates and board cell
 * positions.
 *
 * <p>This is the single place that knows a board cell is 100×100 pixels and that the
 * board starts at pixel x={@link #BOARD_X_OFFSET} and y={@link #BOARD_Y_OFFSET}.
 * Neither the model ({@code Board}/{@code Piece}) nor the engine should need to know
 * about pixels at all; if the cell size or layout changes, only this class needs to
 * change.</p>
 */
public final class BoardMapper {

    /** Pixel width/height of a single board cell. */
    public static final int CELL_SIZE_PIXELS = 100;

    /**
     * Horizontal pixel offset of the board's left edge.
     * Read from {@link ViewConstants#BOARD_X_OFFSET} so drawing and input always agree.
     */
    public static final int BOARD_X_OFFSET = ViewConstants.BOARD_X_OFFSET;

    /**
     * Vertical pixel offset of the board's top edge (below the title bar).
     * Read from {@link ViewConstants#BOARD_Y_OFFSET} so drawing and input always agree.
     */
    public static final int BOARD_Y_OFFSET = ViewConstants.BOARD_Y_OFFSET;

    private BoardMapper() {}

    /**
     * Converts pixel coordinates to a board position.
     *
     * <p>Subtracts {@link #BOARD_X_OFFSET} / {@link #BOARD_Y_OFFSET} before dividing,
     * so that a click at pixel ({@code BOARD_X_OFFSET}, {@code BOARD_Y_OFFSET}) maps to
     * board cell (0,0). Uses {@link Math#floorDiv} so negative coordinates map to
     * negative (out-of-bounds) cells rather than truncating toward zero.</p>
     */
    public static Position pixelToBoard(int pixelX, int pixelY) {
        int row = Math.floorDiv(pixelY - BOARD_Y_OFFSET, CELL_SIZE_PIXELS);
        int col = Math.floorDiv(pixelX - BOARD_X_OFFSET, CELL_SIZE_PIXELS);
        return new Position(row, col);
    }

    /**
     * @param position the board position
     * @return the pixel coordinates of that cell's top-left corner, as {@code [x, y]},
     *         offset by {@link #BOARD_X_OFFSET} and {@link #BOARD_Y_OFFSET}
     */
    public static int[] boardToPixel(Position position) {
        int pixelX = BOARD_X_OFFSET + position.getCol() * CELL_SIZE_PIXELS;
        int pixelY = BOARD_Y_OFFSET + position.getRow() * CELL_SIZE_PIXELS;
        return new int[]{pixelX, pixelY};
    }

    /**
     * @param board  the board whose dimensions define "in bounds"
     * @param pixelX the x-coordinate in pixels (absolute canvas coordinate)
     * @param pixelY the y-coordinate in pixels
     * @return {@code true} if the pixel coordinates land on a cell within {@code board}
     */
    public static boolean isPixelInBounds(Board board, int pixelX, int pixelY) {
        return board.isInBounds(pixelToBoard(pixelX, pixelY));
    }
}
