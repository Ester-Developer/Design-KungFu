package com.kungfuchess.net;

/**
 * Client-to-server message requesting to join an existing room by its code.
 *
 * <p>The second connection to join a room becomes BLACK and starts the game;
 * any connection after that becomes a read-only SPECTATOR.</p>
 */
public class RoomJoinMessage {
    private String type = "ROOM_JOIN";
    private String roomId;

    public RoomJoinMessage() {
        // Default constructor for Gson
    }

    public RoomJoinMessage(String roomId) {
        this.roomId = roomId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    @Override
    public String toString() {
        return "RoomJoinMessage{type='" + type + "', roomId='" + roomId + "'}";
    }
}
