package com.kungfuchess.net;

/**
 * Server-to-client message indicating authentication result.
 *
 * <p>Sent in response to a {@link LoginMessage}. If authentication succeeds,
 * the server will then send a {@link ColorMessage} to assign the player's color.
 * If authentication fails, the connection will be closed after a maximum of 3 attempts.</p>
 */
public class AuthResultMessage {
    private String type = "AUTH_RESULT";
    private boolean success;
    private String reason;
    private int elo;

    public AuthResultMessage() {
        // Default constructor for Gson
    }

    public AuthResultMessage(boolean success, String reason, int elo) {
        this.success = success;
        this.reason = reason;
        this.elo = elo;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public int getElo() {
        return elo;
    }

    public void setElo(int elo) {
        this.elo = elo;
    }

    @Override
    public String toString() {
        return "AuthResultMessage{type='" + type + "', success=" + success + 
               ", reason='" + reason + "', elo=" + elo + "}";
    }
}
