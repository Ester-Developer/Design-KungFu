package com.kungfuchess.io;

import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;

import java.util.Map;

/**
 * Strategy interface for parsing an external representation into a {@link Board}.
 *
 * <p>Implement this interface to support different input formats (text, binary, FEN,
 * etc.) without modifying the core board model.</p>
 *
 * @param <T> the raw input type (e.g. {@link String}, {@code byte[]})
 */
public interface BoardParser<T> {

    /**
     * Parses the given input and returns a populated board.
     *
     * @param input raw board representation
     * @return a fully populated {@link Board}
     * @throws BoardParseException if the input is malformed or cannot be interpreted
     */
    Board parse(T input) throws BoardParseException;

    /**
     * Thrown when the supplied input cannot be interpreted as a valid board.
     *
     * <p>Carries a machine-readable {@link ErrorCode} in addition to the human-readable
     * message, so callers (e.g. {@code Main}) can report a stable, testable error
     * string such as {@code "ERROR UNKNOWN_TOKEN"} without parsing free text.</p>
     */
    class BoardParseException extends Exception {

        /** Stable, machine-readable classification of why parsing failed. */
        public enum ErrorCode {
            /** A cell token was neither {@code "."} nor a valid {@code <color><kind>} pair. */
            UNKNOWN_TOKEN,
            /** A row did not have the same number of cells as the first row. */
            ROW_WIDTH_MISMATCH,
            /** Any other malformed input (blank input, internal placement failure, etc.). */
            INVALID_INPUT
        }

        private final ErrorCode errorCode;

        public BoardParseException(String message) {
            this(message, ErrorCode.INVALID_INPUT);
        }

        public BoardParseException(String message, ErrorCode errorCode) {
            super(message);
            this.errorCode = errorCode;
        }

        public BoardParseException(String message, Throwable cause) {
            this(message, ErrorCode.INVALID_INPUT, cause);
        }

        public BoardParseException(String message, ErrorCode errorCode, Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode;
        }

        /** @return the machine-readable classification of this failure */
        public ErrorCode getErrorCode() {
            return errorCode;
        }
    }

    /**
     * Single source of truth for the compact text notation used to read and write
     * boards: a two-character token per cell — color code ({@code w}/{@code b})
     * followed by a piece code ({@code K Q R B N P}) — or {@code "."} for an empty cell.
     *
     * <p>Shared by {@link TextParser} and {@link BoardPrinter.TextPrinter} so a board
     * that is printed and re-parsed always round-trips exactly.</p>
     */
    final class PieceNotation {

        /** Token used for an unoccupied cell. */
        public static final String EMPTY_TOKEN = ".";

        private static final Map<Character, String> COLOR_BY_CODE = Map.of('w', "white", 'b', "black");
        private static final Map<Character, String> KIND_BY_CODE = Map.of(
            'K', "King", 'Q', "Queen", 'R', "Rook", 'B', "Bishop", 'N', "Knight", 'P', "Pawn");
        private static final Map<String, Character> CODE_BY_COLOR = Map.of("white", 'w', "black", 'b');
        private static final Map<String, Character> CODE_BY_KIND = Map.of(
            "King", 'K', "Queen", 'Q', "Rook", 'R', "Bishop", 'B', "Knight", 'N', "Pawn", 'P');

        private PieceNotation() {}

        /** @return {@code true} if the token is a well-formed color+kind pair, e.g. {@code "wK"}. */
        public static boolean isValidToken(String token) {
            return token != null
                && token.length() == 2
                && COLOR_BY_CODE.containsKey(token.charAt(0))
                && KIND_BY_CODE.containsKey(token.charAt(1));
        }

        /** @return the full color name (e.g. {@code "white"}) encoded by a valid token. */
        public static String colorOf(String token) {
            return COLOR_BY_CODE.get(token.charAt(0));
        }

        /** @return the full piece kind (e.g. {@code "King"}) encoded by a valid token. */
        public static String kindOf(String token) {
            return KIND_BY_CODE.get(token.charAt(1));
        }

        /** @return the two-character token for the given piece kind/color, e.g. {@code "wK"}. */
        public static String encode(String kind, String color) {
            Character colorCode = CODE_BY_COLOR.get(color);
            Character kindCode = CODE_BY_KIND.get(kind);
            if (colorCode == null || kindCode == null) {
                throw new IllegalArgumentException("Unknown piece kind/color: " + kind + "/" + color);
            }
            return "" + colorCode + kindCode;
        }
    }

    /**
     * Parses the plain-text board notation (see {@link PieceNotation}) into a {@link Board}.
     *
     * <h2>Format</h2>
     * <pre>
     * wK . . bK
     * .  .  . .
     * wR . . bR
     * </pre>
     *
     * <p>Every row must have the same number of cells as the first row, and every
     * non-empty token must be a recognised {@code <color><kind>} pair — otherwise
     * parsing fails with a {@link BoardParseException} carrying {@code ROW_WIDTH_MISMATCH}
     * or {@code UNKNOWN_TOKEN} respectively.</p>
     */
    final class TextParser implements BoardParser<String> {

        private static final String EMPTY_CELL = PieceNotation.EMPTY_TOKEN;
        private static final String CELL_SEPARATOR = "\\s+";

        /** {@inheritDoc} */
        @Override
        public Board parse(String input) throws BoardParseException {
            if (input == null || input.isBlank()) {
                throw new BoardParseException("Input must not be null or blank", BoardParseException.ErrorCode.INVALID_INPUT);
            }

            String[] rows = input.strip().split("\\r?\\n");
            int height = rows.length;
            int width = rows[0].strip().split(CELL_SEPARATOR, -1).length;

            // First pass: validate every row's width and every token's shape before touching
            // the board, so a malformed row later in the input can't leave a half-built board.
            String[][] grid = new String[height][];
            for (int r = 0; r < height; r++) {
                String[] cells = rows[r].strip().split(CELL_SEPARATOR, -1);
                if (cells.length != width) {
                    throw new BoardParseException(
                        "Row " + r + " has " + cells.length + " cells but expected " + width,
                        BoardParseException.ErrorCode.ROW_WIDTH_MISMATCH);
                }
                for (String cell : cells) {
                    if (!EMPTY_CELL.equals(cell) && !PieceNotation.isValidToken(cell)) {
                        throw new BoardParseException(
                            "Unknown token '" + cell + "'", BoardParseException.ErrorCode.UNKNOWN_TOKEN);
                    }
                }
                grid[r] = cells;
            }

            // Second pass: build the board now that every token is known-good.
            Board board = Board.create(width, height);
            for (int r = 0; r < height; r++) {
                for (int c = 0; c < width; c++) {
                    String cell = grid[r][c];
                    if (EMPTY_CELL.equals(cell)) continue;

                    try {
                        board.addPiece(
                            new Position(r, c),
                            new Piece(PieceNotation.kindOf(cell), PieceNotation.colorOf(cell)));
                    } catch (Board.OutOfBoundsException | Board.OccupiedCellException e) {
                        throw new BoardParseException(
                            "Failed to place piece at row=" + r + " col=" + c,
                            BoardParseException.ErrorCode.INVALID_INPUT, e);
                    }
                }
            }
            return board;
        }
    }
}
