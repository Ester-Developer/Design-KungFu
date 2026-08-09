package com.kungfuchess.client;

import com.google.gson.Gson;
import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import com.kungfuchess.net.ShardConnectMessage;
import com.kungfuchess.util.ActivityLogger;
import com.kungfuchess.view.Renderer;

import javax.swing.SwingUtilities;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point for the <b>Phase 2 scaled architecture</b> client (Server_Design.md):
 * REST login against the API Gateway, then a WebSocket handshake with the WS
 * Gateway, which — once a room has two players — redirects the connection to a
 * Game Server Shard for the actual game. Reuses the exact same {@link Renderer},
 * {@link LoginWindow}, {@link GameOverlayPanel}, and wire messages as the Phase 1
 * single-process client ({@link ClientMain}) — only the connection/handshake
 * sequence differs.
 *
 * <p>Usage: {@code java ... CloudClientMain --host localhost --ws-port 5555 --api-port 8080}</p>
 *
 * <p>Both Room (create/join by code) and Quick Match are wired up: Quick Match
 * sends {@code PLAY} to the WS Gateway, which queues the player with the
 * Matchmaker and — once paired — receives the same {@link ShardConnectMessage}
 * redirect as the room flow, delivered over a per-user NATS subject.</p>
 */
public class CloudClientMain {

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        ActivityLogger.install("cloud-client");

