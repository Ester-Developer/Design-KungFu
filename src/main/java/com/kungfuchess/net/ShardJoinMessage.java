package com.kungfuchess.net;

/**
 * Client-to-shard handshake message (Phase 2): the first message sent on the
 * reconnected Game Server Shard socket, after a {@link ShardConnectMessage}
 * redirect. The shard validates {@code token} against Redis (single-use), then
 * seats the connection as {@code color} in {@code roomId}.
 */
public class ShardJoinMessage {
    private String type = "SHARD_JOIN";
    private String roomId;
    private String token;
    private String color;

    public ShardJoinMessage() {
        // Default constructor for Gson
    }

    public ShardJoinMessage(String roomId, String token, String color) {
        this.roomId = roomId;
        this.token = token;
        this.color = color;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }
}
