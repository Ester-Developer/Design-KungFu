package com.kungfuchess.cloud.tools;

import com.google.gson.Gson;
import com.kungfuchess.client.ChessWebSocketClient;
import com.kungfuchess.net.ShardConnectMessage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Concurrent load test for the scaled architecture (Server_Design.md's
 * Observability responsibility: "load tests"). Spins up N room-based pairs and M
 * quick-match players concurrently, each driving the exact same REST-login ->
 * WS-Gateway-token -> (room or queue) -> shard-redirect -> move flow the GUI
 * client uses, and reports success rate plus how long pairing+allocation took.
 *
 * <p>This is a smoke-scale tool for validating the pipeline under modest
 * concurrency on a single dev machine, not a claim about the 10M-player traffic
 * estimate in Server_Design.md — see that document's §2.3 for the back-of-envelope
 * numbers at real scale.</p>
 *
 * <p>Usage: {@code java ... LoadTest --room-pairs 5 --quick-pairs 5
 * --api http://localhost:8080 --ws ws://localhost:5555}</p>
 */
public class LoadTest {

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        int roomPairs = 5;
        int quickPairs = 5;
        String apiUrl = "http://localhost:8080";
        String wsUrl = "ws://localhost:5555";
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--room-pairs" -> roomPairs = Integer.parseInt(args[i + 1]);
                case "--quick-pairs" -> quickPairs = Integer.parseInt(args[i + 1]);
                case "--api" -> apiUrl = args[i + 1];
                case "--ws" -> wsUrl = args[i + 1];
            }
        }
        System.out.printf("Load test: %d room-code pairs + %d quick-match pairs against %s / %s%n",
                roomPairs, quickPairs, apiUrl, wsUrl);
        final String api = apiUrl;
        final String ws = wsUrl;

        List<CompletableFuture<Result>> futures = new ArrayList<>();
        ExecutorService pool = Executors.newCachedThreadPool();
        long suffix = System.currentTimeMillis() % 100000;

        for (int i = 0; i < roomPairs; i++) {
            String white = "load_room_" + suffix + "_" + i + "_w";
            String black = "load_room_" + suffix + "_" + i + "_b";
            futures.add(CompletableFuture.supplyAsync(() -> runRoomPair(api, ws, white, black), pool));
        }
        for (int i = 0; i < quickPairs; i++) {
            String a = "load_quick_" + suffix + "_" + i + "_a";
            String b = "load_quick_" + suffix + "_" + i + "_b";
            futures.add(CompletableFuture.supplyAsync(() -> runQuickMatchPair(api, ws, a, b), pool));
        }

        List<Result> results = new ArrayList<>();
        for (CompletableFuture<Result> f : futures) {
            try {
                results.add(f.get(60, TimeUnit.SECONDS));
            } catch (Exception e) {
                results.add(new Result(false, -1));
            }
        }
        pool.shutdownNow();
        report(results);
    }

    private record Result(boolean success, long pairToShardMs) {
    }

    // ── room-code flow: two simulated players, one creates, one joins ─────────

    private static Result runRoomPair(String apiUrl, String wsUrl, String whiteUser, String blackUser) {
        try {
            AuthResponse whiteAuth = registerAndLogin(apiUrl, whiteUser);
            ChessWebSocketClient white = connectAndToken(wsUrl, whiteAuth);

            CompletableFuture<String> roomCode = new CompletableFuture<>();
            white.setOnRoomInfo(msg -> roomCode.complete(msg.getRoomId()));
            CompletableFuture<ShardConnectMessage> whiteShard = new CompletableFuture<>();
            white.setOnShardConnect(whiteShard::complete);
            white.sendRoomCreate();
            String code = roomCode.get(15, TimeUnit.SECONDS);

            long start = System.currentTimeMillis();
            AuthResponse blackAuth = registerAndLogin(apiUrl, blackUser);
            ChessWebSocketClient black = connectAndToken(wsUrl, blackAuth);
            CompletableFuture<ShardConnectMessage> blackShard = new CompletableFuture<>();
            black.setOnShardConnect(blackShard::complete);
            black.sendRoomJoin(code);

            whiteShard.get(15, TimeUnit.SECONDS);
            blackShard.get(15, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;

            white.closeBlocking();
            black.closeBlocking();
            return new Result(true, elapsed);
        } catch (Exception e) {
            System.err.println("[LoadTest] room pair " + whiteUser + "/" + blackUser + " failed: " + e);
            return new Result(false, -1);
        }
    }

    // ── quick-match flow: two simulated players joining the ELO queue ─────────

    private static Result runQuickMatchPair(String apiUrl, String wsUrl, String userA, String userB) {
        try {
            AuthResponse authA = registerAndLogin(apiUrl, userA);
            AuthResponse authB = registerAndLogin(apiUrl, userB);
            ChessWebSocketClient a = connectAndToken(wsUrl, authA);
            ChessWebSocketClient b = connectAndToken(wsUrl, authB);

            CompletableFuture<ShardConnectMessage> shardA = new CompletableFuture<>();
            CompletableFuture<ShardConnectMessage> shardB = new CompletableFuture<>();
            a.setOnShardConnect(shardA::complete);
            b.setOnShardConnect(shardB::complete);

            long start = System.currentTimeMillis();
            a.sendPlayRequest();
            b.sendPlayRequest();

            shardA.get(30, TimeUnit.SECONDS);
            shardB.get(30, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;

            a.closeBlocking();
            b.closeBlocking();
            return new Result(true, elapsed);
        } catch (Exception e) {
            System.err.println("[LoadTest] quick-match pair " + userA + "/" + userB + " failed: " + e);
            return new Result(false, -1);
        }
    }

    // ── shared helpers ───────────────────────────────────────────────────────

    private static ChessWebSocketClient connectAndToken(String wsUrl, AuthResponse auth) throws Exception {
        ChessWebSocketClient client = new ChessWebSocketClient(new URI(wsUrl));
        client.connectBlocking();
        client.sendToken(auth.token, auth.username, auth.elo);
        return client;
    }

    private static AuthResponse registerAndLogin(String apiUrl, String username) throws Exception {
        String body = GSON.toJson(Map.of("username", username, "password", "loadtest123"));
        HttpRequest register = HttpRequest.newBuilder().uri(URI.create(apiUrl + "/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HTTP.send(register, HttpResponse.BodyHandlers.ofString()); // ignore 400 "already taken"

        HttpRequest login = HttpRequest.newBuilder().uri(URI.create(apiUrl + "/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> resp = HTTP.send(login, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) throw new RuntimeException("login failed for " + username + ": " + resp.body());
        return GSON.fromJson(resp.body(), AuthResponse.class);
    }

    private static void report(List<Result> results) {
        int success = 0;
        List<Long> latencies = new ArrayList<>();
        for (Result r : results) {
            if (r.success()) {
                success++;
                latencies.add(r.pairToShardMs());
            }
        }
        latencies.sort(Long::compareTo);
        System.out.println();
        System.out.println("=== Load Test Report ===");
        System.out.printf("Pairs attempted: %d   Succeeded: %d   Failed: %d%n",
                results.size(), success, results.size() - success);
        if (!latencies.isEmpty()) {
            System.out.printf("Pairing -> shard-redirect latency: min=%dms p50=%dms p95=%dms max=%dms%n",
                    latencies.get(0), percentile(latencies, 50), percentile(latencies, 95),
                    latencies.get(latencies.size() - 1));
        }
    }

    private static long percentile(List<Long> sorted, int pct) {
        int idx = Math.min(sorted.size() - 1, (int) Math.ceil(pct / 100.0 * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }

    private static final class AuthResponse {
        String token;
        String username;
        int elo;
    }
}
