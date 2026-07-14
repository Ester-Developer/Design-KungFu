package com.kungfuchess.model;

import java.util.Optional;

/**
 * The logical board grid.
 *
 * <p>Owns which piece occupies which cell. It has no knowledge of chess movement
 * rules; all move validation is the responsibility of the rule layer above it
 * ({@code RuleEngine}/{@code PieceRules}). It also has no knowledge of pixels — that
 * belongs to {@code BoardMapper}.</p>
 *
 * <p>{@link #movePiece} performs no rule checking: its pre-condition is that the
 * caller (the rule layer, via {@code GameEngine}) has already validated that the move
 * is legal.</p>
 */
public class Board {

    private final int width;
    private final int height;

    /** Internal grid: grid[row][col]. Null means empty. */
    private final Piece[][] grid;

    private Board(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Board dimensions must be positive, got " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.grid = new Piece[height][width];
    }

    /** @return a new, empty standard 8×8 board. */
    public static Board createStandard() {
        return new Board(8, 8);
    }

    /**
     * @param width  number of columns (must be &gt; 0)
     * @param height number of rows (must be &gt; 0)
     * @return a new, empty board of the given dimensions
     */
    public static Board create(int width, int height) {
        return new Board(width, height);
    }

    /** @return number of columns */
    public int getWidth() {
        return width;
    }

    /** @return number of rows */
    public int getHeight() {
        return height;
    }

    /**
     * @param position position to test
     * @return {@code true} if the position lies within the board boundaries
     */
    public boolean isInBounds(Position position) {
        return position.getRow() >= 0
            && position.getRow() < height
            && position.getCol() >= 0
            && position.getCol() < width;
    }

    /**
     * @param position target cell
     * @return the occupying piece, or {@link Optional#empty()}
     * @throws OutOfBoundsException if position is outside the board
     */
    public Optional<Piece> pieceAt(Position position) throws OutOfBoundsException {
        requireInBounds(position);
        return Optional.ofNullable(grid[position.getRow()][position.getCol()]);
    }

    /**
     * Places a piece on an empty cell.
     *
     * @param position target cell
     * @param piece    piece to place
     * @throws OutOfBoundsException  if position is outside the board
     * @throws OccupiedCellException if the cell is already occupied
     */
    public void addPiece(Position position, Piece piece) throws OutOfBoundsException, OccupiedCellException {
        requireInBounds(position);
        if (grid[position.getRow()][position.getCol()] != null) {
            throw new OccupiedCellException(position);
        }
        grid[position.getRow()][position.getCol()] = piece;
    }

    /**
     * Removes and returns the piece at the given position.
     *
     * @param position target cell
     * @return the removed piece, or {@link Optional#empty()} if the cell was already empty
     * @throws OutOfBoundsException if position is outside the board
     */
    public Optional<Piece> removePiece(Position position) throws OutOfBoundsException {
        requireInBounds(position);
        Piece existing = grid[position.getRow()][position.getCol()];
        grid[position.getRow()][position.getCol()] = null;
        return Optional.ofNullable(existing);
    }

    /**
     * Moves the piece at {@code from} to {@code to}. Any piece already at {@code to} is
     * silently replaced (capture semantics — the rule layer decides whether a capture
     * is legal before calling this method).
     *
     * @param from source cell — must be occupied
     * @param to   destination cell
     * @throws OutOfBoundsException     if either position is outside the board
     * @throws IllegalArgumentException if the source cell is empty
     */
    public void movePiece(Position from, Position to) throws OutOfBoundsException {
        requireInBounds(from);
        requireInBounds(to);

        Piece moving = grid[from.getRow()][from.getCol()];
        if (moving == null) {
            throw new IllegalArgumentException("No piece at source position " + from);
        }

        grid[to.getRow()][to.getCol()] = moving;
        grid[from.getRow()][from.getCol()] = null;
    }

    private void requireInBounds(Position position) throws OutOfBoundsException {
        if (!isInBounds(position)) {
            throw new OutOfBoundsException(position, width, height);
        }
    }

    @Override
    public String toString() {
        return "Board(" + width + "x" + height + ")";
    }

    /** Thrown when a position lies outside the valid bounds of the board. */
    public static class OutOfBoundsException extends Exception {
        private final Position position;

        public OutOfBoundsException(Position position, int width, int height) {
            super("Position " + position + " is out of bounds for board " + width + "x" + height);
            this.position = position;
        }

        /** @return the position that was out of bounds */
        public Position getPosition() {
            return position;
        }
    }

    /** Thrown when an attempt is made to place a piece on a cell that is already occupied. */
    public static class OccupiedCellException extends Exception {
        private final Position position;

        public OccupiedCellException(Position position) {
            super("Cell already occupied at " + position);
            this.position = position;
        }

        /** @return the position that caused the conflict */
        public Position getPosition() {
            return position;
        }
    }
}
