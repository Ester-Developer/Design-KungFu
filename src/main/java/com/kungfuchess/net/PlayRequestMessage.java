package com.kungfuchess.net;

/**
 * Client-to-server message to join the matchmaking queue.
 *
 * <p>Sent by the client after successful authentication to request a game.
 * The server will pair this player with another player of similar ELO rating.</p>
 */
public class PlayRequestMessage {
    private String type = "PLAY";

    public PlayRequestMessage() {
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
        return "PlayRequestMessage{type='" + type + "'}";
    }
}
