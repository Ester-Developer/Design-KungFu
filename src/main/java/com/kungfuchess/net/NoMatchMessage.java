package com.kungfuchess.net;

/**
 * Server-to-client message indicating no match was found.
 *
 * <p>Sent when a player has been in the matchmaking queue for 60 seconds
 * without finding a suitable opponent. The player is removed from the queue
 * and can send another {@link PlayRequestMessage} to try again.</p>
 */
public class NoMatchMessage {
    private String type = "NO_MATCH_FOUND";

    public NoMatchMessage() {
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
        return "NoMatchMessage{type='" + type + "'}";
    }
}
