package com.kungfuchess.client;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;

/**
 * Transparent banner/panel shown as the game window's glass pane. Combines two
 * independent concerns without touching the {@link com.kungfuchess.view.Renderer}/
 * {@code ImageView} drawing pipeline underneath:
 * <ul>
 *   <li>NORTH — the opponent-disconnected countdown banner.</li>
 *   <li>SOUTH — Exit / Play Again buttons, shown once the game ends (positioned away
 *       from {@code ImageView}'s own centered "GAME OVER" scrim so they don't overlap).</li>
 * </ul>
 * Neither region has mouse listeners outside its own buttons, so board clicks pass
 * through to the game normally while both are hidden.
 */
final class GameOverlayPanel extends JPanel {

    interface GameOverHandler {
        void onExit();
        void onPlayAgain();
    }

    private final JLabel disconnectLabel;
    private final JPanel gameOverBox;
    private final JLabel gameOverLabel;

    GameOverlayPanel(GameOverHandler handler) {
        setOpaque(false);
        setLayout(new BorderLayout());

        // NORTH: disconnect countdown banner
        disconnectLabel = new JLabel("", SwingConstants.CENTER);
        disconnectLabel.setOpaque(true);
        disconnectLabel.setBackground(new Color(180, 40, 40, 235));
        disconnectLabel.setForeground(Color.WHITE);
        disconnectLabel.setFont(new Font("Segoe UI", Font.BOLD, 16));
        disconnectLabel.setBorder(BorderFactory.createEmptyBorder(10, 22, 10, 22));
        disconnectLabel.setVisible(false);
        JPanel top = new JPanel(new FlowLayout(FlowLayout.CENTER));
        top.setOpaque(false);
        top.add(disconnectLabel);
        add(top, BorderLayout.NORTH);

        // SOUTH: game-over box (message + buttons), hidden until the game ends
        gameOverBox = new JPanel();
        gameOverBox.setOpaque(true);
        gameOverBox.setBackground(new Color(20, 20, 20, 235));
        gameOverBox.setLayout(new BoxLayout(gameOverBox, BoxLayout.Y_AXIS));
        gameOverBox.setBorder(BorderFactory.createEmptyBorder(16, 40, 20, 40));

        gameOverLabel = new JLabel("", SwingConstants.CENTER);
        gameOverLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        gameOverLabel.setForeground(new Color(255, 220, 60));
        gameOverLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JButton playAgainButton = new JButton("Play Again");
        JButton exitButton = new JButton("Exit");
        styleButton(playAgainButton, new Color(118, 150, 86));
        styleButton(exitButton, new Color(150, 60, 60));
        playAgainButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        exitButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        playAgainButton.addActionListener(e -> handler.onPlayAgain());
        exitButton.addActionListener(e -> handler.onExit());

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(playAgainButton);
        buttons.add(Box.createHorizontalStrut(12));
        buttons.add(exitButton);
        buttons.setAlignmentX(Component.CENTER_ALIGNMENT);

        gameOverBox.add(gameOverLabel);
        gameOverBox.add(Box.createVerticalStrut(14));
        gameOverBox.add(buttons);
        gameOverBox.setVisible(false);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER));
        bottom.setOpaque(false);
        bottom.add(gameOverBox);
        add(bottom, BorderLayout.SOUTH);
    }

    void showDisconnectCountdown(int secondsLeft) {
        disconnectLabel.setText("Opponent disconnected — forfeits in " + secondsLeft + "s");
        disconnectLabel.setVisible(true);
        revalidate();
        repaint();
    }

    void hideDisconnectCountdown() {
        disconnectLabel.setVisible(false);
        repaint();
    }

    /** Shown on the local player's own window while an unexpected drop is being retried. */
    void showReconnecting() {
        disconnectLabel.setText("Connection lost — reconnecting...");
        disconnectLabel.setVisible(true);
        revalidate();
        repaint();
    }

    void showGameOver(String message) {
        gameOverLabel.setText(message);
        gameOverBox.setVisible(true);
        revalidate();
        repaint();
    }

    private void styleButton(JButton button, Color bg) {
        button.setFont(new Font("Segoe UI", Font.BOLD, 14));
        button.setBackground(bg);
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(8, 20, 8, 20));
        button.setMaximumSize(new Dimension(160, 40));
    }
}
