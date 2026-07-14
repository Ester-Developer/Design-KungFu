package tests.unit;

import com.kungfuchess.io.BoardParser;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestBoardParser {

    private final BoardParser<String> parser = new BoardParser.TextParser();

    @Test
    void parsesARectangularBoard() throws Exception {
        Board board = parser.parse("wK . . bK\n. . . .\nwR . . bR");

        assertEquals(4, board.getWidth());
        assertEquals(3, board.getHeight());
        assertEquals("King", board.pieceAt(new Position(0, 0)).orElseThrow().getKind());
        assertEquals("white", board.pieceAt(new Position(0, 0)).orElseThrow().getColor());
        assertEquals("King", board.pieceAt(new Position(0, 3)).orElseThrow().getKind());
        assertEquals("black", board.pieceAt(new Position(0, 3)).orElseThrow().getColor());
        assertTrue(board.pieceAt(new Position(1, 1)).isEmpty());
    }

    @Test
    void parsesAllPieceKindsAndColors() throws Exception {
        Board board = parser.parse("wK . bQ\n. wN .\nbP . wR");

        assertEquals("Queen", board.pieceAt(new Position(0, 2)).orElseThrow().getKind());
        assertEquals("black", board.pieceAt(new Position(0, 2)).orElseThrow().getColor());
        assertEquals("Knight", board.pieceAt(new Position(1, 1)).orElseThrow().getKind());
        assertEquals("Pawn", board.pieceAt(new Position(2, 0)).orElseThrow().getKind());
        assertEquals("Rook", board.pieceAt(new Position(2, 2)).orElseThrow().getKind());
    }

    @Test
    void rejectsUnknownToken() {
        BoardParser.BoardParseException ex = assertThrows(BoardParser.BoardParseException.class,
            () -> parser.parse("wK xZ\n. ."));
        assertEquals(BoardParser.BoardParseException.ErrorCode.UNKNOWN_TOKEN, ex.getErrorCode());
    }

    @Test
    void rejectsRowWidthMismatch() {
        BoardParser.BoardParseException ex = assertThrows(BoardParser.BoardParseException.class,
            () -> parser.parse("wK . .\n. bK"));
        assertEquals(BoardParser.BoardParseException.ErrorCode.ROW_WIDTH_MISMATCH, ex.getErrorCode());
    }

    @Test
    void rejectsBlankInput() {
        assertThrows(BoardParser.BoardParseException.class, () -> parser.parse(""));
        assertThrows(BoardParser.BoardParseException.class, () -> parser.parse(null));
    }

    @Test
    void toleratesExtraPaddingSpacesBetweenCells() throws Exception {
        Board board = parser.parse("wK   .      .\n.    .      bK");
        assertEquals(3, board.getWidth());
        assertEquals("King", board.pieceAt(new Position(0, 0)).orElseThrow().getKind());
        assertEquals("King", board.pieceAt(new Position(1, 2)).orElseThrow().getKind());
    }

    @Test
    void pieceNotationRoundTripsEveryKindAndColor() {
        String[] colors = {"white", "black"};
        String[] kinds = {"King", "Queen", "Rook", "Bishop", "Knight", "Pawn"};
        for (String color : colors) {
            for (String kind : kinds) {
                String token = BoardParser.PieceNotation.encode(kind, color);
                assertTrue(BoardParser.PieceNotation.isValidToken(token));
                assertEquals(kind, BoardParser.PieceNotation.kindOf(token));
                assertEquals(color, BoardParser.PieceNotation.colorOf(token));
            }
        }
    }

    @Test
    void pieceNotationRejectsMalformedTokens() {
        assertFalse(BoardParser.PieceNotation.isValidToken("xZ"));
        assertFalse(BoardParser.PieceNotation.isValidToken("w"));
        assertFalse(BoardParser.PieceNotation.isValidToken("wKK"));
    }
}
