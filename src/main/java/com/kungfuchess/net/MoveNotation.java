package com.kungfuchess.net;

import com.kungfuchess.model.Position;

/**
 * Utility for parsing and encoding chess moves in algebraic notation.
 *
 * <p>Converts between algebraic notation (e.g. "e2e4") and {@link Position} pairs.
 * Standard chess notation where columns are a-h (left to right) and rows are 8-1
 * (top to bottom on screen, which corresponds to board rows 0-7).</p>
 */
public final class MoveNotation {

    private MoveNotation() {}

    /**
     * Parses a move string like "e2e4" into source and destination positions.
     *
     * @param move the move string (4 characters: from-col, from-row, to-col, to-row)
     * @return array of [from, to] positions
     * @throws IllegalArgumentException if the move string is malformed
     */
    public static Position[] parseMove(String move) {
        if (move == null || move.length() != 4) {
            throw new IllegalArgumentException("Move must be exactly 4 characters (e.g. 'e2e4')");
        }

        char fromCol = move.charAt(0);
        char fromRow = move.charAt(1);
        char toCol = move.charAt(2);
        char toRow = move.charAt(3);

        Position from = parseSquare(fromCol, fromRow);
        Position to = parseSquare(toCol, toRow);

        return new Position[]{from, to};
    }

    /**
     * Parses a single square like "e2" into a Position.
     *
     * @param col column letter ('a'-'h')
     * @param row row digit ('1'-'8')
     * @return the Position
     * @throws IllegalArgumentException if the square is malformed
     */
    public static Position parseSquare(char col, char row) {
        if (col < 'a' || col > 'h') {
            throw new IllegalArgumentException("Column must be a-h, got: " + col);
        }
        if (row < '1' || row > '8') {
            throw new IllegalArgumentException("Row must be 1-8, got: " + row);
        }

        // Column: a=0, b=1, ..., h=7
        int colIndex = col - 'a';

        // Row: 8=0 (top), 7=1, ..., 1=7 (bottom)
        // In algebraic notation, row '8' is at the top (board row 0)
        int rowIndex = '8' - row;

        return new Position(rowIndex, colIndex);
    }

    /**
     * Encodes a Position back to algebraic notation (e.g. "e2").
     *
     * @param pos the position
     * @return the algebraic square notation (e.g. "e2")
     */
    public static String encodeSquare(Position pos) {
        char col = (char) ('a' + pos.getCol());
        char row = (char) ('8' - pos.getRow());
        return "" + col + row;
    }

    /**
     * Encodes a move as algebraic notation (e.g. "e2e4").
     *
     * @param from source position
     * @param to destination position
     * @return the move string
     */
    public static String encodeMove(Position from, Position to) {
        return encodeSquare(from) + encodeSquare(to);
    }
}
