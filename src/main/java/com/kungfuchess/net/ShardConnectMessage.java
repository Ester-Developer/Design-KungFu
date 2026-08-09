package com.kungfuchess.net;

/**
 * Gateway-to-client message (Phase 2): "stop talking to me, reconnect to this Game
 * Server Shard instead" — sent once a room is ready (both players present) and a
 * shard has been allocated for it. The client closes its WS Gateway connection and
 * opens a new one to {@code shardUrl}, sending a {@link ShardJoinMessage} first.
 */
public class ShardConnectMessage {
    private String type = "SHARD_CONNECT";
    private String shardUrl;
    private String roomId;
    private String token;
    private String color;
    private String[] players;

    public ShardConnectMessage() {
        // Default constructor for Gson
    }

    public ShardConnectMessage(String shardUrl, String roomId, String token, String color, String[] players) {
        this.shardUrl = shardUrl;
        this.roomId = roomId;
        this.token = token;
        this.color = color;
        this.players = players;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getShardUrl() {
        return shardUrl;
    }

    public void setShardUrl(String shardUrl) {
        this.shardUrl = shardUrl;
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

    public String[] getPlayers() {
        return players;
    }

    public void setPlayers(String[] players) {
        this.players = players;
    }
}
