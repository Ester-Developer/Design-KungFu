package com.kungfuchess.net;

/**
 * Server-to-client message indicating opponent has disconnected.
 *
 * <p>Sent once per second during the 20-second countdown after an opponent
 * disconnects mid-game. If the opponent doesn't reconnect within 20 seconds,
 * they automatically resign and the remaining player wins.</p>
 */
public class DisconnectCountdownMessage {
    private String type = "OPPONENT_DISCONNECTED";
    private int secondsLeft;

    public DisconnectCountdownMessage() {
        // Default constructor for Gson
    }

    public DisconnectCountdownMessage(int secondsLeft) {
        this.secondsLeft = secondsLeft;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getSecondsLeft() {
        return secondsLeft;
    }

    public void setSecondsLeft(int secondsLeft) {
        this.secondsLeft = secondsLeft;
    }

    @Override
    public String toString() {
        return "DisconnectCountdownMessage{type='" + type + "', secondsLeft=" + secondsLeft + "}";
    }
}
