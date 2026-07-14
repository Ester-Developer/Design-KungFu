package com.kungfuchess.model;

import java.util.Objects;

/**
 * Immutable value object representing a cell coordinate on the board.
 * Row and column are zero-based.
 */
public final class Position {

    private final int row;
    private final int col;

    /**
     * @param row zero-based row index
     * @param col zero-based column index
     */
    public Position(int row, int col) {
        this.row = row;
        this.col = col;
    }

    /** @return zero-based row index */
    public int getRow() { return row; }

    /** @return zero-based column index */
    public int getCol() { return col; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Position)) return false;
        Position other = (Position) o;
        return row == other.row && col == other.col;
    }

    @Override
    public int hashCode() {
        return Objects.hash(row, col);
    }

    @Override
    public String toString() {
        return "Position(" + row + ", " + col + ")";
    }
}
