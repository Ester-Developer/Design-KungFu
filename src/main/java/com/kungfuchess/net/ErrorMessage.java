package com.kungfuchess.net;

/**
 * Server-to-client error message.
 *
 * <p>Sent when a move request is invalid or cannot be processed.</p>
 */
public class ErrorMessage {
    private String type = "ERROR";
    private String reason;

    public ErrorMessage() {
        // Default constructor for Gson
    }

    public ErrorMessage(String reason) {
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
        return "ErrorMessage{type='" + type + "', reason='" + reason + "'}";
    }
}
