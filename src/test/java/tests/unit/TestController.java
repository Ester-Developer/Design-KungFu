package tests.unit;

import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.input.Controller;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestController {

    private GameEngine engine;
    private Controller controller;

    @BeforeEach
    void setUp() throws Exception {
        Board board = Board.create(3, 3);
        board.addPiece(new Position(0, 0), new Piece("King", "white"));
        engine = new GameEngine().setBoard(board);
        controller = engine.getController();
    }

    @Test
    void firstClickOnEmptyCellIsIgnored() throws Exception {
        Controller.ControllerResult result = controller.click(150, 150); // (1,1) is empty
        assertFalse(result.moveRequested());
        assertTrue(controller.getSelected().isEmpty());
    }

    @Test
    void firstClickOnAPieceSelectsIt() throws Exception {
        controller.click(50, 50); // (0,0) has the king
        assertEquals(new Position(0, 0), controller.getSelected().orElseThrow());
    }

    @Test
    void secondInBoardClickRequestsAMoveAndClearsSelection() throws Exception {
        controller.click(50, 50);
        Controller.ControllerResult result = controller.click(150, 150);

        assertTrue(result.moveRequested());
        assertEquals(new Position(0, 0), result.source());
        assertEquals(new Position(1, 1), result.destination());
        assertTrue(controller.getSelected().isEmpty());
    }

    @Test
    void selectionClearsEvenWhenTheRequestedMoveIsIllegal() throws Exception {
        controller.click(50, 50);
        controller.click(250, 250); // king can't move 2 squares diagonally

        assertTrue(controller.getSelected().isEmpty());
        assertTrue(engine.getBoard().pieceAt(new Position(0, 0)).isPresent()); // unmoved
    }

    @Test
    void outOfBoundsClickWithNoSelectionIsIgnored() throws Exception {
        Controller.ControllerResult result = controller.click(-50, -50);
        assertFalse(result.moveRequested());
        assertTrue(controller.getSelected().isEmpty());
    }

    @Test
    void outOfBoundsClickWithSelectionCancelsItWithoutSendingACommand() throws Exception {
        controller.click(50, 50);
        assertTrue(controller.getSelected().isPresent());

        Controller.ControllerResult result = controller.click(-50, -50);

        assertFalse(result.moveRequested());
        assertTrue(controller.getSelected().isEmpty());
    }
}
