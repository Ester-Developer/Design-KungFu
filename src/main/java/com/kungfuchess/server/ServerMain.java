package com.kungfuchess.server;

import com.kungfuchess.util.ActivityLogger;

/**
 * Entry point for the Kung-Fu Chess WebSocket server.
 *
 * <p>Single-process server (per the assignment's Base/Stage 1-4 spec): one JVM,
 * one WebSocket port. Every game gets its own isolated {@link Room} (own board,
 * own clock) via {@link RoomManager} — connections create a room, join one by its
 * 4-character code, or request a quick ELO-matched game.</p>
 */
public class ServerMain {

    private static final int PORT = 8887;

    public static void main(String[] args) throws Exception {
        ActivityLogger.install("server");
        System.out.println("=== Kung-Fu Chess Server ===\n");

        ChessWebSocketServer server = new ChessWebSocketServer(PORT);
        server.start();

        System.out.println("Server listening on port " + PORT);
        System.out.println("Clients can connect to: ws://localhost:" + PORT);
        System.out.println("\nPress Ctrl+C to stop the server\n");

        // Keep the server running
        Thread.currentThread().join();
    }
}
