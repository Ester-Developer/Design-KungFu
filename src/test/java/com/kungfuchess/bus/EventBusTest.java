package com.kungfuchess.bus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EventBusTest {

    private EventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
    }

    @Test
    void testPublishAndSubscribe() {
        List<Object> receivedEvents = new ArrayList<>();

        eventBus.subscribe("test_topic", receivedEvents::add);
        eventBus.publish("test_topic", "Hello World");

        assertEquals(1, receivedEvents.size());
        assertEquals("Hello World", receivedEvents.get(0));
    }

    @Test
    void testMultipleSubscribers() {
        List<Object> subscriber1Events = new ArrayList<>();
        List<Object> subscriber2Events = new ArrayList<>();

        eventBus.subscribe("test_topic", subscriber1Events::add);
        eventBus.subscribe("test_topic", subscriber2Events::add);
        eventBus.publish("test_topic", "Event 1");

        assertEquals(1, subscriber1Events.size());
        assertEquals(1, subscriber2Events.size());
        assertEquals("Event 1", subscriber1Events.get(0));
        assertEquals("Event 1", subscriber2Events.get(0));
    }

    @Test
    void testMultipleEvents() {
        List<Object> receivedEvents = new ArrayList<>();

        eventBus.subscribe("test_topic", receivedEvents::add);
        eventBus.publish("test_topic", "Event 1");
        eventBus.publish("test_topic", "Event 2");
        eventBus.publish("test_topic", "Event 3");

        assertEquals(3, receivedEvents.size());
        assertEquals("Event 1", receivedEvents.get(0));
        assertEquals("Event 2", receivedEvents.get(1));
        assertEquals("Event 3", receivedEvents.get(2));
    }

    @Test
    void testPublishToNonExistentTopic() {
        // Should not throw exception
        assertDoesNotThrow(() -> eventBus.publish("nonexistent_topic", "Test"));
    }

    @Test
    void testUnsubscribe() {
        List<Object> receivedEvents = new ArrayList<>();
        java.util.function.Consumer<Object> subscriber = receivedEvents::add;

        eventBus.subscribe("test_topic", subscriber);
        eventBus.publish("test_topic", "Event 1");

        boolean removed = eventBus.unsubscribe("test_topic", subscriber);
        assertTrue(removed);

        eventBus.publish("test_topic", "Event 2");

        // Should only have received the first event
        assertEquals(1, receivedEvents.size());
        assertEquals("Event 1", receivedEvents.get(0));
    }

    @Test
    void testClearTopic() {
        List<Object> receivedEvents = new ArrayList<>();

        eventBus.subscribe("test_topic", receivedEvents::add);
        eventBus.clearTopic("test_topic");
        eventBus.publish("test_topic", "Event 1");

        assertEquals(0, receivedEvents.size());
    }

    @Test
    void testClearAll() {
        List<Object> topic1Events = new ArrayList<>();
        List<Object> topic2Events = new ArrayList<>();

        eventBus.subscribe("topic1", topic1Events::add);
        eventBus.subscribe("topic2", topic2Events::add);

        eventBus.clearAll();

        eventBus.publish("topic1", "Event 1");
        eventBus.publish("topic2", "Event 2");

        assertEquals(0, topic1Events.size());
        assertEquals(0, topic2Events.size());
    }

    @Test
    void testSubscriberCount() {
        assertEquals(0, eventBus.subscriberCount("test_topic"));

        eventBus.subscribe("test_topic", obj -> {});
        assertEquals(1, eventBus.subscriberCount("test_topic"));

        eventBus.subscribe("test_topic", obj -> {});
        assertEquals(2, eventBus.subscriberCount("test_topic"));
    }

    @Test
    void testNullPayload() {
        List<Object> receivedEvents = new ArrayList<>();

        eventBus.subscribe("test_topic", receivedEvents::add);
        eventBus.publish("test_topic", null);

        assertEquals(1, receivedEvents.size());
        assertNull(receivedEvents.get(0));
    }

    @Test
    void testSubscriberException() {
        List<Object> goodSubscriberEvents = new ArrayList<>();

        // First subscriber throws exception
        eventBus.subscribe("test_topic", obj -> {
            throw new RuntimeException("Test exception");
        });

        // Second subscriber should still receive event
        eventBus.subscribe("test_topic", goodSubscriberEvents::add);

        eventBus.publish("test_topic", "Event 1");

        // Good subscriber should have received the event despite first subscriber throwing
        assertEquals(1, goodSubscriberEvents.size());
        assertEquals("Event 1", goodSubscriberEvents.get(0));
    }

    @Test
    void testThreadSafety() throws InterruptedException {
        int threadCount = 10;
        int eventsPerThread = 100;
        AtomicInteger receivedCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount);

        eventBus.subscribe("test_topic", obj -> receivedCount.incrementAndGet());

        // Spawn multiple threads publishing events concurrently
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                for (int j = 0; j < eventsPerThread; j++) {
                    eventBus.publish("test_topic", "Event");
                }
                latch.countDown();
            }).start();
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(threadCount * eventsPerThread, receivedCount.get());
    }

    @Test
    void testEventTopicConstants() {
        // Verify the expected topic constants exist
        assertEquals("score_updated", EventBus.SCORE_UPDATED);
        assertEquals("move_logged", EventBus.MOVE_LOGGED);
        assertEquals("sound_triggered", EventBus.SOUND_TRIGGERED);
        assertEquals("game_started", EventBus.GAME_STARTED);
        assertEquals("game_ended", EventBus.GAME_ENDED);
    }
}
