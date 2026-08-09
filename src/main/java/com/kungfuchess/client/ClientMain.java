package com.kungfuchess.client;

import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.io.BoardParser;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import com.kungfuchess.net.BoardStateMessage;
import com.kungfuchess.util.ActivityLogger;
import com.kungfuchess.view.Renderer;

import javax.swing.SwingUtilities;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point for the Kung-Fu Chess WebSocket client.
 *
 * <p>By default, shows a graphical board window using {@link Renderer} and {@link Controller}.
 * Mouse clicks on the board send moves to the server.</p>
 *
 * <p>Use {@code --console} flag to fall back to ASCII text mode (board printed to console,
 * moves typed as "e2e4").</p>
 *
 * <p>Console input is handled in a dedicated background thread to prevent blocking
 * the WebSocket keep-alive mechanism, which prevents "did not respond with pong" disconnects.</p>
 */
public class ClientMain {

    private static final String SERVER_URI = "ws://localhost:8887";

    public static void main(String[] args) throws Exception {
        ActivityLogger.install("client");
        boolean consoleMode = false;
        for (String arg : args) {
            if ("--console".equalsIgnoreCase(arg)) {
                consoleMode = true;
                break;
            }
        }

        final boolean useConsoleMode = consoleMode;
        System.out.println("=== Kung-Fu Chess Client ===");
        if (useConsoleMode) {
            System.out.println("Running in CONSOLE mode (--console flag detected)\n");
        } else {
            System.out.println("Running in GUI mode (use --console flag for text mode)\n");
        }

        ChessWebSocketClient client = new ChessWebSocketClient(new URI(SERVER_URI));
        client.connectBlocking(); // Wait for connection to establish
        System.out.println("[Client] Connected to server.\n");

        if (useConsoleMode) {
            runConsoleMode(client);
        } else {
            runGuiMode(client);
        }
    }

    // =========================================================================
    // Console mode — unchanged shell-based flow (Stages 1-2 spec)
    // =========================================================================

