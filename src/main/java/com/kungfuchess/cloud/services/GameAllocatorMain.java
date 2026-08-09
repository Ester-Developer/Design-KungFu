package com.kungfuchess.cloud.services;

import com.kungfuchess.cloud.infra.HttpJson;
import com.kungfuchess.cloud.infra.RedisRegistry;
import com.kungfuchess.util.ActivityLogger;
import com.sun.net.httpserver.HttpServer;

import java.util.Map;

/**
 * Game Allocator — the only service that decides *which* Game Server Shard a room
 * runs on. Picks the least-loaded shard from the heartbeats Game Shards write to
 * Redis, and writes the {@code room_id -> shard} mapping every WS Gateway resolves
 * on join/reconnect (Server_Design.md §2.2). Holds no state itself — Redis is the
 * registry. Port 8004.
 *
 * <p>POST /allocate {roomId} -&gt; {host, port}</p>
 */
public class GameAllocatorMain {

    private static final int PORT = 8004;

    public static void main(String[] args) throws Exception {
        ActivityLogger.install("game-allocator");
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
        RedisRegistry redis = new RedisRegistry(redisHost, redisPort);

        HttpServer server = HttpJson.start(PORT);

        HttpJson.post(server, "/allocate", AllocateRequest.class, (body, ex) -> {
            if (body == null || body.roomId == null || body.roomId.isBlank()) {
                throw new HttpJson.ApiError(400, "roomId is required");
            }
            String shard = redis.pickLeastLoadedShard();
            if (shard == null) {
                throw new HttpJson.ApiError(503, "no Game Server Shard is currently registered");
            }
            String[] parts = shard.split(":");
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);
            redis.setRoomShard(body.roomId, host, port);
            System.out.println("[GameAllocator] room " + body.roomId + " -> shard " + shard);
            return Map.of("host", host, "port", port);
        });

        server.start();
        System.out.println("[GameAllocator] listening on http://0.0.0.0:" + PORT);
        Thread.currentThread().join();
    }

    private static final class AllocateRequest {
        String roomId;
    }
}
