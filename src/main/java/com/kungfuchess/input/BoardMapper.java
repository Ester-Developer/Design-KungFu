package com.kungfuchess.input;
import com.kungfuchess.model.Board;

import com.kungfuchess.model.Position;

/**
 * Coordinate Adapter: translates between screen pixel coordinates and board cell
 * positions.
 *
 * <p>This is the single place that knows a board cell is 100×100 pixels — a display
 * detail, not a chess rule. Neither the model ({@code Board}/{@code Piece}) nor the
 * engine should need to know about pixels at all; if the cell size changes, or a
 * future {@code Zoom}/viewport feature is added, only this class needs to change.</p>
 */
public final class BoardMapper {

    /** Pixel width/height of a single board cell. */
    public static final int CELL_SIZE_PIXELS = 100;

    private BoardMapper() {}

    /**
     * Converts pixel coordinates to a board position.
     *
     * <p>Uses {@link Math#floorDiv} (not plain division) so negative pixel coordinates
     * correctly map to a negative, out-of-bounds cell instead of truncating toward zero
     * into cell (0, 0).</p>
     *
     * @param pixelX the x-coordinate in pixels
     * @param pixelY the y-coordinate in pixels
     * @return the corresponding board position (may be out of bounds — the caller
     *         should check via {@link #isPixelInBounds})
     */
    public static Position pixelToBoard(int pixelX, int pixelY) {
        int row = Math.floorDiv(pixelY, CELL_SIZE_PIXELS);
        int col = Math.floorDiv(pixelX, CELL_SIZE_PIXELS);
        return new Position(row, col);
    }

    /**
     * @param position the board position
     * @return the pixel coordinates of that cell's top-left corner, as {@code [x, y]}
     */
    public static int[] boardToPixel(Position position) {
        int pixelX = position.getCol() * CELL_SIZE_PIXELS;
        int pixelY = position.getRow() * CELL_SIZE_PIXELS;
        return new int[]{pixelX, pixelY};
    }

    /**
     * @param board  the board whose dimensions define "in bounds"
     * @param pixelX the x-coordinate in pixels
     * @param pixelY the y-coordinate in pixels
     * @return {@code true} if the pixel coordinates land on a cell within {@code board}
     */
    public static boolean isPixelInBounds(Board board, int pixelX, int pixelY) {
        return board.isInBounds(pixelToBoard(pixelX, pixelY));
    }
}
