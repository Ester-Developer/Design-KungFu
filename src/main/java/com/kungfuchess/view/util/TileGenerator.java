package com.kungfuchess.view.util;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Run once to generate tile_light.png and tile_dark.png in assets/tiles/.
 * Usage: mvn compile exec:java -Dexec.mainClass=com.kungfuchess.view.util.TileGenerator
 */
public class TileGenerator {

    public static void main(String[] args) throws Exception {
        String dir = "src/main/resources/assets/tiles/";
        generate(dir + "tile_light.png", new Color(240, 217, 181), 100, 100);
        generate(dir + "tile_dark.png",  new Color(181, 136,  99), 100, 100);
        generate(dir + "canvas_blank.png", new Color(0, 0, 0), 1000, 800);
        generateHighlight(dir + "highlight.png");
        generate(dir + "sidebar_bg.png", new Color(30, 30, 30), 200, 800);
        generateCooldownFrames(dir);
        System.out.println("Tiles generated in " + dir);
    }

    private static void generate(String path, Color color, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ImageIO.write(img, "png", new File(path));
    }

    private static void generateHighlight(String path) throws Exception {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(255, 255, 0, 100));
        g.fillRect(0, 0, 100, 100);
        g.dispose();
        ImageIO.write(img, "png", new File(path));
    }

    /** Generates cooldown_0.png through cooldown_100.png in steps of 10.
     *  Each frame is a 100x100 ARGB image with a semi-transparent yellow fill
     *  covering the top N% of the cell (100% = fully covered, drains top-down). */
    private static void generateCooldownFrames(String dir) throws Exception {
        for (int pct = 0; pct <= 100; pct += 10) {
            BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
            if (pct > 0) {
                Graphics2D g = img.createGraphics();
                g.setColor(new Color(255, 215, 0, 160));
                int fillH = pct;  // 1% == 1px on a 100px cell
                g.fillRect(0, 0, 100, fillH);
                g.dispose();
            }
            ImageIO.write(img, "png", new File(dir + "cooldown_" + pct + ".png"));
        }
    }
}
