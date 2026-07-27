package com.kungfuchess.view;

import com.kungfuchess.engine.GameEngine.GameSnapshot;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;

/**
 * Owns a single persistent {@link JFrame} + {@link JLabel} window.
 * Each call to {@link #render(GameSnapshot)} repaints the board in-place —
 * no new windows are created per frame.
 *
 * <p>The window adapts to the screen size: if the canvas is larger than the
 * available screen area it is scaled down uniformly to fit, so nothing is
 * ever clipped.</p>
 *
 * <p>The first call blocks via {@code invokeAndWait} until the window is fully
 * constructed, so subsequent calls are guaranteed to find {@code label} non-null.</p>
 */
public class Renderer {

    private final ImageView imageView = new ImageView();
    private JLabel label;
    private JFrame frame;

    /** Computed once on first render: uniform scale factor applied to the raw canvas. */
    private double scaleFactor = 1.0;

    /**
     * Translates a pixel coordinate from the displayed (possibly scaled) image
     * back to the raw canvas coordinate space that {@link ImageView} operates in.
     * Call this before passing mouse-event coordinates to the Controller.
     */
    public int rawX(int displayX) { return (int) Math.round(displayX / scaleFactor); }

    /** @see #rawX(int) */
    public int rawY(int displayY) { return (int) Math.round(displayY / scaleFactor); }

    /** Delegates to {@link ImageView#setPlayerNames}. */
    public void setPlayerNames(String white, String black) {
        imageView.setPlayerNames(white, black);
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
        BufferedImage raw = imageView.getImage();
        BufferedImage display = scaleFactor == 1.0 ? raw : scaleImage(raw, scaleFactor);
        ImageIcon icon = new ImageIcon(display);

        if (label == null) {
            initWindowAndWait(icon, raw.getWidth(), raw.getHeight());
        } else {
            SwingUtilities.invokeLater(() -> {
                label.setIcon(icon);
                label.repaint();
            });
        }
    }

    /**
     * Scales a BufferedImage by {@code factor} using bicubic interpolation for quality.
     */
    private static BufferedImage scaleImage(BufferedImage src, double factor) {
        int w = (int) Math.round(src.getWidth()  * factor);
        int h = (int) Math.round(src.getHeight() * factor);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                           RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                           RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                           RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private void initWindowAndWait(ImageIcon icon, int rawW, int rawH) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                // Determine available screen space (subtract taskbar / dock insets).
                GraphicsConfiguration gc =
                    GraphicsEnvironment.getLocalGraphicsEnvironment()
                                       .getDefaultScreenDevice()
                                       .getDefaultConfiguration();
                Rectangle screenBounds = gc.getBounds();
                Insets    screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
                int availW = screenBounds.width  - screenInsets.left - screenInsets.right;
                int availH = screenBounds.height - screenInsets.top  - screenInsets.bottom;

                // Leave a small margin so the window title bar + OS chrome fit.
                int marginW = 40;
                int marginH = 80; // title bar ~ 30px + some breathing room

                double scaleW = (double)(availW - marginW) / rawW;
                double scaleH = (double)(availH - marginH) / rawH;
                scaleFactor   = Math.min(1.0, Math.min(scaleW, scaleH));

                // If we are scaling, replace the icon with the correctly scaled one.
                ImageIcon displayIcon = scaleFactor < 1.0
                    ? new ImageIcon(scaleImage(icon.getImage() instanceof BufferedImage
                                               ? (BufferedImage) icon.getImage()
                                               : toBufferedImage(icon), rawW, rawH, scaleFactor))
                    : icon;

                label = new JLabel(displayIcon);
                frame = new JFrame("KF-Chess");
                frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                // Use the label as the content pane so MouseEvent coordinates
                // from the label are always (0,0)-based with no inset offset.
                frame.setContentPane(label);
                frame.pack();
                frame.setResizable(false);
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialise game window", e);
        }
    }

    /** Helper: scale an already-loaded image back from a BufferedImage, given raw dimensions. */
    private static BufferedImage toBufferedImage(ImageIcon icon) {
        int w = icon.getIconWidth();
        int h = icon.getIconHeight();
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        g.drawImage(icon.getImage(), 0, 0, null);
        g.dispose();
        return bi;
    }

    private static BufferedImage scaleImage(BufferedImage src, int rawW, int rawH, double factor) {
        int w = (int) Math.round(rawW * factor);
        int h = (int) Math.round(rawH * factor);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                           RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                           RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
