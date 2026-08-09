package com.kungfuchess.cloud.services;

import com.google.gson.Gson;
import com.kungfuchess.cloud.infra.HttpJson;
import com.kungfuchess.cloud.infra.RedisRegistry;
import com.kungfuchess.net.ShardConnectMessage;
import com.kungfuchess.util.ActivityLogger;
import com.sun.net.httpserver.HttpServer;
import io.nats.client.Connection;
import io.nats.client.Nats;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Matchmaker — the ELO queue for "Quick Match" (Server_Design.md §3). Players join
 * an ELO-sorted queue held in Redis; a background scan pairs anyone within
 * {@link #ELO_RANGE} of each other, allocates a shard for them exactly like a
 * room-code game, and publishes the result on a per-user NATS subject so whichever
 * WS Gateway instance holds that player's socket can deliver it — this is the one
 * place in the system where NATS actually carries control-plane traffic between
 * services, as opposed to Redis's shared-registry role everywhere else.
 *
 * <p>POST /queue/join {username, elo} -&gt; 202 Accepted (asynchronous — the result
 * arrives over NATS on subject {@code kfc.matched.<username>})</p>
 */
public class MatchmakerMain {

    private static final int PORT = 8003;
    private static final int ELO_RANGE = 100;
    private static final long SCAN_INTERVAL_MS = 750;

    public static void main(String[] args) throws Exception {
        ActivityLogger.install("matchmaker");
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
        String allocatorUrl = "http://" + System.getenv().getOrDefault("ALLOCATOR_HOST", "localhost")
                + ":" + System.getenv().getOrDefault("ALLOCATOR_PORT", "8004");
        String natsUrl = "nats://" + System.getenv().getOrDefault("NATS_HOST", "localhost")
                + ":" + System.getenv().getOrDefault("NATS_PORT", "4222");

        RedisRegistry redis = new RedisRegistry(redisHost, redisPort);
        Connection nats = Nats.connect(natsUrl);
        Gson gson = new Gson();
        SecureRandom random = new SecureRandom();

        HttpServer server = HttpJson.start(PORT);
        HttpJson.get(server, "/health", (body, ex) -> Map.of("status", "ok", "service", "matchmaker"));
        HttpJson.get(server, "/metrics", (body, ex) -> Map.of(
                "kfc_matchmaker_queue_size", redis.matchQueueSnapshot().size()));

        HttpJson.post(server, "/queue/join", QueueJoinRequest.class, (reqBody, ex) -> {
            if (reqBody == null || reqBody.username == null || reqBody.username.isBlank()) {
                throw new HttpJson.ApiError(400, "username is required");
            }
            redis.enqueueForMatch(reqBody.username, reqBody.elo);
            System.out.println("[Matchmaker] " + reqBody.username + " (ELO " + reqBody.elo + ") joined the queue");
            return Map.of("status", "queued");
        });

        HttpJson.post(server, "/queue/leave", QueueJoinRequest.class, (reqBody, ex) -> {
            if (reqBody == null || reqBody.username == null) {
                throw new HttpJson.ApiError(400, "username is required");
            }
            redis.removeFromQueueIfPresent(reqBody.username);
            return Map.of("status", "left");
        });

        server.start();
        System.out.println("[Matchmaker] listening on http://0.0.0.0:" + PORT + " (Allocator at " + allocatorUrl + ")");

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(
                () -> scanAndPair(redis, allocatorUrl, nats, gson, random),
                SCAN_INTERVAL_MS, SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);

        Thread.currentThread().join();
    }

    private static void scanAndPair(RedisRegistry redis, String allocatorUrl, Connection nats, Gson gson,
                                     SecureRandom random) {
        List<RedisRegistry.QueuedPlayer> queue = redis.matchQueueSnapshot(); // ELO-ascending
        boolean[] matched = new boolean[queue.size()];
        for (int i = 0; i < queue.size(); i++) {
            if (matched[i]) continue;
            for (int j = i + 1; j < queue.size(); j++) {
                if (matched[j]) continue;
                if (Math.abs(queue.get(i).elo() - queue.get(j).elo()) <= ELO_RANGE) {
                    matched[i] = true;
                    matched[j] = true;
                    pairUp(redis, allocatorUrl, nats, gson, random, queue.get(i), queue.get(j));
                    break;
                }
            }
        }
    }

    private static void pairUp(RedisRegistry redis, String allocatorUrl, Connection nats, Gson gson,
                                SecureRandom random, RedisRegistry.QueuedPlayer white, RedisRegistry.QueuedPlayer black) {
        // Re-check + remove atomically-enough for this scale: both must still be queued.
        if (!redis.removeFromQueueIfPresent(white.username()) || !redis.removeFromQueueIfPresent(black.username())) {
            return;
        }
        String roomId = "Q-" + randomCode(random, 6);
        try {
            redis.createRoom(roomId, white.username());
            redis.setRoomBlack(roomId, black.username());

            Map<String, Object> allocated = HttpJson.postJson(allocatorUrl + "/allocate",
                    Map.of("roomId", roomId), Map.class);
            String shardHost = String.valueOf(allocated.get("host"));
            int shardPort = (int) Double.parseDouble(String.valueOf(allocated.get("port")));
            String shardUrl = "ws://" + shardHost + ":" + shardPort;
            String[] players = { white.username(), black.username() };

            String whiteToken = redis.issueSessionToken(white.username());
            String blackToken = redis.issueSessionToken(black.username());

            publish(nats, gson, white.username(),
                    new ShardConnectMessage(shardUrl, roomId, whiteToken, "WHITE", players));
            publish(nats, gson, black.username(),
                    new ShardConnectMessage(shardUrl, roomId, blackToken, "BLACK", players));

            System.out.println("[Matchmaker] matched " + white.username() + " (ELO " + white.elo() + ") vs "
                    + black.username() + " (ELO " + black.elo() + ") -> room " + roomId + " on " + shardUrl);
        } catch (Exception e) {
            System.err.println("[Matchmaker] failed to allocate match " + white.username() + " vs "
                    + black.username() + ": " + e.getMessage());
            redis.deleteRoom(roomId);
            // Best effort — put both back in the queue so they aren't stranded.
            redis.enqueueForMatch(white.username(), white.elo());
            redis.enqueueForMatch(black.username(), black.elo());
        }
    }

    private static void publish(Connection nats, Gson gson, String username, ShardConnectMessage msg) {
        nats.publish("kfc.matched." + username, gson.toJson(msg).getBytes(StandardCharsets.UTF_8));
    }

    private static String randomCode(SecureRandom random, int length) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private static final class QueueJoinRequest {
        String username;
        int elo;
    }
}
