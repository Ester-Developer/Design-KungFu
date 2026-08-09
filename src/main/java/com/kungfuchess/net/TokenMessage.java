package com.kungfuchess.net;

/**
 * Client-to-gateway handshake message for the scaled architecture (Phase 2):
 * the very first message sent on a WS Gateway or Game Shard connection, carrying
 * the session token issued by the API Gateway (or, for a shard hop, re-issued by
 * the WS Gateway). Validated once against Redis, then deleted (single-use).
 */
public class TokenMessage {
    private String type = "TOKEN";
    private String token;
    private String username;
    private int elo;

    public TokenMessage() {
        // Default constructor for Gson
    }

    public TokenMessage(String token, String username, int elo) {
        this.token = token;
        this.username = username;
        this.elo = elo;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public int getElo() {
        return elo;
    }

    public void setElo(int elo) {
        this.elo = elo;
    }
}
