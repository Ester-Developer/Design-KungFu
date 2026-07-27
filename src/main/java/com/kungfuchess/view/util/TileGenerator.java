package com.kungfuchess.view.util;

import com.kungfuchess.view.ViewConstants;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Run once to regenerate all static tile assets in {@code src/main/resources/assets/tiles/}.
 *
 * <p>Canvas and sidebar dimensions are read from {@link ViewConstants} — the single
 * source of truth shared with {@link com.kungfuchess.view.ImageView}. Changing
 * {@code CANVAS_W}, {@code CANVAS_H}, or {@code TITLE_H} in {@code ViewConstants} and
 * re-running this generator is all that is needed to keep assets in sync.</p>
 *
 * <p>Assets produced:
 * <ul>
 *   <li>{@code tile_light.png} / {@code tile_dark.png} — 100×100 board squares.</li>
 *   <li>{@code canvas_blank.png} — {@code CANVAS_W × CANVAS_H} warm off-white canvas.</li>
 *   <li>{@code highlight.png} — 100×100 soft glow ring.</li>
 *   <li>{@code sidebar_bg.png} — {@code SIDEBAR_W × CANVAS_H} dark panel.</li>
 *   <li>{@code cooldown_0.png} … {@code cooldown_100.png} — radial pie-wipe overlays.</li>
 * </ul>
 */
public class TileGenerator {

    public static void main(String[] args) throws Exception {
        String dir = "src/main/resources/assets/tiles/";
        generateTile(dir + "tile_light.png", new Color(240, 217, 181), new Color(220, 190, 150));
        generateTile(dir + "tile_dark.png",  new Color(181, 136,  99), new Color(150, 105,  70));
        // Canvas background matches the panel background color (warm off-white)
        generate(dir + "canvas_blank.png", new Color(240, 238, 232),
                 ViewConstants.CANVAS_W, ViewConstants.CANVAS_H);
        generateHighlight(dir + "highlight.png");
        generateSidebarBg(dir + "sidebar_bg.png");
        generateCooldownFrames(dir);
        System.out.println("Tiles generated in " + dir);
    }

    /** Flat fill — used for canvas_blank. */
    private static void generate(String path, Color color, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ImageIO.write(img, "png", new File(path));
    }

    /**
     * Board tile with a subtle inner-border gradient for depth.
     * The centre is the base color; a 4px inner border is drawn 15% darker.
     */
    private static void generateTile(String path, Color base, Color border) throws Exception {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Fill base
        g.setColor(base);
        g.fillRect(0, 0, 100, 100);
        // Inner border — 4px, slightly darker
        g.setColor(border);
        g.setStroke(new BasicStroke(4f));
        g.drawRect(2, 2, 96, 96);
        g.dispose();
        ImageIO.write(img, "png", new File(path));
    }

    /**
     * Selection highlight: a soft glow ring rather than a solid fill.
     * Semi-transparent yellow ring (4px outer + 2px inner glow) leaves the piece visible.
     */
    private static void generateHighlight(String path) throws Exception {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Outer glow — wide, very transparent
        g.setColor(new Color(255, 230, 0, 60));
        g.setStroke(new BasicStroke(12f));
        g.drawRect(6, 6, 88, 88);
        // Inner ring — narrower, more opaque
        g.setColor(new Color(255, 220, 0, 180));
        g.setStroke(new BasicStroke(4f));
        g.drawRect(3, 3, 94, 94);
        // Corner dots for extra clarity
        g.setColor(new Color(255, 255, 100, 200));
        g.fillOval(0, 0, 8, 8);
        g.fillOval(92, 0, 8, 8);
        g.fillOval(0, 92, 8, 8);
        g.fillOval(92, 92, 8, 8);
        g.dispose();
        ImageIO.write(img, "png", new File(path));
    }

    /**
     * Sidebar background: dark panel with a subtle top-to-bottom gradient and a
     * 1px right-edge accent line.
     */
    private static void generateSidebarBg(String path) throws Exception {
        int w = ViewConstants.SIDEBAR_W;
        int h = ViewConstants.CANVAS_H;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        GradientPaint gp = new GradientPaint(0, 0, new Color(45, 45, 50),
                                              0, h,  new Color(25, 25, 30));
        g.setPaint(gp);
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(80, 80, 90));
        g.drawLine(w - 1, 0, w - 1, h);
        g.dispose();
        ImageIO.write(img, "png", new File(path));
    }

    /**
     * Cooldown overlay frames: radial "pie-wipe" sweep drawn via Arc2D.
     *
     * <p>Choice rationale: a radial sweep reads immediately as a "recharging" indicator
     * (like a game ability cooldown) rather than a flat bar, which could be confused with
     * a health bar or progress indicator. It requires only standard Java2D (Arc2D) and
     * no external library. The sweep starts at 12 o'clock and goes clockwise; at 100%
     * the full circle is filled (piece fully on cooldown), draining to 0% (no overlay).</p>
     *
     * <p>Frames: {@code cooldown_0.png} (transparent) through {@code cooldown_100.png}
     * (full circle), in steps of 10.</p>
     */
    private static void generateCooldownFrames(String dir) throws Exception {
        for (int pct = 0; pct <= 100; pct += 10) {
            BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
            if (pct > 0) {
                Graphics2D g = img.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // Semi-transparent gold fill for the swept arc
                g.setColor(new Color(255, 200, 0, 140));
                double sweepDeg = pct * 3.6; // 100% = 360°
                // Arc2D: start=90° (12 o'clock), sweep clockwise (negative in Java2D)
                Arc2D arc = new Arc2D.Double(5, 5, 90, 90, 90, -sweepDeg, Arc2D.PIE);
                g.fill(arc);
                // Thin white border ring for contrast
                g.setColor(new Color(255, 255, 255, 80));
                g.setStroke(new BasicStroke(1.5f));
                g.drawOval(5, 5, 90, 90);
                g.dispose();
            }
            ImageIO.write(img, "png", new File(dir + "cooldown_" + pct + ".png"));
        }
    }
}
