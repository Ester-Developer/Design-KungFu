package com.kungfuchess.net;

/**
 * Client-to-server message representing a move request.
 *
 * <p>Uses algebraic square notation (e.g., "e2", "e4") rather than full move
 * notation to match the JSON structure.</p>
 */
public class MoveMessage {
    private String type = "MOVE";
    private String from;
    private String to;

    public MoveMessage() {
        // Default constructor for Gson
    }

    public MoveMessage(String from, String to) {
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
        return "MoveMessage{type='" + type + "', from='" + from + "', to='" + to + "'}";
    }
}
