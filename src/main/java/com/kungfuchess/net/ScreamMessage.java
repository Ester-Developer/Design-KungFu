package com.kungfuchess.net;

/**
 * Client-to-server message representing a Scream request: an instant ranged capture
 * of an adjacent enemy piece, without the screaming piece moving.
 *
 * <p>Uses algebraic square notation (e.g., "e2", "e4"), matching {@link MoveMessage}.</p>
 */
public class ScreamMessage {
    private String type = "SCREAM";
    private String from;
    private String to;

    public ScreamMessage() {
        // Default constructor for Gson
    }

    public ScreamMessage(String from, String to) {
        this.from = from;
        this.to = to;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    @Override
    public String toString() {
        return "ScreamMessage{type='" + type + "', from='" + from + "', to='" + to + "'}";
    }
}
