package com.kungfuchess.bus;

import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.io.BoardParser;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Position;

/**
 * Demonstration program showing the EventBus in action with GameEngine.
 *
 * <p>Creates a game, subscribes a console logger to all events, and makes a few moves
 * to show event publications flowing through the system.</p>
 */
public class EventBusDemo {

    private static final String STARTING_BOARD =
        "bR bN bB bQ bK bB bN bR\n" +
        "bP bP bP bP bP bP bP bP\n" +
        " .  .  .  .  .  .  .  .\n" +
        " .  .  .  .  .  .  .  .\n" +
        " .  .  .  .  .  .  .  .\n" +
        " .  .  .  .  .  .  .  .\n" +
        "wP wP wP wP wP wP wP wP\n" +
        "wR wN wB wQ wK wB wN wR";

    public static void main(String[] args) throws Exception {
        System.out.println("=== EventBus Demo ===\n");

        // Create an EventBus and attach logger BEFORE creating the engine
        EventBus eventBus = new EventBus();
        ConsoleEventLogger logger = new ConsoleEventLogger(eventBus);

        System.out.println("Console logger attached.\n");

        // Create board with standard starting position
        Board board = new BoardParser.TextParser().parse(STARTING_BOARD);
        
        // Create a game engine with our event bus (triggers GAME_STARTED)
        GameEngine engine = new GameEngine(eventBus).setBoard(board);

        System.out.println("\nNow making moves...\n");

        // Move 1: White pawn from (6,4) to (5,4)
        System.out.println("Move 1: White pawn e2->e3");
        engine.requestMove(new Position(6, 4), new Position(5, 4));
        engine.waitMs(2000);

        // Move 2: Black pawn from (1,4) to (2,4)
        System.out.println("\nMove 2: Black pawn e7->e6");
        engine.requestMove(new Position(1, 4), new Position(2, 4));
        engine.waitMs(2000);

        // Move 3: White pawn from (5,4) to (4,4)
        System.out.println("\nMove 3: White pawn e3->e4");
        engine.requestMove(new Position(5, 4), new Position(4, 4));
        engine.waitMs(2000);

        // Move 4: Black pawn from (1,3) to (3,3) (two squares)
        System.out.println("\nMove 4: Black pawn d7->d5");
        engine.requestMove(new Position(1, 3), new Position(3, 3));
        engine.waitMs(3000);

        // Move 5: White pawn captures black pawn
        System.out.println("\nMove 5: White pawn e4xd5 (CAPTURE!)");
        engine.requestMove(new Position(4, 4), new Position(3, 3));
        engine.waitMs(2000);

        System.out.println("\n=== Demo Complete ===");
        System.out.println("You should see [EventBus] log messages above showing:");
        System.out.println("- GAME_STARTED at the beginning");
        System.out.println("- MOVE_LOGGED for each completed move");
        System.out.println("- SCORE_UPDATED when the capture occurred");
    }
}