    private static void runConsoleMode(ChessWebSocketClient client) throws Exception {
        CountDownLatch colorLatch = new CountDownLatch(1);
        AtomicBoolean shouldExit = new AtomicBoolean(false);
        String[] username = new String[1];

        client.setOnColorAssigned(color -> colorLatch.countDown());

        Thread inputThread = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            try {
                System.out.print("Enter your username: ");
                String usernameInput = scanner.nextLine().trim();
                while (usernameInput.isEmpty()) {
                    System.out.print("Username cannot be empty. Enter your username: ");
                    usernameInput = scanner.nextLine().trim();
                }
                username[0] = usernameInput;

                System.out.print("Enter your password: ");
                String password = scanner.nextLine();
                while (password.isEmpty()) {
                    System.out.print("Password cannot be empty. Enter your password: ");
                    password = scanner.nextLine();
                }

                client.sendLogin(username[0], password);

                // Home screen (shell form, per the assignment spec): Play for a quick
                // ELO match, or Room to create/join a private game by code.
                System.out.println("\nType 'play' for a quick ELO match,");
                System.out.println("or 'room create' / 'room join CODE' to play with a friend.");
                client.setOnRoomError(reason -> System.err.println("[Client] Room error: " + reason
                        + " — try 'room create' or 'room join CODE' again."));
                client.setOnNoMatch(() -> System.out.println("[Client] No opponent found — type 'play' to try again."));
                while (colorLatch.getCount() > 0 && scanner.hasNextLine()) {
                    System.out.print("> ");
                    String choice = scanner.nextLine().trim();
                    if (choice.equalsIgnoreCase("play")) {
                        client.sendPlayRequest();
                    } else if (choice.equalsIgnoreCase("room create")) {
                        client.sendRoomCreate();
                    } else if (choice.toLowerCase().startsWith("room join ")) {
                        client.sendRoomJoin(choice.substring("room join ".length()).trim());
                    } else {
                        System.out.println("Unrecognized. Type 'play', 'room create', or 'room join CODE'.");
                        continue;
                    }
                    colorLatch.await(3, java.util.concurrent.TimeUnit.SECONDS);
                }

                colorLatch.await();

                System.out.println("\nType moves in algebraic notation (e.g. 'e2e4' or 'e2 e4') and press Enter");
                System.out.println("Type 'quit' to exit\n");

                while (scanner.hasNextLine() && !shouldExit.get()) {
                    String line = scanner.nextLine().trim();
                    if (line.isEmpty()) continue;

                    if ("quit".equalsIgnoreCase(line)) {
                        System.out.println("Closing connection...");
                        shouldExit.set(true);
                        break;
                    }

                    String from, to;
                    if (line.contains(" ")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length != 2) {
                            System.err.println("Invalid move format. Use 'e2e4' or 'e2 e4'");
                            continue;
                        }
                        from = parts[0];
                        to = parts[1];
                    } else if (line.length() == 4) {
                        from = line.substring(0, 2);
                        to = line.substring(2, 4);
                    } else {
                        System.err.println("Invalid move format. Use 'e2e4' or 'e2 e4'");
                        continue;
                    }

                    if (client.isOpen()) {
                        client.sendMove(from, to);
                    } else {
                        System.err.println("[Client] Connection closed, cannot send move");
                        shouldExit.set(true);
                        break;
                    }
                }

                scanner.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("[Client] Input thread interrupted");
            } catch (Exception e) {
                System.err.println("[Client] Error in input thread: " + e.getMessage());
                e.printStackTrace();
                shouldExit.set(true);
            }
        }, "InputThread");

        inputThread.setDaemon(true);
        inputThread.start();

        try {
            while (!shouldExit.get() && client.isOpen()) {
                Thread.sleep(100);
            }
        } finally {
            if (client.isOpen()) client.close();
            inputThread.interrupt();
            inputThread.join(1000);
            System.out.println("Client disconnected");
        }
    }

    // =========================================================================
    // GUI mode — login once, then loop through games (Play Again / Exit)
    // =========================================================================

    private static void runGuiMode(ChessWebSocketClient client) throws Exception {
        LoginWindow.LoginHandler loginHandler = (user, pass, onResult) -> {
            client.setOnAuthResult((success, reason) ->
                onResult.accept(new LoginWindow.AuthOutcome(success, reason)));
            client.sendLogin(user, pass);
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
        while (playAgain && client.isOpen()) {
            playAgain = playOneGame(client, loginWindow, username);
        }

        loginWindow.close();
        if (client.isOpen()) client.close();
        System.out.println("Client disconnected");
    }

    /**
     * Runs one game from the Home screen through game-over (Exit/Play Again buttons).
     *
     * @return {@code true} if the user chose "Play Again" (caller should loop),
     *         {@code false} for "Exit" or a dropped connection.
     */
    private static boolean playOneGame(ChessWebSocketClient client, LoginWindow loginWindow, String username)
            throws Exception {
        CountDownLatch colorLatch = new CountDownLatch(1);
        CountDownLatch opponentLatch = new CountDownLatch(1);
        String[] assignedColor = new String[1];

        client.setOnColorAssigned(color -> {
            assignedColor[0] = color;
            colorLatch.countDown();
        });
        client.setOnOpponentJoined(opponentLatch::countDown);

        // Home screen (Stage 3/4 spec): "Play" for a quick ELO match, "Room" to
        // create/join a private game by code.
        LoginWindow.HomeHandler homeHandler = new LoginWindow.HomeHandler() {
            @Override public void onPlay() {
                client.sendPlayRequest();
                loginWindow.showSearching();
            }
            @Override public void onCreateRoom() {
                client.sendRoomCreate();
            }
            @Override public void onJoinRoom(String roomId) {
                client.sendRoomJoin(roomId);
            }
        };
        client.setOnRoomInfo(msg -> {
            System.out.println("[Client] onRoomInfo: roomId=" + msg.getRoomId() + " role=" + msg.getRole()
                    + " started=" + msg.isStarted());
            if (!msg.isStarted()) {
                System.out.println("[Client] Showing waiting screen with room code: " + msg.getRoomId());
                loginWindow.showWaitingForOpponent(msg.getRole(), msg.getRoomId());
            }
        });
        client.setOnRoomError(reason -> loginWindow.showHomeScreen(username, homeHandler, reason));
        client.setOnNoMatch(() -> loginWindow.showHomeScreen(username, homeHandler,
                "No opponent found. Try again."));
        loginWindow.showHomeScreen(username, homeHandler);
        loginWindow.showWindow();

        try {
            colorLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        if (!client.isOpponentPresent()) {
            System.out.println("[Client] Opponent not yet present — re-showing waiting screen with room code: "
                    + client.getCurrentRoomId());
            loginWindow.showWaitingForOpponent(assignedColor[0], client.getCurrentRoomId());
            try {
                opponentLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        loginWindow.hideWindow(); // stays alive for next time — unlike close(), which disposes it

        Renderer renderer = new Renderer();
        CompletableFuture<Boolean> nextAction = new CompletableFuture<>();
        try {
            initGameWindow(client, renderer, assignedColor[0], username, nextAction);
        } catch (Exception e) {
            System.err.println("[Client] ERROR setting up game window:");
            e.printStackTrace();
            return false;
        }

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
        return result;
    }

    /** Builds the game window, wires callbacks, and shows the initial board. */
    private static void initGameWindow(ChessWebSocketClient client, Renderer renderer, String assignedColor,
                                        String username, CompletableFuture<Boolean> nextAction) throws Exception {
        String whiteName = "WHITE".equalsIgnoreCase(assignedColor) ? username : "Waiting...";
        String blackName = "BLACK".equalsIgnoreCase(assignedColor) ? username : "Waiting...";
        renderer.setPlayerNames(whiteName, blackName);
        renderer.setLocalPlayerInfo(assignedColor, client.getLastKnownElo());

        client.setRenderer(renderer, false);
        client.setClientMode(false, assignedColor);

        client.setMoveCallback((from, to) -> {
            try {
                if (client.isOpen()) client.sendMove(from, to);
            } catch (Exception e) {
                System.err.println("[Client] ERROR in move callback:");
                e.printStackTrace();
            }
        });
        client.setScreamCallback((from, to) -> {
            try {
                if (client.isOpen()) client.sendScream(from, to);
            } catch (Exception e) {
                System.err.println("[Client] ERROR in scream callback:");
                e.printStackTrace();
            }
        });
        client.setDodgeCallback(square -> {
            try {
                if (client.isOpen()) client.sendDodge(square);
            } catch (Exception e) {
                System.err.println("[Client] ERROR in dodge callback:");
                e.printStackTrace();
            }
        });

        // Placeholder starting position — real state arrives from the server moments later.
        Board initialBoard = Board.createStandard();
        initialBoard.addPiece(new Position(0, 0), new Piece("Rook", "black"));
        initialBoard.addPiece(new Position(0, 1), new Piece("Knight", "black"));
        initialBoard.addPiece(new Position(0, 2), new Piece("Bishop", "black"));
        initialBoard.addPiece(new Position(0, 3), new Piece("Queen", "black"));
        initialBoard.addPiece(new Position(0, 4), new Piece("King", "black"));
        initialBoard.addPiece(new Position(0, 5), new Piece("Bishop", "black"));
        initialBoard.addPiece(new Position(0, 6), new Piece("Knight", "black"));
        initialBoard.addPiece(new Position(0, 7), new Piece("Rook", "black"));
        for (int c = 0; c < 8; c++) {
            initialBoard.addPiece(new Position(1, c), new Piece("Pawn", "black"));
        }
        for (int c = 0; c < 8; c++) {
            initialBoard.addPiece(new Position(6, c), new Piece("Pawn", "white"));
        }
        initialBoard.addPiece(new Position(7, 0), new Piece("Rook", "white"));
        initialBoard.addPiece(new Position(7, 1), new Piece("Knight", "white"));
        initialBoard.addPiece(new Position(7, 2), new Piece("Bishop", "white"));
        initialBoard.addPiece(new Position(7, 3), new Piece("Queen", "white"));
        initialBoard.addPiece(new Position(7, 4), new Piece("King", "white"));
        initialBoard.addPiece(new Position(7, 5), new Piece("Bishop", "white"));
        initialBoard.addPiece(new Position(7, 6), new Piece("Knight", "white"));
        initialBoard.addPiece(new Position(7, 7), new Piece("Rook", "white"));

        List<GameEngine.GameSnapshot.PieceView> initialPieces = new ArrayList<>();
        for (int r = 0; r < initialBoard.getHeight(); r++) {
            for (int c = 0; c < initialBoard.getWidth(); c++) {
                Position pos = new Position(r, c);
                Optional<Piece> pieceOpt = initialBoard.pieceAt(pos);
                if (pieceOpt.isPresent()) {
                    Piece piece = pieceOpt.get();
                    initialPieces.add(new GameEngine.GameSnapshot.PieceView(
                        piece.getKind(), piece.getColor(), pos, 0L, 0L));
                }
            }
        }

        GameEngine.GameSnapshot initialSnapshot = new GameEngine.GameSnapshot(
            initialPieces, 8, 8, null, false, assignedColor,
            new ArrayList<>(), 0L, 0, 0, new ArrayList<>());
        renderer.render(initialSnapshot);

        if (renderer.getFrame() != null) {
            String roomId = client.getCurrentRoomId();
            String suffix = roomId != null ? " — Room: " + roomId : "";
            if ("SPECTATOR".equals(assignedColor)) {
                suffix += " (Spectating)";
            }
            renderer.getFrame().setTitle("KF-Chess" + suffix);

            // Glass-pane overlay: disconnect countdown + Exit/Play Again on game-over —
            // independent of the Renderer's own drawing.
            GameOverlayPanel overlay = new GameOverlayPanel(new GameOverlayPanel.GameOverHandler() {
                @Override public void onExit() {
                    nextAction.complete(false);
                }
                @Override public void onPlayAgain() {
                    nextAction.complete(true);
                }
            });
            renderer.getFrame().setGlassPane(overlay);
            overlay.setVisible(true);

            client.setOnOpponentDisconnectedCountdown(secondsLeft ->
                    SwingUtilities.invokeLater(() -> overlay.showDisconnectCountdown(secondsLeft)));
            client.setOnGameOver(winnerColor -> SwingUtilities.invokeLater(() -> {
                overlay.hideDisconnectCountdown();
                overlay.showGameOver(gameOverMessage(winnerColor, assignedColor));
            }));
        }

        client.attachMouseListenerNow();
    }

    /** Personalizes the game-over message to the local viewer's own color. */
    private static String gameOverMessage(String winnerColor, String myColor) {
        if (winnerColor == null) return "Game over.";
        String winnerLabel = "white".equalsIgnoreCase(winnerColor) ? "White" : "Black";
        if ("SPECTATOR".equals(myColor)) {
            return winnerLabel + " wins!";
        }
        return winnerColor.equalsIgnoreCase(myColor)
            ? "You won! (" + winnerLabel + ")"
            : "You lost — " + winnerLabel + " wins.";
    }

    /**
     * Converts a BoardStateMessage (2D String array) to a Board object.
     * Uses BoardParser.TextParser which expects format like "wP bK . ."
     */
    private static Board boardStateToBoard(BoardStateMessage msg) throws BoardParser.BoardParseException {
        String[][] grid = msg.getBoard();
        if (grid == null || grid.length == 0) {
            throw new BoardParser.BoardParseException("Empty board state");
        }

        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < grid.length; r++) {
            for (int c = 0; c < grid[r].length; c++) {
                if (c > 0) sb.append(" ");
                sb.append(grid[r][c]);
            }
            if (r < grid.length - 1) sb.append("\n");
        }

        return new BoardParser.TextParser().parse(sb.toString());
    }

    /**
     * Creates a GameSnapshot from BoardStateMessage using rich data.
     * Uses the message's pieces list with real cooldowns, not the flat board array.
     */
    private static GameEngine.GameSnapshot buildSnapshotFromMessage(BoardStateMessage msg, Position localSelectedSquare) {
        List<GameEngine.GameSnapshot.PieceView> pieces = new ArrayList<>();

        if (msg.getPieces() != null) {
            for (BoardStateMessage.PieceData pd : msg.getPieces()) {
                Position pos = pd.getPosition().toPosition();
                pieces.add(new GameEngine.GameSnapshot.PieceView(
                    pd.getKind(), pd.getColor(), pos, pd.getRestUntilMs(), pd.getRestStartMs()
                ));
            }
        } else {
            String[][] grid = msg.getBoard();
            if (grid != null) {
                for (int r = 0; r < grid.length; r++) {
                    for (int c = 0; c < grid[r].length; c++) {
                        String cell = grid[r][c];
                        if (cell != null && !cell.equals(".")) {
                            String color = cell.startsWith("w") ? "white" : "black";
                            String kind = cell.substring(1);
                            pieces.add(new GameEngine.GameSnapshot.PieceView(
                                kind, color, new Position(r, c), 0L, 0L
                            ));
                        }
                    }
                }
            }
        }

        Position rejectedDest = null;
        if (msg.getLastRejectedDest() != null) {
            rejectedDest = msg.getLastRejectedDest().toPosition();
        }

        return new GameEngine.GameSnapshot(
            pieces,
            msg.getBoardWidth() > 0 ? msg.getBoardWidth() : 8,
            msg.getBoardHeight() > 0 ? msg.getBoardHeight() : 8,
            localSelectedSquare,
            msg.isGameOver(),
            msg.getTurn() != null ? msg.getTurn() : "white",
            new ArrayList<>(),
            msg.getClock(),
            msg.getScoreWhite(),
            msg.getScoreBlack(),
            new ArrayList<>(),
            rejectedDest,
            msg.getWinner()
        );
    }
}
