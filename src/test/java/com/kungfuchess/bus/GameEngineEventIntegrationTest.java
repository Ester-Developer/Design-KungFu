package com.kungfuchess.bus;

import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.io.BoardParser;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameEngineEventIntegrationTest {

    private static final String STARTING_BOARD =
        "bR bN bB bQ bK bB bN bR\n" +
        "bP bP bP bP bP bP bP bP\n" +
        " .  .  .  .  .  .  .  .\n" +
        " .  .  .  .  .  .  .  .\n" +
        " .  .  .  .  .  .  .  .\n" +
        " .  .  .  .  .  .  .  .\n" +
        "wP wP wP wP wP wP wP wP\n" +
        "wR wN wB wQ wK wB wN wR";

    private EventBus eventBus;
    private GameEngine engine;
    private List<Object> gameStartedEvents;
    private List<Object> moveLoggedEvents;
    private List<Object> scoreUpdatedEvents;
    private List<Object> gameEndedEvents;

    @BeforeEach
    void setUp() throws Exception {
        eventBus = new EventBus();
        gameStartedEvents = new ArrayList<>();
        moveLoggedEvents = new ArrayList<>();
        scoreUpdatedEvents = new ArrayList<>();
        gameEndedEvents = new ArrayList<>();

        // Subscribe to all event topics
        eventBus.subscribe(EventBus.GAME_STARTED, gameStartedEvents::add);
        eventBus.subscribe(EventBus.MOVE_LOGGED, moveLoggedEvents::add);
        eventBus.subscribe(EventBus.SCORE_UPDATED, scoreUpdatedEvents::add);
        eventBus.subscribe(EventBus.GAME_ENDED, gameEndedEvents::add);

        // Create engine with our event bus (this triggers GAME_STARTED), then
        // load a standard starting position — the default board is empty.
        Board board = new BoardParser.TextParser().parse(STARTING_BOARD);
        engine = new GameEngine(eventBus).setBoard(board);
    }

    @Test
    void testGameStartedEventOnCreation() {
        // GameEngine constructor should publish GAME_STARTED
        assertEquals(1, gameStartedEvents.size());
        assertEquals("Game started", gameStartedEvents.get(0));
    }

    @Test
    void testMoveLoggedEvent() throws Exception {
        // Make a move: white pawn from (6,0) to (5,0)
        engine.requestMove(new Position(6, 0), new Position(5, 0));
        
        // Advance time to complete the move
        engine.waitMs(1000);

        // Should have logged the move
        assertTrue(moveLoggedEvents.size() > 0, "Expected at least one MOVE_LOGGED event");
        
        // Verify the event payload is a MoveLogEntry
        Object firstEvent = moveLoggedEvents.get(0);
        assertInstanceOf(GameEngine.GameSnapshot.MoveLogEntry.class, firstEvent);
        
        GameEngine.GameSnapshot.MoveLogEntry entry = (GameEngine.GameSnapshot.MoveLogEntry) firstEvent;
        assertEquals("Pawn", entry.pieceKind());
        assertEquals("white", entry.color());
    }

    @Test
    void testScoreUpdatedEventOnCapture() throws Exception {
        // Set up a scenario where white can capture black's piece
        // Move white pawn forward (6,4) -> (4,4); wait out its full travel + rest-chain
        // cooldown so the same piece is free to move again below.
        engine.requestMove(new Position(6, 4), new Position(4, 4));
        engine.waitMs(4000);

        // Move black pawn forward (1,3) -> (3,3)
        engine.requestMove(new Position(1, 3), new Position(3, 3));
        engine.waitMs(1000);
        
        // Capture: white pawn (4,4) -> (3,3) captures black pawn
        engine.requestMove(new Position(4, 4), new Position(3, 3));
        engine.waitMs(1000);

        // Should have at least one score update (from the capture)
        assertTrue(scoreUpdatedEvents.size() > 0, "Expected at least one SCORE_UPDATED event");
        
        // Verify it's a string describing the score update
        Object lastScoreEvent = scoreUpdatedEvents.get(scoreUpdatedEvents.size() - 1);
        assertInstanceOf(String.class, lastScoreEvent);
        assertTrue(((String) lastScoreEvent).contains("score"));
    }

    @Test
    void testEventBusAccessibleFromEngine() {
        assertNotNull(engine.getEventBus());
        assertSame(eventBus, engine.getEventBus());
    }

    @Test
    void testMultipleMoveEvents() throws Exception {
        int initialMoveCount = moveLoggedEvents.size();
        
        // Make two moves
        engine.requestMove(new Position(6, 0), new Position(5, 0));
        engine.waitMs(1000);
        
        engine.requestMove(new Position(1, 0), new Position(2, 0));
        engine.waitMs(1000);

        // Should have logged both moves
        assertEquals(initialMoveCount + 2, moveLoggedEvents.size());
    }

    @Test
    void testConsoleEventLogger() {
        // Test that ConsoleEventLogger can be instantiated and subscribes successfully
        ConsoleEventLogger logger = new ConsoleEventLogger(eventBus);
        
        // After creating the logger, there should be subscribers on all topics
        assertTrue(eventBus.subscriberCount(EventBus.SCORE_UPDATED) > 0);
        assertTrue(eventBus.subscriberCount(EventBus.MOVE_LOGGED) > 0);
        assertTrue(eventBus.subscriberCount(EventBus.SOUND_TRIGGERED) > 0);
        assertTrue(eventBus.subscriberCount(EventBus.GAME_STARTED) > 0);
        assertTrue(eventBus.subscriberCount(EventBus.GAME_ENDED) > 0);
    }
}
