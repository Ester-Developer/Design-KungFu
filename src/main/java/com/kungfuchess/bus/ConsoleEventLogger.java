package com.kungfuchess.bus;

import java.util.function.Consumer;

/**
 * Simple console logger that subscribes to event bus topics and prints events to System.out.
 *
 * <p>Primarily for debugging and demonstrating event flow. Real subscribers for sound
 * and UI updates will be added later.</p>
 */
public class ConsoleEventLogger {

    private final EventBus eventBus;

    /**
     * Creates a logger and subscribes it to all standard event topics.
     *
     * @param eventBus the event bus to subscribe to
     */
    public ConsoleEventLogger(EventBus eventBus) {
        this.eventBus = eventBus;
        subscribeToAll();
    }

    /**
     * Subscribes this logger to all standard event topics.
     */
    private void subscribeToAll() {
        eventBus.subscribe(EventBus.SCORE_UPDATED, createLogger("SCORE_UPDATED"));
        eventBus.subscribe(EventBus.MOVE_LOGGED, createLogger("MOVE_LOGGED"));
        eventBus.subscribe(EventBus.SOUND_TRIGGERED, createLogger("SOUND_TRIGGERED"));
        eventBus.subscribe(EventBus.GAME_STARTED, createLogger("GAME_STARTED"));
        eventBus.subscribe(EventBus.GAME_ENDED, createLogger("GAME_ENDED"));
    }

    /**
     * Creates a consumer that logs events for a specific topic.
     *
     * @param topicName the name of the topic (for log output)
     * @return a consumer that logs the event
     */
    private Consumer<Object> createLogger(String topicName) {
        return payload -> {
            System.out.println("[EventBus] " + topicName + ": " + payload);
        };
    }
}
