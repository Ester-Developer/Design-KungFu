package com.kungfuchess.cloud.services;

import com.kungfuchess.cloud.infra.HttpJson;
import com.kungfuchess.cloud.infra.PostgresUserRepository;
import com.kungfuchess.util.ActivityLogger;
import com.sun.net.httpserver.HttpServer;

import java.util.Map;

/**
 * Auth Service — durable identity: register/login against PostgreSQL. Reads only
 * PG_* env vars; no other service's address, no Redis. Port 8000 (matches
 * Server_Design.md).
 *
 * <p>POST /register {username, password} -&gt; {username, elo}<br>
 * POST /login {username, password} -&gt; {username, elo}</p>
 */
public class AuthServiceMain {

    private static final int PORT = 8000;

    public static void main(String[] args) throws Exception {
        ActivityLogger.install("auth-service");
        String pgHost = System.getenv().getOrDefault("PG_HOST", "localhost");
        int pgPort = Integer.parseInt(System.getenv().getOrDefault("PG_PORT", "5432"));

        PostgresUserRepository users = new PostgresUserRepository(pgHost, pgPort, "kfc", "kfc", "kfc");

        HttpServer server = HttpJson.start(PORT);

        HttpJson.post(server, "/register", Creds.class, (body, ex) -> {
            requireCreds(body);
            if (!users.registerUser(body.username, body.password)) {
                throw new HttpJson.ApiError(400, "username already taken");
            }
            System.out.println("[AuthService] registered " + body.username);
            return Map.of("username", body.username, "elo", users.getElo(body.username));
        });

        HttpJson.post(server, "/login", Creds.class, (body, ex) -> {
            requireCreds(body);
            if (!users.userExists(body.username) || !users.verifyPassword(body.username, body.password)) {
                throw new HttpJson.ApiError(401, "invalid credentials");
            }
            System.out.println("[AuthService] logged in " + body.username);
            return Map.of("username", body.username, "elo", users.getElo(body.username));
        });

        server.start();
        System.out.println("[AuthService] listening on http://0.0.0.0:" + PORT);
        Thread.currentThread().join();
    }

    private static void requireCreds(Creds c) {
        if (c == null || c.username == null || c.username.isBlank() || c.password == null || c.password.isEmpty()) {
            throw new HttpJson.ApiError(400, "username and password are required");
        }
    }

    private static final class Creds {
        String username;
        String password;
    }
}
