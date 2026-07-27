import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Generates 5 distinct bishop sprite frames for bB (black bishop).
 * The bishop is drawn as a tall pointed mitre shape with a cross on top,
 * clearly distinguishable from the pawn (round head, short body).
 * Run from the project root: java GenerateBishopSprite.java
 */
public class GenerateBishopSprite {

    public static void main(String[] args) throws Exception {
        String base = "src/main/resources/pieces/bB/states/";
        String[] states = {"idle", "move", "jump", "short_rest", "long_rest"};

        // Subtle bob offsets for animation frames (pixels up from center)
        int[] bobY = {0, -2, -3, -2, 0};

        for (String state : states) {
            for (int frame = 1; frame <= 5; frame++) {
                int bob = bobY[frame - 1];
                BufferedImage img = drawBishop(bob);
                String path = base + state + "/sprites/" + frame + ".png";
                ImageIO.write(img, "png", new File(path));
            }
        }
        System.out.println("bB sprites generated.");
    }

    private static BufferedImage drawBishop(int bobY) {
        int W = 100, H = 100;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Transparent background
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, W, H);
        g.setComposite(AlphaComposite.SrcOver);

        int cx = W / 2;
        int baseY = 82 + bobY;   // bottom of base
        int topY  = 18 + bobY;   // tip of mitre

        // --- Base platform ---
        g.setColor(new Color(30, 25, 20));
        g.fillRoundRect(cx - 22, baseY - 8, 44, 10, 6, 6);

        // --- Body (tapered trapezoid) ---
        int[] bodyX = {cx - 16, cx + 16, cx + 10, cx - 10};
        int[] bodyYArr = {baseY - 8, baseY - 8, baseY - 32, baseY - 32};
        g.setColor(new Color(35, 30, 25));
        g.fillPolygon(bodyX, bodyYArr, 4);

        // --- Neck (narrow cylinder) ---
        g.setColor(new Color(40, 35, 30));
        g.fillRoundRect(cx - 7, baseY - 46, 14, 16, 5, 5);

        // --- Mitre head (tall pointed oval/diamond) ---
        // Draw as a tall ellipse with a pointed top using a custom shape
        Path2D mitre = new Path2D.Double();
        int mCx = cx, mTopY = topY, mMidY = topY + 18, mBotY = baseY - 44;
        int mW = 18;
        mitre.moveTo(mCx, mTopY);                          // tip
        mitre.curveTo(mCx + mW, mTopY + 6,  mCx + mW, mMidY - 4, mCx + mW / 2, mBotY);  // right side
        mitre.lineTo(mCx - mW / 2, mBotY);                // bottom
        mitre.curveTo(mCx - mW, mMidY - 4, mCx - mW, mTopY + 6, mCx, mTopY);  // left side
        mitre.closePath();
        g.setColor(new Color(45, 38, 30));
        g.fill(mitre);

        // --- Cross on mitre ---
        g.setColor(new Color(200, 180, 120));
        int crossCy = topY + 10;
        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(cx, crossCy - 6, cx, crossCy + 6);   // vertical
        g.drawLine(cx - 5, crossCy - 1, cx + 5, crossCy - 1); // horizontal

        // --- Highlight on mitre (subtle) ---
        g.setColor(new Color(80, 70, 55, 120));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(mitre);

        // --- Dot/ball at base of mitre ---
        g.setColor(new Color(200, 180, 120));
        g.fillOval(cx - 4, baseY - 50, 8, 8);

        // --- Outline the base ---
        g.setColor(new Color(20, 15, 10, 180));
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(cx - 22, baseY - 8, 44, 10, 6, 6);

        g.dispose();
        return img;
    }
}
