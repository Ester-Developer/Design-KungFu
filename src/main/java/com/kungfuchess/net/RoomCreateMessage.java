package com.kungfuchess.net;

/**
 * Client-to-server message requesting a new private room.
 *
 * <p>Sent after successful login. The server generates a 4-character room code,
 * assigns the requesting connection as WHITE, and replies with a {@link RoomInfoMessage}.</p>
 */
public class RoomCreateMessage {
    private String type = "ROOM_CREATE";

    public RoomCreateMessage() {
        // Default constructor for Gson
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    @Override
    public String toString() {
        return "RoomCreateMessage{type='" + type + "'}";
    }
}
