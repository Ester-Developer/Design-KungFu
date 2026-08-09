package com.kungfuchess.server;

import org.java_websocket.WebSocket;

/**
 * Represents a connected client session on the server.
 *
 * <p>Stores the WebSocket connection, username, ELO rating, and the room the
 * connection currently belongs to (if any) along with its role in that room
 * (WHITE, BLACK, or SPECTATOR). {@code color}/{@code room} start unset at login
 * and are only assigned once the player creates/joins a room or is matched —
 * every game now lives in its own {@link Room}, never a server-wide board.</p>
 */
public class ServerSession {

    public static final String SPECTATOR = "SPECTATOR";

    private final WebSocket connection;
    private final String username;
    private volatile int elo;
    private volatile String color;
    private volatile Room room;

    public ServerSession(WebSocket connection, String username, String color, int elo) {
        this.connection = connection;
        this.username = username;
        this.color = color;
        this.elo = elo;
    }

    public WebSocket getConnection() {
        return connection;
    }

    public String getUsername() {
        return username;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public int getElo() {
        return elo;
    }

    /** Refreshes the cached ELO (e.g. right after a game updates it in the database). */
    public void setElo(int elo) {
        this.elo = elo;
    }

    public Room getRoom() {
        return room;
    }

    public void setRoom(Room room) {
        this.room = room;
    }

    public boolean isSpectator() {
        return SPECTATOR.equals(color);
    }

    @Override
    public String toString() {
        return "ServerSession{username='" + username + "', color='" + color
                + "', room=" + (room != null ? room.getRoomId() : "none") + ", elo=" + elo + "}";
    }
}
