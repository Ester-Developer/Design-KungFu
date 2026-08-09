package com.kungfuchess.client;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Simple test client that sends a move and waits for the board update.
 */
public class TestClient {

    public static void main(String[] args) throws Exception {
        System.out.println("=== WebSocket Test Client ===\n");

        final CountDownLatch initialBoardLatch = new CountDownLatch(1);
        final CountDownLatch updateBoardLatch = new CountDownLatch(1);
        
        ChessWebSocketClient client = new ChessWebSocketClient(new URI("ws://localhost:8887")) {
            private int messageCount = 0;
            
            @Override
            public void onMessage(String message) {
                super.onMessage(message);
                messageCount++;
                if (messageCount == 1) {
                    initialBoardLatch.countDown();
                } else {
                    updateBoardLatch.countDown();
                }
            }
        };

        System.out.println("Connecting to server...");
        client.connectBlocking();

        // Wait for initial board
        System.out.println("Waiting for initial board...");
        boolean gotInitial = initialBoardLatch.await(2, TimeUnit.SECONDS);
        if (!gotInitial) {
            System.err.println("Timeout waiting for initial board");
            client.close();
            return;
        }
        System.out.println("Initial board received!");

        // Send a move
        System.out.println("\nSending move: d2d4");
        client.sendMove("d2", "d4");

        // Wait for updated board
        System.out.println("Waiting for board update...");
        boolean gotUpdate = updateBoardLatch.await(5, TimeUnit.SECONDS);
        if (gotUpdate) {
            System.out.println("\n[SUCCESS] Successfully received board update!");
        } else {
            System.err.println("\n[FAIL] Timeout waiting for board update");
        }

        // Give time to see the output
        Thread.sleep(1000);

        client.close();
        System.out.println("\nTest complete");
    }
}
