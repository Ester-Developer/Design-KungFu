package tests.unit;

import com.kungfuchess.io.BoardParser;
import com.kungfuchess.io.BoardPrinter;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestBoardPrinter {

    private final BoardPrinter<String> printer = new BoardPrinter.TextPrinter();

    @Test
    void printsAnEmptyBoardAsDots() throws Exception {
        Board board = Board.create(2, 2);
        assertEquals(". .\n. .", printer.print(board));
    }

    @Test
    void printsPiecesInCompactNotation() throws Exception {
        Board board = Board.create(4, 1);
        board.addPiece(new Position(0, 0), new Piece("King", "white"));
        board.addPiece(new Position(0, 3), new Piece("King", "black"));

        assertEquals("wK . . bK", printer.print(board));
    }

    @Test
    void printThenParseRoundTrips() throws Exception {
        Board original = Board.create(3, 2);
        original.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        original.addPiece(new Position(1, 2), new Piece("Queen", "black"));

        String text = printer.print(original);
        Board reparsed = new BoardParser.TextParser().parse(text);

        assertEquals(printer.print(original), printer.print(reparsed));
    }
}
