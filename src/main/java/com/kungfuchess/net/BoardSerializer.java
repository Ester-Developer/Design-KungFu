package com.kungfuchess.net;

import com.kungfuchess.io.BoardParser;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Position;

/**
 * Utility for converting Board to 2D array format for JSON serialization.
 */
public final class BoardSerializer {

    private BoardSerializer() {}

    /**
     * Converts a Board to a 2D array of piece notations.
     *
     * @param board the board to serialize
     * @return 2D array where each cell is a piece notation (e.g., "wP", "bK") or "."
     */
    public static String[][] toArray(Board board) {
        String[][] result = new String[board.getHeight()][board.getWidth()];
        
        for (int row = 0; row < board.getHeight(); row++) {
            for (int col = 0; col < board.getWidth(); col++) {
                try {
                    Position pos = new Position(row, col);
                    final int r = row;
                    final int c = col;
                    board.pieceAt(pos).ifPresentOrElse(
                        piece -> result[r][c] = BoardParser.PieceNotation.encode(
                            piece.getKind(), piece.getColor()),
                        () -> result[r][c] = "."
                    );
                } catch (Board.OutOfBoundsException e) {
                    result[row][col] = ".";
                }
            }
        }
        
        return result;
    }

    /**
     * Converts a 2D array board representation to a formatted string for display.
     *
     * @param board the 2D array board
     * @return formatted string representation
     */
    public static String formatBoard(String[][] board) {
        if (board == null || board.length == 0) {
            return "(empty board)";
        }
        
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < board.length; row++) {
            for (int col = 0; col < board[row].length; col++) {
                if (col > 0) sb.append(" ");
                String cell = board[row][col];
                if (cell.length() == 1) {
                    sb.append(" ").append(cell);
                } else {
                    sb.append(cell);
                }
            }
            if (row < board.length - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
