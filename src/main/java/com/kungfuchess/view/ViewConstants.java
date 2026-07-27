package com.kungfuchess.view;

/**
 * Single source of truth for canvas dimensions shared between {@link ImageView}
 * and {@link com.kungfuchess.view.util.TileGenerator}.
 *
 * <p>Any change to canvas size must be made here only — both the renderer and the
 * asset generator read from this class, so they can never diverge again.</p>
 */
public final class ViewConstants {

    private ViewConstants() {}

    /** Safe margin on all four canvas edges (pixels). */
    public static final int MARGIN = 20;

    /** Total canvas width in pixels (includes left + right margins). */
    public static final int CANVAS_W = 1248 + (MARGIN * 2);

    /** Height of the title bar above the board in pixels. */
    public static final int TITLE_H  = 45;

    /** 
     * Uniform thickness of the black coordinate frame band on all four sides of the board.
     * This single constant ensures all four frame edges (top, bottom, left, right) are identical.
     */
    public static final int FRAME_BAND = 24;

    /** Height of the dedicated file-label band above and below the board. */
    public static final int LABEL_BAND_H = FRAME_BAND;

    /** Width of the dedicated rank-label band left and right of the board. */
    public static final int RANK_BAND_W = FRAME_BAND;

    /** Total canvas height in pixels (includes top + bottom margins). */
    public static final int CANVAS_H = MARGIN + TITLE_H + LABEL_BAND_H + 800 + LABEL_BAND_H + MARGIN;  // 933

    /** Width of each sidebar panel in pixels. */
    public static final int SIDEBAR_W = 200;

    /**
     * Horizontal pixel offset of the board's left edge (margin + sidebar + rank band).
     * Both drawing (ImageView) and input (BoardMapper) must use this constant.
     */
    public static final int BOARD_X_OFFSET = MARGIN + SIDEBAR_W + RANK_BAND_W;  // 244

    /**
     * Vertical pixel offset of the board's top edge (margin + title bar + top label band).
     * Both drawing (ImageView) and input (BoardMapper) must use this constant.
     */
    public static final int BOARD_Y_OFFSET = MARGIN + TITLE_H + LABEL_BAND_H;  // 89
}
