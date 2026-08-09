package com.kungfuchess.client;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Graphical welcome/login flow for the WebSocket client: a "Welcome" screen with a
 * Join button, a username/password form, and a "waiting for opponent" screen.
 * Replaces console-typed login so players never need to touch the terminal.
 *
 * <p>Networking is delegated to a {@link LoginHandler} supplied by the caller; this
 * class only owns the Swing presentation, the retry loop on authentication failure,
 * and the waiting-room animation. The caller decides when the opponent has joined
 * and calls {@link #close()} to hand off to the game board.</p>
 */
public class LoginWindow {

    /** Performs the actual login attempt and reports the outcome back to the window. */
    public interface LoginHandler {
        void attemptLogin(String username, String password, Consumer<AuthOutcome> onResult);
    }

    public static final class AuthOutcome {
        public final boolean success;
        public final String message;

        public AuthOutcome(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    /** Actions available from the post-login Home screen (Play / Room create / Room join). */
    public interface HomeHandler {
        void onPlay();
        void onCreateRoom();
        void onJoinRoom(String roomId);
    }

    private static final Color BG = new Color(24, 26, 27);
    private static final Color FG = new Color(235, 235, 235);
    private static final Color ACCENT = new Color(118, 150, 86); // chess-board green
    private static final Color ERROR = new Color(224, 108, 92);

    private final JFrame frame;
    private final LoginHandler loginHandler;
    private final CompletableFuture<String> loggedInUsername = new CompletableFuture<>();
    private Timer waitingAnimationTimer;

    public LoginWindow(LoginHandler loginHandler) {
        this.loginHandler = loginHandler;
        this.frame = new JFrame("Kung Fu Chess");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(440, 360);
        frame.setResizable(false);
        frame.setLocationRelativeTo(null);
        showWelcomeScreen();
        frame.setVisible(true);
    }

    /** Completes with the username as soon as login succeeds (before the opponent joins). */
    public CompletableFuture<String> awaitSuccessfulLogin() {
        return loggedInUsername;
    }

    /** Switches to the "waiting for opponent" screen. Call after a successful login. */
    public void showWaitingForOpponent(String myColor) {
        showWaitingForOpponent(myColor, null);
    }

    /**
     * Switches to the "waiting for opponent" screen, optionally showing a room code
     * (e.g. right after creating a room, so the creator can share it).
     */
    public void showWaitingForOpponent(String myColor, String roomId) {
        SwingUtilities.invokeLater(() -> {
            stopWaitingAnimation();

            JPanel panel = new JPanel(new BorderLayout(0, 20));
            panel.setBackground(BG);
            panel.setBorder(BorderFactory.createEmptyBorder(50, 40, 50, 40));

            Spinner spinner = new Spinner(ACCENT);

            // Static text — only the spinner moves, so the line never shifts.
            JLabel status = new JLabel("Waiting for opponent to join", SwingConstants.CENTER);
            status.setFont(new Font("Segoe UI", Font.BOLD, 18));
            status.setForeground(FG);

            String colorLabel = myColor != null ? myColor.toUpperCase() : "?";
            JLabel sub = new JLabel("You're playing as " + colorLabel, SwingConstants.CENTER);
            sub.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            sub.setForeground(new Color(160, 160, 160));

            JPanel center = new JPanel();
            center.setOpaque(false);
            center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
            spinner.setAlignmentX(Component.CENTER_ALIGNMENT);
            status.setAlignmentX(Component.CENTER_ALIGNMENT);
            sub.setAlignmentX(Component.CENTER_ALIGNMENT);
            center.add(spinner);
            center.add(Box.createVerticalStrut(16));
            center.add(status);
            center.add(Box.createVerticalStrut(6));
            center.add(sub);

            System.out.println("[LoginWindow] showWaitingForOpponent: myColor=" + myColor + " roomId=" + roomId);
            if (roomId != null && !roomId.isBlank()) {
                JLabel code = new JLabel("Room code: " + roomId, SwingConstants.CENTER);
                code.setFont(new Font("Segoe UI", Font.BOLD, 22));
                code.setForeground(ACCENT);
                code.setAlignmentX(Component.CENTER_ALIGNMENT);
                JLabel codeHint = new JLabel("Share this code with your friend", SwingConstants.CENTER);
                codeHint.setFont(new Font("Segoe UI", Font.PLAIN, 12));
                codeHint.setForeground(new Color(160, 160, 160));
                codeHint.setAlignmentX(Component.CENTER_ALIGNMENT);
                center.add(Box.createVerticalStrut(18));
                center.add(code);
                center.add(Box.createVerticalStrut(4));
                center.add(codeHint);
            }

            panel.add(center, BorderLayout.CENTER);
            swapContent(panel);

            waitingAnimationTimer = new Timer(30, e -> spinner.advance(9));
            waitingAnimationTimer.start();
        });
    }

    /** Switches to a "searching for a match" screen (Quick Play / ELO matchmaking). */
    public void showSearching() {
        SwingUtilities.invokeLater(() -> {
            stopWaitingAnimation();

            JPanel panel = new JPanel(new BorderLayout(0, 20));
            panel.setBackground(BG);
            panel.setBorder(BorderFactory.createEmptyBorder(50, 40, 50, 40));

            Spinner spinner = new Spinner(ACCENT);
            JLabel status = new JLabel("Searching for an opponent...", SwingConstants.CENTER);
            status.setFont(new Font("Segoe UI", Font.BOLD, 18));
            status.setForeground(FG);
            JLabel sub = new JLabel("Matching by ELO rating (up to 30 seconds)", SwingConstants.CENTER);
            sub.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            sub.setForeground(new Color(160, 160, 160));

            JPanel center = new JPanel();
            center.setOpaque(false);
            center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
            spinner.setAlignmentX(Component.CENTER_ALIGNMENT);
            status.setAlignmentX(Component.CENTER_ALIGNMENT);
            sub.setAlignmentX(Component.CENTER_ALIGNMENT);
            center.add(spinner);
            center.add(Box.createVerticalStrut(16));
            center.add(status);
            center.add(Box.createVerticalStrut(6));
            center.add(sub);

            panel.add(center, BorderLayout.CENTER);
            swapContent(panel);

            waitingAnimationTimer = new Timer(30, e -> spinner.advance(9));
            waitingAnimationTimer.start();
        });
    }

    /**
     * Shows the post-login Home screen: "Play" (quick ELO match) and "Room"
     * (create/join by code) buttons, per the assignment's Stage 3/4 spec.
     */
    public void showHomeScreen(String username, HomeHandler handler) {
        showHomeScreen(username, handler, null);
    }

    public void showHomeScreen(String username, HomeHandler handler, String errorMessage) {
        stopWaitingAnimation();
        SwingUtilities.invokeLater(() -> {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setBackground(BG);
            panel.setBorder(BorderFactory.createEmptyBorder(50, 40, 50, 40));

            JLabel title = new JLabel("Welcome, " + username, SwingConstants.CENTER);
            title.setFont(new Font("Segoe UI", Font.BOLD, 22));
            title.setForeground(FG);

            JLabel status = new JLabel(errorMessage != null ? errorMessage : " ", SwingConstants.CENTER);
            status.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            status.setForeground(errorMessage != null ? ERROR : FG);

            JButton playButton = new JButton("Play (Quick Match)");
            styleButton(playButton);
            playButton.addActionListener(e -> handler.onPlay());

            JButton roomButton = new JButton("Room");
            styleButton(roomButton);
            roomButton.addActionListener(e -> showRoomDialog(handler));

            JPanel center = new JPanel();
            center.setOpaque(false);
            center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
            title.setAlignmentX(Component.CENTER_ALIGNMENT);
            status.setAlignmentX(Component.CENTER_ALIGNMENT);
            playButton.setAlignmentX(Component.CENTER_ALIGNMENT);
            roomButton.setAlignmentX(Component.CENTER_ALIGNMENT);
            center.add(title);
            center.add(Box.createVerticalStrut(8));
            center.add(status);
            center.add(Box.createVerticalStrut(30));
            center.add(playButton);
            center.add(Box.createVerticalStrut(14));
            center.add(roomButton);

            panel.add(center, BorderLayout.CENTER);
            swapContent(panel);
        });
    }

    /** "Room" popup: text box + Create / Join / Cancel, per the assignment's Stage 4 spec. */
    private void showRoomDialog(HomeHandler handler) {
        JDialog dialog = new JDialog(frame, "Room", true);
        dialog.setSize(360, 220);
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(frame);

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(BG);
        panel.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        dialog.setContentPane(panel);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridwidth = 2;
        gbc.gridx = 0;
        gbc.gridy = 0;

        JLabel title = new JLabel("Enter a room code to join, or create a new one", SwingConstants.CENTER);
        title.setForeground(FG);
        title.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        panel.add(title, gbc);

        JTextField codeField = new JTextField();
        codeField.setFont(new Font("Segoe UI", Font.BOLD, 16));
        gbc.gridy = 1;
        panel.add(codeField, gbc);

        JLabel statusLabel = label(" ");
        gbc.gridy = 2;
        panel.add(statusLabel, gbc);

        JButton createButton = new JButton("Create");
        JButton joinButton = new JButton("Join");
        JButton cancelButton = new JButton("Cancel");
        styleButton(createButton);
        styleButton(joinButton);
        styleButton(cancelButton);

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.add(createButton);
        buttons.add(joinButton);
        buttons.add(cancelButton);
        gbc.gridy = 3;
        panel.add(buttons, gbc);

        createButton.addActionListener(e -> {
            dialog.dispose();
            handler.onCreateRoom();
        });
        joinButton.addActionListener(e -> {
            String code = codeField.getText().trim();
            if (code.isEmpty()) {
                statusLabel.setForeground(ERROR);
                statusLabel.setText("Enter a room code first");
                return;
            }
            dialog.dispose();
            handler.onJoinRoom(code);
        });
        cancelButton.addActionListener(e -> dialog.dispose());

        dialog.setVisible(true);
    }

    /** Hand-drawn rotating arc spinner — avoids relying on font glyph coverage. */
    private static final class Spinner extends JComponent {
        private final Color color;
        private double angle = 0;

        Spinner(Color color) {
            this.color = color;
            setOpaque(false);
            setPreferredSize(new Dimension(56, 56));
            setMaximumSize(new Dimension(56, 56));
        }

        void advance(double degrees) {
            angle = (angle + degrees) % 360;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int size = Math.min(getWidth(), getHeight()) - 8;
            int x = (getWidth() - size) / 2;
            int y = (getHeight() - size) / 2;

            g2.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 50));
            g2.drawOval(x, y, size, size);

            g2.setColor(color);
            g2.drawArc(x, y, size, size, (int) angle, 100);
            g2.dispose();
        }
    }

    /** Hand-drawn 4x4 checkerboard motif — avoids relying on font glyph coverage. */
    private static final class Checkerboard extends JComponent {
        private static final int SQUARES = 4;
        private static final int SQUARE_SIZE = 14;

        Checkerboard() {
            setOpaque(false);
            int side = SQUARES * SQUARE_SIZE;
            setPreferredSize(new Dimension(side, side));
            setMaximumSize(new Dimension(side, side));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            for (int row = 0; row < SQUARES; row++) {
                for (int col = 0; col < SQUARES; col++) {
                    boolean light = (row + col) % 2 == 0;
                    g2.setColor(light ? FG : ACCENT);
                    g2.fillRect(col * SQUARE_SIZE, row * SQUARE_SIZE, SQUARE_SIZE, SQUARE_SIZE);
                }
            }
            g2.dispose();
        }
    }

    /** Disposes the window (stopping any animation), handing off to the game board. */
    public void close() {
        SwingUtilities.invokeLater(() -> {
            stopWaitingAnimation();
            frame.dispose();
        });
    }

    /**
     * Hides the window without disposing it (stopping any animation) — used when
     * handing off to the game board but the same instance may be shown again later
     * ("Play Again"), unlike {@link #close()} which destroys it for good.
     */
    public void hideWindow() {
        SwingUtilities.invokeLater(() -> {
            stopWaitingAnimation();
            frame.setVisible(false);
        });
    }

    /** Re-shows a previously hidden window (see {@link #hideWindow()}). */
    public void showWindow() {
        SwingUtilities.invokeLater(() -> frame.setVisible(true));
    }

    private void stopWaitingAnimation() {
        if (waitingAnimationTimer != null) {
            waitingAnimationTimer.stop();
            waitingAnimationTimer = null;
        }
    }

    private void showWelcomeScreen() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG);
        panel.setBorder(BorderFactory.createEmptyBorder(50, 40, 50, 40));

        Checkerboard icon = new Checkerboard();

        JLabel title = new JLabel("Welcome to Kung Fu Chess", SwingConstants.CENTER);
        title.setFont(new Font("Segoe UI", Font.BOLD, 24));
        title.setForeground(FG);

        JLabel subtitle = new JLabel("Real-time chess. No turns. No mercy.", SwingConstants.CENTER);
        subtitle.setFont(new Font("Segoe UI", Font.ITALIC, 13));
        subtitle.setForeground(new Color(160, 160, 160));

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        icon.setAlignmentX(Component.CENTER_ALIGNMENT);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        center.add(icon);
        center.add(Box.createVerticalStrut(12));
        center.add(title);
        center.add(Box.createVerticalStrut(8));
        center.add(subtitle);

        JButton joinButton = new JButton("Join Game");
        styleButton(joinButton);
        joinButton.addActionListener(e -> showLoginScreen(null));

        JPanel south = new JPanel();
        south.setOpaque(false);
        south.add(joinButton);

        panel.add(center, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);

        swapContent(panel);
    }

    private void showLoginScreen(String errorMessage) {
        stopWaitingAnimation();

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(BG);
        panel.setBorder(BorderFactory.createEmptyBorder(30, 40, 30, 40));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;

        JLabel title = new JLabel("Log In", SwingConstants.CENTER);
        title.setFont(new Font("Segoe UI", Font.BOLD, 20));
        title.setForeground(FG);
        panel.add(title, gbc);

        JLabel userLabel = label("Username:");
        JTextField userField = new JTextField(16);

        JLabel passLabel = label("Password:");
        JPasswordField passField = new JPasswordField(16);

        JLabel statusLabel = label(errorMessage != null ? errorMessage : " ");
        statusLabel.setForeground(errorMessage != null ? ERROR : FG);

        JButton loginButton = new JButton("Log In");
        styleButton(loginButton);

        gbc.gridwidth = 1;
        gbc.gridy = 1;
        gbc.gridx = 0;
        panel.add(userLabel, gbc);
        gbc.gridx = 1;
        panel.add(userField, gbc);
        gbc.gridy = 2;
        gbc.gridx = 0;
        panel.add(passLabel, gbc);
        gbc.gridx = 1;
        panel.add(passField, gbc);

        gbc.gridy = 3;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        panel.add(loginButton, gbc);
        gbc.gridy = 4;
        panel.add(statusLabel, gbc);

        Runnable submit = () -> {
            String user = userField.getText().trim();
            String pass = new String(passField.getPassword());
            if (user.isEmpty()) {
                statusLabel.setForeground(ERROR);
                statusLabel.setText("Please enter a username");
                return;
            }
            if (pass.isEmpty()) {
                statusLabel.setForeground(ERROR);
                statusLabel.setText("Please enter a password");
                return;
            }
            loginButton.setEnabled(false);
            userField.setEnabled(false);
            passField.setEnabled(false);
            statusLabel.setForeground(FG);
            statusLabel.setText("Connecting...");

            loginHandler.attemptLogin(user, pass, outcome -> SwingUtilities.invokeLater(() -> {
                if (outcome.success) {
                    loggedInUsername.complete(user);
                } else {
                    showLoginScreen(outcome.message != null ? outcome.message : "Login failed");
                }
            }));
        };

        loginButton.addActionListener(e -> submit.run());
        passField.addActionListener(e -> submit.run());

        swapContent(panel);
        userField.requestFocusInWindow();
    }

    private JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(FG);
        l.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        return l;
    }

    private void styleButton(JButton button) {
        button.setFont(new Font("Segoe UI", Font.BOLD, 15));
        button.setBackground(ACCENT);
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(10, 24, 10, 24));
    }

    private void swapContent(JPanel panel) {
        frame.setContentPane(panel);
        frame.revalidate();
        frame.repaint();
    }
}
