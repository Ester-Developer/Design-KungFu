package com.kungfuchess.net;

/**
 * Server-to-client message reporting the room code and the receiving connection's
 * role within it ("WHITE", "BLACK", or "SPECTATOR").
 *
 * <p>Sent right after a successful {@link RoomCreateMessage} or {@link RoomJoinMessage},
 * and again to the room's existing occupants whenever someone new joins (so everyone's
 * player-name/role display stays current). The client shows the room code prominently
 * (window title) so it can be shared with a friend.</p>
 */
public class RoomInfoMessage {
    private String type = "ROOM_INFO";
    private String roomId;
    private String role;       // "WHITE" | "BLACK" | "SPECTATOR" — the receiving connection's role
    private String whiteName;
    private String blackName;
    private boolean started;   // true once both WHITE and BLACK are present

    public RoomInfoMessage() {
        // Default constructor for Gson
    }

    public RoomInfoMessage(String roomId, String role, String whiteName, String blackName, boolean started) {
        this.roomId = roomId;
        this.role = role;
        this.whiteName = whiteName;
        this.blackName = blackName;
        this.started = started;
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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getWhiteName() {
        return whiteName;
    }

    public void setWhiteName(String whiteName) {
        this.whiteName = whiteName;
    }

    public String getBlackName() {
        return blackName;
    }

    public void setBlackName(String blackName) {
        this.blackName = blackName;
    }

    public boolean isStarted() {
        return started;
    }

    public void setStarted(boolean started) {
        this.started = started;
    }

    @Override
    public String toString() {
        return "RoomInfoMessage{type='" + type + "', roomId='" + roomId + "', role='" + role
                + "', whiteName='" + whiteName + "', blackName='" + blackName + "', started=" + started + "}";
    }
}
