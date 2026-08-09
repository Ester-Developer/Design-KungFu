package com.kungfuchess.cloud.services;

import com.kungfuchess.cloud.infra.HttpJson;
import com.kungfuchess.cloud.infra.RedisRegistry;
import com.kungfuchess.util.ActivityLogger;
import com.sun.net.httpserver.HttpServer;

import java.net.http.HttpResponse;
import java.util.Map;

/**
 * API Gateway — the only entry point clients hit over plain REST: login/register.
 * Forwards credentials to the Auth Service, then issues a short-lived, single-use
 * Redis session token the client presents to the WS Gateway. Holds no state of its
 * own (Server_Design.md §2.2). Port 8080.
 *
 * <p>POST /login {username, password} -&gt; {token, username, elo}<br>
 * POST /register {username, password} -&gt; {token, username, elo}</p>
 */
public class ApiGatewayMain {

    private static final int PORT = 8080;

    public static void main(String[] args) throws Exception {
        ActivityLogger.install("api-gateway");
        String authUrl = "http://" + System.getenv().getOrDefault("AUTH_HOST", "localhost")
                + ":" + System.getenv().getOrDefault("AUTH_PORT", "8000");
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));

        RedisRegistry redis = new RedisRegistry(redisHost, redisPort);
        HttpServer server = HttpJson.start(PORT);

        HttpJson.post(server, "/login", Creds.class, (body, ex) -> forward(authUrl + "/login", body, redis));
        HttpJson.post(server, "/register", Creds.class, (body, ex) -> forward(authUrl + "/register", body, redis));

        server.start();
        System.out.println("[ApiGateway] listening on http://0.0.0.0:" + PORT + " (Auth Service at " + authUrl + ")");
        Thread.currentThread().join();
    }

    private static Map<String, Object> forward(String url, Creds body, RedisRegistry redis) throws Exception {
        if (body == null || body.username == null || body.password == null) {
            throw new HttpJson.ApiError(400, "username and password are required");
        }
        HttpResponse<String> response;
        try {
            response = HttpJson.postJsonRaw(url, body);
        } catch (Exception e) {
            // A transport-level failure (connection refused, timeout, ...) — the Auth
            // Service is genuinely unreachable, distinct from it answering with an
            // error status (e.g. 401 invalid credentials), which is passed through below.
            throw new HttpJson.ApiError(503, "auth service unavailable: " + e.getMessage());
        }
        com.google.gson.Gson gson = new com.google.gson.Gson();
        if (response.statusCode() >= 400) {
            ErrorBody err = gson.fromJson(response.body(), ErrorBody.class);
            throw new HttpJson.ApiError(response.statusCode(), err != null && err.error != null ? err.error : response.body());
        }
        AuthResponse resp = gson.fromJson(response.body(), AuthResponse.class);
        String token = redis.issueSessionToken(resp.username);
        return Map.of("token", token, "username", resp.username, "elo", resp.elo);
    }

    private static final class Creds {
        String username;
        String password;
    }

    private static final class AuthResponse {
        String username;
        int elo;
    }

    private static final class ErrorBody {
        String error;
    }
}
