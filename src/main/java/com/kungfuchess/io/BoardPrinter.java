package com.kungfuchess.io;

import com.kungfuchess.model.Board;
import com.kungfuchess.model.Position;

/**
 * Strategy interface for serialising a {@link Board} to an external representation.
 *
 * <p>Implement this interface to support different output formats (plain text, JSON,
 * SVG, etc.) without modifying the core board model.</p>
 *
 * @param <T> the output type (e.g. {@link String}, {@code byte[]})
 */
public interface BoardPrinter<T> {

    /**
     * Serialises the board to the target representation.
     *
     * @param board the board to serialise
     * @return the serialised form
     */
    T print(Board board);

    /**
     * Renders a {@link Board} as a plain-text grid.
     *
     * <p>Output mirrors the format accepted by {@link BoardParser.TextParser}: rows
     * separated by {@code \n}, cells separated by a single space, empty cells shown as
     * {@code "."}, occupied cells as the two-character {@link BoardParser.PieceNotation}
     * token (e.g. {@code "wK"}).</p>
     */
    final class TextPrinter implements BoardPrinter<String> {

        private static final String CELL_SEPARATOR = " ";

        /** {@inheritDoc} */
        @Override
        public String print(Board board) {
            StringBuilder sb = new StringBuilder();
            for (int r = 0; r < board.getHeight(); r++) {
                for (int c = 0; c < board.getWidth(); c++) {
                    if (c > 0) sb.append(CELL_SEPARATOR);
                    try {
                        board.pieceAt(new Position(r, c))
                             .ifPresentOrElse(
                                 p -> sb.append(BoardParser.PieceNotation.encode(p.getKind(), p.getColor())),
                                 () -> sb.append(BoardParser.PieceNotation.EMPTY_TOKEN)
                             );
                    } catch (Board.OutOfBoundsException e) {
                        // Cannot happen: we iterate within board dimensions
                        throw new IllegalStateException("Unexpected out-of-bounds during print", e);
                    }
                }
                if (r < board.getHeight() - 1) sb.append('\n');
            }
            return sb.toString();
        }
    }
}
