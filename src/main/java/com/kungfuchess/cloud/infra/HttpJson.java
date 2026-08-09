package com.kungfuchess.cloud.infra;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Minimal JSON-over-HTTP helpers built on the JDK's own {@code HttpServer}/
 * {@code HttpClient} — deliberately no new web framework dependency, since every
 * service in the scaled architecture only needs a couple of small REST endpoints.
 */
public final class HttpJson {

    private static final Gson GSON = new Gson();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private HttpJson() {
    }

    public static HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        return server;
    }

    /** Handles a parsed JSON request body and returns the object to serialize as the response. */
    public interface JsonHandler<T> {
        Object handle(T body, HttpExchange exchange) throws Exception;
    }

    public static <T> void post(HttpServer server, String path, Class<T> bodyType, JsonHandler<T> handler) {
        server.createContext(path, exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("error", "method not allowed"));
                return;
            }
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                T parsed = GSON.fromJson(body, bodyType);
                Object result = handler.handle(parsed, exchange);
                sendJson(exchange, 200, result);
            } catch (ApiError e) {
                sendJson(exchange, e.status, Map.of("error", e.getMessage()));
            } catch (Exception e) {
                e.printStackTrace();
                sendJson(exchange, 500, Map.of("error", String.valueOf(e.getMessage())));
            }
        });
    }

    public static void get(HttpServer server, String path, JsonHandler<Void> handler) {
        server.createContext(path, exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("error", "method not allowed"));
                return;
            }
            try {
                Object result = handler.handle(null, exchange);
                sendJson(exchange, 200, result);
            } catch (ApiError e) {
                sendJson(exchange, e.status, Map.of("error", e.getMessage()));
            } catch (Exception e) {
                e.printStackTrace();
                sendJson(exchange, 500, Map.of("error", String.valueOf(e.getMessage())));
            }
        });
    }

    public static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Last path segment, e.g. "/rooms/ABCD" -> "ABCD". */
    public static String lastPathSegment(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /** Thrown by a handler to short-circuit with a specific HTTP status + JSON {"error": message}. */
    public static final class ApiError extends RuntimeException {
        final int status;

        public ApiError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    // ── outbound (service-to-service) JSON calls ─────────────────────────────

    public static <R> R postJson(String url, Object requestBody, Class<R> responseType)
            throws IOException, InterruptedException {
        HttpResponse<String> response = postJsonRaw(url, requestBody);
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return GSON.fromJson(response.body(), responseType);
    }

    /**
     * Like {@link #postJson}, but never throws on a non-2xx status — the caller gets
     * the raw status/body and decides what it means (e.g. a gateway forwarding a 401
     * "invalid credentials" from an upstream service must pass it through as-is, not
     * collapse it into a generic "service unavailable" the way a *transport* failure
     * — connection refused, timeout — should be reported).
     */
    public static HttpResponse<String> postJsonRaw(String url, Object requestBody)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(requestBody)))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public static <R> R getJson(String url, Class<R> responseType) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return GSON.fromJson(response.body(), responseType);
    }

    /** @return true if the request succeeded (2xx), without needing a response body. */
    public static boolean deleteQuiet(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).DELETE().build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() < 400;
        } catch (Exception e) {
            return false;
        }
    }
}
