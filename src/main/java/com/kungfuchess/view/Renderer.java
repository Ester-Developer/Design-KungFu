package com.kungfuchess.view;

import com.kungfuchess.engine.GameEngine.GameSnapshot;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/**
 * Owns a single persistent {@link JFrame} + {@link JLabel} window.
 * Each call to {@link #render(GameSnapshot)} repaints the board in-place —
 * no new windows are created per frame.
 *
 * <p>The first call blocks via {@code invokeAndWait} until the window is fully
 * constructed, so subsequent calls are guaranteed to find {@code label} non-null.</p>
 */
public class Renderer {

    private final ImageView imageView = new ImageView();
    private JLabel label;
    private JFrame frame;

    /** Delegates to {@link ImageView#setPlayerNames}. */
    public void setPlayerNames(String white, String black) {
        imageView.setPlayerNames(white, black);
    }

    /** Delegates to {@link ImageView#logMove}. */
    public void logMove(String entry) {
        imageView.logMove(entry);
    }

    /** @return the persistent label (null before first render). */
    public JLabel getLabel() { return label; }

    /** @return the persistent frame (null before first render). */
    public JFrame getFrame() { return frame; }

    /**
     * Renders the current game state. On the first call the window is created
     * synchronously (blocks until the EDT has finished building it). Subsequent
     * calls swap the icon and repaint without blocking.
     */
    public void render(GameSnapshot snapshot) {
        imageView.draw(snapshot);
        ImageIcon icon = new ImageIcon(imageView.getImage());

        if (label == null) {
            initWindowAndWait(icon);
        } else {
            SwingUtilities.invokeLater(() -> {
                label.setIcon(icon);
                label.repaint();
            });
        }
    }

    private void initWindowAndWait(ImageIcon icon) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                label = new JLabel(icon);
                frame = new JFrame("KF-Chess");
                frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                frame.add(label);
                frame.pack();
                // Enforce full content size — pack() alone doesn't account for
                // OS window chrome (title bar + borders) on all platforms.
                frame.setMinimumSize(frame.getSize());
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialise game window", e);
        }
    }
}
