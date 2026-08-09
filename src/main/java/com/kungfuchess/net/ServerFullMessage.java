package com.kungfuchess.net;

/**
 * Server-to-client message indicating the server is at capacity.
 *
 * <p>Sent when a third client attempts to connect. After sending this message,
 * the server closes the connection. The game supports a maximum of 2 players.</p>
 */
public class ServerFullMessage {
    private String type = "FULL";

    public ServerFullMessage() {
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
        return "ServerFullMessage{type='" + type + "'}";
    }
}
