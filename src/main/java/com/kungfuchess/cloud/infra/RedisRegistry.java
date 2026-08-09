package com.kungfuchess.cloud.infra;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Thin wrapper around Redis for everything the scaled architecture needs to share
 * across otherwise-stateless services (see Server_Design.md, section 2.2):
 * session tokens, the room→shard registry, room player assignment, and shard
 * heartbeats. This is deliberately the *only* shared mutable state in the whole
 * system — every service using it (API Gateway, WS Gateway, Game Allocator,
 * Game Shard) holds no state of its own.
 *
 * <p>{@link #pickLeastLoadedShard()} uses the {@code KEYS} command, which is O(N)
 * over the keyspace — acceptable for this "small working thing" pass with a
 * handful of shards; a production version would maintain an explicit Redis Set
 * of live shard ids instead of pattern-scanning.</p>
 */
public class RedisRegistry implements AutoCloseable {

    private static final int SESSION_TTL_SECONDS = 300;
    private static final int SHARD_HEARTBEAT_TTL_SECONDS = 30;
    private static final int ROOM_TTL_SECONDS = 7200;

    private final JedisPool pool;

    public RedisRegistry(String host, int port) {
        this.pool = new JedisPool(host, port);
    }

    // ── session tokens (single-use, short-lived) ────────────────────────────────

    /** Issues a fresh single-use token bound to {@code username}, valid for a few minutes. */
    public String issueSessionToken(String username) {
        String token = UUID.randomUUID().toString().replace("-", "");
        try (Jedis j = pool.getResource()) {
            j.setex("session:" + token, SESSION_TTL_SECONDS, username);
        }
        return token;
    }

    /** Validates and consumes (single-use) a token; returns the bound username, or null if invalid/expired. */
    public String consumeSessionToken(String token) {
        try (Jedis j = pool.getResource()) {
            String username = j.get("session:" + token);
            if (username != null) {
                j.del("session:" + token);
            }
            return username;
        }
    }

    // ── room registry: room:{id} -> hash {white, black, started} ───────────────

    public void createRoom(String roomId, String whiteUsername) {
        try (Jedis j = pool.getResource()) {
            String key = "room:" + roomId;
            j.hset(key, "white", whiteUsername);
            j.hset(key, "started", "false");
            j.expire(key, ROOM_TTL_SECONDS);
        }
    }

    /** @return the room's fields (white/black/started), or null if the room doesn't exist. */
    public Map<String, String> getRoom(String roomId) {
        try (Jedis j = pool.getResource()) {
            Map<String, String> data = j.hgetAll("room:" + roomId);
            return data.isEmpty() ? null : data;
        }
    }

    /** @return true if the room existed and black was assigned; false if the room doesn't exist. */
    public boolean setRoomBlack(String roomId, String blackUsername) {
        try (Jedis j = pool.getResource()) {
            String key = "room:" + roomId;
            if (j.exists(key) == false) return false;
            j.hset(key, "black", blackUsername);
            j.hset(key, "started", "true");
            return true;
        }
    }

    public void deleteRoom(String roomId) {
        try (Jedis j = pool.getResource()) {
            j.del("room:" + roomId);
            j.del("shard:" + roomId);
        }
    }

    // ── room -> shard registry ───────────────────────────────────────────────

    public void setRoomShard(String roomId, String host, int port) {
        try (Jedis j = pool.getResource()) {
            j.setex("shard:" + roomId, ROOM_TTL_SECONDS, host + ":" + port);
        }
    }

    /** @return "host:port" for the shard owning this room, or null if not (yet) allocated. */
    public String getRoomShard(String roomId) {
        try (Jedis j = pool.getResource()) {
            return j.get("shard:" + roomId);
        }
    }

    // ── shard heartbeats + least-loaded selection ───────────────────────────────

    public void heartbeatShard(String shardId, String host, int port, int activeRooms) {
        try (Jedis j = pool.getResource()) {
            j.setex("shard:worker:" + shardId, SHARD_HEARTBEAT_TTL_SECONDS,
                    host + ":" + port + ":" + activeRooms);
        }
    }

    /** @return "host:port" of the least-loaded live shard, or null if none have a current heartbeat. */
    public String pickLeastLoadedShard() {
        try (Jedis j = pool.getResource()) {
            Set<String> keys = j.keys("shard:worker:*");
            String best = null;
            int bestLoad = Integer.MAX_VALUE;
            for (String key : keys) {
                String val = j.get(key);
                if (val == null) continue;
                String[] parts = val.split(":");
                if (parts.length != 3) continue;
                int load = Integer.parseInt(parts[2]);
                if (load < bestLoad) {
                    bestLoad = load;
                    best = parts[0] + ":" + parts[1];
                }
            }
            return best;
        }
    }

    @Override
    public void close() {
        pool.close();
    }
}