        String host = "localhost";
        int wsPort = 5555;
        int apiPort = 8080;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--host" -> host = args[i + 1];
                case "--ws-port" -> wsPort = Integer.parseInt(args[i + 1]);
                case "--api-port" -> apiPort = Integer.parseInt(args[i + 1]);
            }
        }
        System.out.println("=== Kung-Fu Chess Client (Phase 2 — scaled architecture) ===");
        System.out.println("API Gateway: http://" + host + ":" + apiPort
                + "   WS Gateway: ws://" + host + ":" + wsPort);

        final String apiUrl = "http://" + host + ":" + apiPort;
        final String wsGatewayUrl = "ws://" + host + ":" + wsPort;

        // Login over REST — the password is kept in memory only to support
        // re-logging in for "Play Again" (Phase 2 tokens are single-use).
        final String[] password = new String[1];
        LoginWindow.LoginHandler loginHandler = (user, pass, onResult) -> {
            password[0] = pass;
            try {
                AuthResponse resp = restLogin(apiUrl, user, pass);
                onResult.accept(new LoginWindow.AuthOutcome(true, "ok"));
                loggedInUser[0] = resp;
            } catch (Exception e) {
                onResult.accept(new LoginWindow.AuthOutcome(false, e.getMessage()));
            }
        };

        LoginWindow loginWindow = new LoginWindow(loginHandler);
        String username;
        try {
            username = loginWindow.awaitSuccessfulLogin().get();
        } catch (Exception e) {
            System.err.println("[Client] Login window closed without logging in: " + e.getMessage());
            return;
        }

        boolean playAgain = true;
        while (playAgain) {
            AuthResponse fresh = loggedInUser[0] != null ? loggedInUser[0]
                    : restLogin(apiUrl, username, password[0]);
            playAgain = playOneGame(loginWindow, wsGatewayUrl, fresh);
            loggedInUser[0] = null; // force a fresh token on the next loop
        }

        loginWindow.close();
        System.out.println("Client disconnected");
    }

    // Captured by the login handler so the first game can reuse the token that was
    // already issued at login, instead of an extra round-trip.
    private static final AuthResponse[] loggedInUser = new AuthResponse[1];

    private static boolean playOneGame(LoginWindow loginWindow, String wsGatewayUrl, AuthResponse auth)
            throws Exception {
        CountDownLatch colorLatch = new CountDownLatch(1);
        CountDownLatch opponentLatch = new CountDownLatch(1);
        String[] assignedColor = new String[1];

        ChessWebSocketClient gatewayClient = new ChessWebSocketClient(new URI(wsGatewayUrl));
        gatewayClient.connectBlocking();
        gatewayClient.sendToken(auth.token, auth.username, auth.elo);

        LoginWindow.HomeHandler homeHandler = new LoginWindow.HomeHandler() {
            @Override public void onPlay() {
                gatewayClient.sendPlayRequest();
                loginWindow.showSearching();
            }
            @Override public void onCreateRoom() {
                gatewayClient.sendRoomCreate();
            }
            @Override public void onJoinRoom(String roomId) {
                gatewayClient.sendRoomJoin(roomId);
            }
        };
        gatewayClient.setOnRoomInfo(msg -> {
            if (!msg.isStarted()) {
                loginWindow.showWaitingForOpponent(msg.getRole(), msg.getRoomId());
            }
        });
        gatewayClient.setOnRoomError(reason -> loginWindow.showHomeScreen(auth.username, homeHandler, reason));

        CompletableFuture<ShardConnectMessage> shardConnect = new CompletableFuture<>();
        gatewayClient.setOnShardConnect(shardConnect::complete);

        loginWindow.showHomeScreen(auth.username, homeHandler);
        loginWindow.showWindow();

        ShardConnectMessage redirect;
        try {
            redirect = shardConnect.get();
        } catch (Exception e) {
            System.err.println("[Client] Never got redirected to a shard: " + e.getMessage());
            return false;
        }
        gatewayClient.closeBlocking();

        // Second hop: reconnect to the assigned Game Server Shard.
        ChessWebSocketClient client = new ChessWebSocketClient(new URI(redirect.getShardUrl()));
        client.setOnColorAssigned(color -> {
            assignedColor[0] = color;
            colorLatch.countDown();
        });
        client.setOnOpponentJoined(opponentLatch::countDown);
        client.connectBlocking();
        client.sendShardJoin(redirect.getRoomId(), redirect.getToken(), redirect.getColor());

        colorLatch.await();
        if (!client.isOpponentPresent()) {
            loginWindow.showWaitingForOpponent(assignedColor[0], redirect.getRoomId());
            opponentLatch.await();
        }
        loginWindow.hideWindow();

        Renderer renderer = new Renderer();
        CompletableFuture<Boolean> nextAction = new CompletableFuture<>();
        initGameWindow(client, renderer, assignedColor[0], auth.username, redirect.getRoomId(), nextAction);

        boolean result;
        try {
            while (!nextAction.isDone() && client.isOpen()) {
                Thread.sleep(100);
            }
            result = nextAction.isDone() && nextAction.get();
        } catch (Exception e) {
            result = false;
        }

        client.resetForNewGame();
        if (renderer.getFrame() != null) {
            SwingUtilities.invokeLater(() -> renderer.getFrame().dispose());
        }
        if (client.isOpen()) client.close();
        return result;
    }

    private static void initGameWindow(ChessWebSocketClient client, Renderer renderer, String assignedColor,
                                        String username, String roomId, CompletableFuture<Boolean> nextAction)
            throws Exception {
        String whiteName = "WHITE".equalsIgnoreCase(assignedColor) ? username : "Waiting...";
        String blackName = "BLACK".equalsIgnoreCase(assignedColor) ? username : "Waiting...";
        renderer.setPlayerNames(whiteName, blackName);
        renderer.setLocalPlayerInfo(assignedColor, client.getLastKnownElo());
        renderer.setRoomId(roomId);

        client.setRenderer(renderer, false);
        client.setClientMode(false, assignedColor);
        client.setMoveCallback((from, to) -> { if (client.isOpen()) client.sendMove(from, to); });
        client.setScreamCallback((from, to) -> { if (client.isOpen()) client.sendScream(from, to); });
        client.setDodgeCallback(square -> { if (client.isOpen()) client.sendDodge(square); });

        GameEngine.GameSnapshot initialSnapshot = blankStartingSnapshot(assignedColor);
        renderer.render(initialSnapshot);

        if (renderer.getFrame() != null) {
            String suffix = roomId != null ? " — Room: " + roomId : "";
            renderer.getFrame().setTitle("KF-Chess (Cloud)" + suffix);

            GameOverlayPanel overlay = new GameOverlayPanel(new GameOverlayPanel.GameOverHandler() {
                @Override public void onExit() { nextAction.complete(false); }
                @Override public void onPlayAgain() { nextAction.complete(true); }
            });
            renderer.getFrame().setGlassPane(overlay);
            overlay.setVisible(true);

            client.setOnOpponentDisconnectedCountdown(secondsLeft ->
                    SwingUtilities.invokeLater(() -> overlay.showDisconnectCountdown(secondsLeft)));
            client.setOnGameOver(winnerColor -> SwingUtilities.invokeLater(() -> {
                overlay.hideDisconnectCountdown();
                String label = "white".equalsIgnoreCase(winnerColor) ? "White" : "Black";
                overlay.showGameOver(winnerColor == null ? "Game over."
                        : winnerColor.equalsIgnoreCase(assignedColor) ? "You won! (" + label + ")"
                        : "You lost — " + label + " wins.");
            }));
        }

        client.attachMouseListenerNow();
    }

    private static GameEngine.GameSnapshot blankStartingSnapshot(String assignedColor) throws Exception {
        Board board = Board.createStandard();
        board.addPiece(new Position(0, 0), new Piece("Rook", "black"));
        board.addPiece(new Position(0, 1), new Piece("Knight", "black"));
        board.addPiece(new Position(0, 2), new Piece("Bishop", "black"));
        board.addPiece(new Position(0, 3), new Piece("Queen", "black"));
        board.addPiece(new Position(0, 4), new Piece("King", "black"));
        board.addPiece(new Position(0, 5), new Piece("Bishop", "black"));
        board.addPiece(new Position(0, 6), new Piece("Knight", "black"));
        board.addPiece(new Position(0, 7), new Piece("Rook", "black"));
        for (int c = 0; c < 8; c++) board.addPiece(new Position(1, c), new Piece("Pawn", "black"));
        for (int c = 0; c < 8; c++) board.addPiece(new Position(6, c), new Piece("Pawn", "white"));
        board.addPiece(new Position(7, 0), new Piece("Rook", "white"));
        board.addPiece(new Position(7, 1), new Piece("Knight", "white"));
        board.addPiece(new Position(7, 2), new Piece("Bishop", "white"));
        board.addPiece(new Position(7, 3), new Piece("Queen", "white"));
        board.addPiece(new Position(7, 4), new Piece("King", "white"));
        board.addPiece(new Position(7, 5), new Piece("Bishop", "white"));
        board.addPiece(new Position(7, 6), new Piece("Knight", "white"));
        board.addPiece(new Position(7, 7), new Piece("Rook", "white"));

        List<GameEngine.GameSnapshot.PieceView> pieces = new ArrayList<>();
        for (int r = 0; r < board.getHeight(); r++) {
            for (int c = 0; c < board.getWidth(); c++) {
                Position pos = new Position(r, c);
                Optional<Piece> p = board.pieceAt(pos);
                p.ifPresent(piece -> pieces.add(new GameEngine.GameSnapshot.PieceView(
                        piece.getKind(), piece.getColor(), pos, 0L, 0L)));
            }
        }
        return new GameEngine.GameSnapshot(pieces, 8, 8, null, false, assignedColor,
                new ArrayList<>(), 0L, 0, 0, new ArrayList<>());
    }

    // ── REST login against the API Gateway ───────────────────────────────────

    private static AuthResponse restLogin(String apiUrl, String username, String password) throws Exception {
        String body = GSON.toJson(Map.of("username", username, "password", password));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) {
            // try registering — mirrors the Phase 1 "auto-register on first login" UX
            HttpRequest register = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/register"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> regResponse = HTTP.send(register, HttpResponse.BodyHandlers.ofString());
            if (regResponse.statusCode() >= 400) {
                throw new RuntimeException("login/register failed: " + regResponse.body());
            }
            return GSON.fromJson(regResponse.body(), AuthResponse.class);
        }
        if (response.statusCode() >= 400) {
            throw new RuntimeException("login failed: " + response.body());
        }
        return GSON.fromJson(response.body(), AuthResponse.class);
    }

    private static final class AuthResponse {
        String token;
        String username;
        int elo;
    }
}
