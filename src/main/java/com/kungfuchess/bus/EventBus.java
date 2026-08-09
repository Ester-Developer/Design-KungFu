package com.kungfuchess.bus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Thread-safe in-process pub/sub event bus.
 *
 * <p>Subscribers register interest in specific topics via {@link #subscribe(String, Consumer)}.
 * Publishers broadcast events to all subscribers of a topic via {@link #publish(String, Object)}.
 *
 * <p>Uses {@link ConcurrentHashMap} for topic storage and {@link CopyOnWriteArrayList} for
 * subscriber lists to ensure thread-safe concurrent publication and subscription without
 * external synchronization.</p>
 */
public class EventBus {

    // Event topic constants
    public static final String SCORE_UPDATED = "score_updated";
    public static final String MOVE_LOGGED = "move_logged";
    public static final String SOUND_TRIGGERED = "sound_triggered";
    public static final String GAME_STARTED = "game_started";
    public static final String GAME_ENDED = "game_ended";

    private final Map<String, List<Consumer<Object>>> subscribers = new ConcurrentHashMap<>();

    /**
     * Registers a subscriber for the given topic.
     *
     * <p>The same subscriber may be registered multiple times; each registration will
     * result in a separate invocation when events are published to that topic.</p>
     *
     * @param topic the event topic to subscribe to
     * @param subscriber the callback to invoke when events are published to this topic
     */
    public void subscribe(String topic, Consumer<Object> subscriber) {
        subscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(subscriber);
    }

    /**
     * Publishes an event to all subscribers of the given topic.
     *
     * <p>If no subscribers are registered for the topic, the event is silently ignored.
     * Subscribers are invoked synchronously in registration order. If a subscriber throws
     * an exception, it is caught and does not prevent subsequent subscribers from receiving
     * the event.</p>
     *
     * @param topic the event topic
     * @param payload the event payload (may be {@code null})
     */
    public void publish(String topic, Object payload) {
        List<Consumer<Object>> topicSubscribers = subscribers.get(topic);
        if (topicSubscribers != null) {
            for (Consumer<Object> subscriber : topicSubscribers) {
                try {
                    subscriber.accept(payload);
                } catch (Exception e) {
                    // Log the exception but continue notifying other subscribers
                    System.err.println("EventBus: Subscriber threw exception on topic '" 
                        + topic + "': " + e.getMessage());
                }
            }
        }
    }

    /**
     * Removes a specific subscriber from a topic.
     *
     * <p>Only removes the first matching instance if the subscriber was registered
     * multiple times.</p>
     *
     * @param topic the event topic
     * @param subscriber the subscriber to remove
     * @return {@code true} if the subscriber was found and removed, {@code false} otherwise
     */
    public boolean unsubscribe(String topic, Consumer<Object> subscriber) {
        List<Consumer<Object>> topicSubscribers = subscribers.get(topic);
        return topicSubscribers != null && topicSubscribers.remove(subscriber);
    }

    /**
     * Removes all subscribers from a topic.
     *
     * @param topic the event topic to clear
     */
    public void clearTopic(String topic) {
        subscribers.remove(topic);
    }

    /**
     * Removes all subscribers from all topics.
     */
    public void clearAll() {
        subscribers.clear();
    }

    /**
     * Returns the number of subscribers for a given topic.
     *
     * @param topic the event topic
     * @return the number of registered subscribers, or 0 if the topic has no subscribers
     */
    public int subscriberCount(String topic) {
        List<Consumer<Object>> topicSubscribers = subscribers.get(topic);
        return topicSubscribers != null ? topicSubscribers.size() : 0;
    }
}
