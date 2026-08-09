package com.kungfuchess.server;

import com.google.gson.Gson;
import com.kungfuchess.net.NoMatchMessage;
import org.java_websocket.WebSocket;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages matchmaking queue for pairing players based on ELO ratings.
 *
 * <p>Players are paired if their ELO ratings differ by at most 100 points.
 * Matching is checked on every PLAY request and every 5 seconds via a scheduled task.
 * Players who wait more than 30 seconds without finding a match are removed from the queue.</p>
 */
public class Matchmaker {

    private static final int MAX_ELO_DIFFERENCE = 100;
    private static final int QUEUE_TIMEOUT_SECONDS = 30;
    private static final int MATCH_CHECK_INTERVAL_SECONDS = 5;

    private final ConcurrentLinkedQueue<QueuedPlayer> queue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Gson gson = new Gson();
    private final MatchCallback matchCallback;

    /**
     * Callback interface for notifying when a match is found.
     */
    public interface MatchCallback {
        void onMatchFound(ServerSession player1, ServerSession player2);
    }

    /**
     * Represents a player waiting in the matchmaking queue.
     */
    private static class QueuedPlayer {
        final ServerSession session;
        final long queuedAtMillis;

        QueuedPlayer(ServerSession session) {
            this.session = session;
            this.queuedAtMillis = System.currentTimeMillis();
        }

        boolean hasTimedOut() {
            long elapsedSeconds = (System.currentTimeMillis() - queuedAtMillis) / 1000;
            return elapsedSeconds >= QUEUE_TIMEOUT_SECONDS;
        }

        long getQueueTimeSeconds() {
            return (System.currentTimeMillis() - queuedAtMillis) / 1000;
        }
    }

    /**
     * Creates a new Matchmaker with the specified callback for match notifications.
     *
     * @param matchCallback callback to invoke when a match is found
     */
    public Matchmaker(MatchCallback matchCallback) {
        this.matchCallback = matchCallback;
        startScheduledMatching();
    }

    /**
     * Starts the scheduled task that checks for matches every 5 seconds.
     */
    private void startScheduledMatching() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkForMatches();
                removeTimedOutPlayers();
            } catch (Exception e) {
                System.err.println("[Matchmaker] Error in scheduled matching: " + e.getMessage());
                e.printStackTrace();
            }
        }, MATCH_CHECK_INTERVAL_SECONDS, MATCH_CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Adds a player to the matchmaking queue and immediately checks for matches.
     *
     * @param session the player session to add to the queue
     */
    public void addToQueue(ServerSession session) {
        QueuedPlayer queuedPlayer = new QueuedPlayer(session);
        queue.add(queuedPlayer);
        
        System.out.println("[Matchmaker] " + session.getUsername() + " (ELO: " + 
                         session.getElo() + ") joined queue. Queue size: " + queue.size());
        
        // Immediately check for matches when a new player joins
        checkForMatches();
    }

    /**
     * Removes a player from the matchmaking queue.
     *
     * @param session the player session to remove
     * @return true if the player was in the queue and removed, false otherwise
     */
    public boolean removeFromQueue(ServerSession session) {
        boolean removed = queue.removeIf(qp -> qp.session.equals(session));
        if (removed) {
            System.out.println("[Matchmaker] " + session.getUsername() + " removed from queue");
        }
        return removed;
    }

    /**
     * Checks if a player is currently in the matchmaking queue.
     *
     * @param session the player session to check
     * @return true if the player is in the queue, false otherwise
     */
    public boolean isInQueue(ServerSession session) {
        return queue.stream().anyMatch(qp -> qp.session.equals(session));
    }

    /**
     * Checks for compatible matches in the queue.
     *
     * <p>Pairs two players if their ELO ratings differ by at most 100 points.
     * Once a match is found, both players are removed from the queue and the
     * match callback is invoked.</p>
     */
    private void checkForMatches() {
        List<QueuedPlayer> players = new ArrayList<>(queue);
        
        // Try to find matches
        for (int i = 0; i < players.size(); i++) {
            QueuedPlayer player1 = players.get(i);
            
            // Check if player1 is still in the queue (might have been matched already)
            if (!queue.contains(player1)) {
                continue;
            }
            
            for (int j = i + 1; j < players.size(); j++) {
                QueuedPlayer player2 = players.get(j);
                
                // Check if player2 is still in the queue
                if (!queue.contains(player2)) {
                    continue;
                }
                
                // Check if ELO difference is acceptable
                int eloDiff = Math.abs(player1.session.getElo() - player2.session.getElo());
                
                if (eloDiff <= MAX_ELO_DIFFERENCE) {
                    // Match found! Remove both from queue
                    queue.remove(player1);
                    queue.remove(player2);
                    
                    System.out.println("[Matchmaker] Match found: " + 
                                     player1.session.getUsername() + " (ELO: " + player1.session.getElo() + ") vs " +
                                     player2.session.getUsername() + " (ELO: " + player2.session.getElo() + ")");
                    
                    // Notify via callback
                    matchCallback.onMatchFound(player1.session, player2.session);
                    
                    // Break out of inner loop since player1 has been matched
                    break;
                }
            }
        }
    }

    /**
     * Removes players who have been in the queue for more than 60 seconds.
     *
     * <p>Sends a NO_MATCH_FOUND message to timed-out players.</p>
     */
    private void removeTimedOutPlayers() {
        Iterator<QueuedPlayer> iterator = queue.iterator();
        
        while (iterator.hasNext()) {
            QueuedPlayer player = iterator.next();
            
            if (player.hasTimedOut()) {
                iterator.remove();
                
                System.out.println("[Matchmaker] " + player.session.getUsername() + 
                                 " timed out after " + player.getQueueTimeSeconds() + " seconds");
                
                // Send NO_MATCH_FOUND message
                try {
                    NoMatchMessage msg = new NoMatchMessage();
                    String json = gson.toJson(msg);
                    player.session.getConnection().send(json);
                } catch (Exception e) {
                    System.err.println("[Matchmaker] Failed to send NO_MATCH_FOUND to " + 
                                     player.session.getUsername() + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Gets the current size of the matchmaking queue.
     *
     * @return the number of players waiting for a match
     */
    public int getQueueSize() {
        return queue.size();
    }

    /**
     * Shuts down the matchmaker and its scheduled tasks.
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
