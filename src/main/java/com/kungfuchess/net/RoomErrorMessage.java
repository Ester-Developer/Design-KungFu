package com.kungfuchess.net;

/**
 * Server-to-client message reporting a room-related failure (e.g. unknown room code).
 */
public class RoomErrorMessage {
    private String type = "ROOM_ERROR";
    private String reason;

    public RoomErrorMessage() {
        // Default constructor for Gson
    }

    public RoomErrorMessage(String reason) {
        this.reason = reason;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "RoomErrorMessage{type='" + type + "', reason='" + reason + "'}";
    }
}
