package tests.unit;

import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.input.BoardMapper;
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
        // Cell (1,1) center: BOARD_X_OFFSET + 1*100 + 50, BOARD_Y_OFFSET + 1*100 + 50
        int x = BoardMapper.BOARD_X_OFFSET + 150;
        int y = BoardMapper.BOARD_Y_OFFSET + 150;
        Controller.ControllerResult result = controller.click(x, y);
        assertFalse(result.moveRequested());
        assertTrue(controller.getSelected().isEmpty());
    }

    @Test
    void firstClickOnAPieceSelectsIt() throws Exception {
        // Cell (0,0) center: BOARD_X_OFFSET + 50, BOARD_Y_OFFSET + 50
        int x = BoardMapper.BOARD_X_OFFSET + 50;
        int y = BoardMapper.BOARD_Y_OFFSET + 50;
        controller.click(x, y);
        assertEquals(new Position(0, 0), controller.getSelected().orElseThrow());
    }

    @Test
    void secondInBoardClickRequestsAMoveAndClearsSelection() throws Exception {
        int x00 = BoardMapper.BOARD_X_OFFSET + 50;
        int y00 = BoardMapper.BOARD_Y_OFFSET + 50;
        int x11 = BoardMapper.BOARD_X_OFFSET + 150;
        int y11 = BoardMapper.BOARD_Y_OFFSET + 150;
        
        controller.click(x00, y00);
        Controller.ControllerResult result = controller.click(x11, y11);

        assertTrue(result.moveRequested());
        assertEquals(new Position(0, 0), result.source());
        assertEquals(new Position(1, 1), result.destination());
        assertTrue(controller.getSelected().isEmpty());
    }

    @Test
    void selectionClearsEvenWhenTheRequestedMoveIsIllegal() throws Exception {
        int x00 = BoardMapper.BOARD_X_OFFSET + 50;
        int y00 = BoardMapper.BOARD_Y_OFFSET + 50;
        int x22 = BoardMapper.BOARD_X_OFFSET + 250;
        int y22 = BoardMapper.BOARD_Y_OFFSET + 250;
        
        controller.click(x00, y00);
        controller.click(x22, y22); // king can't move 2 squares diagonally

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
        int x00 = BoardMapper.BOARD_X_OFFSET + 50;
        int y00 = BoardMapper.BOARD_Y_OFFSET + 50;
        
        controller.click(x00, y00);
        assertTrue(controller.getSelected().isPresent());

        Controller.ControllerResult result = controller.click(-50, -50);

        assertFalse(result.moveRequested());
        assertTrue(controller.getSelected().isEmpty());
    }
}
