package com.kungfuchess.net;

/**
 * Client-to-server message attempting to resume an in-progress game after an
 * unexpected disconnect (network drop, not a deliberate Exit/Play-Again close).
 * Sent to the same server the player was already on — the Game Server Shard in
 * the scaled architecture, or the single process in Phase 1 — identifying which
 * room/color/username is trying to rejoin. The server accepts it only if that
 * room is still active and the username matches who was actually seated there,
 * and cancels the opponent's disconnect countdown on success.
 */
public class ReconnectMessage {
    private String type = "RECONNECT";
    private String roomId;
    private String username;
    private String color;

    public ReconnectMessage() {
        // Default constructor for Gson
    }

    public ReconnectMessage(String roomId, String username, String color) {
        this.roomId = roomId;
        this.username = username;
        this.color = color;
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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }
}
