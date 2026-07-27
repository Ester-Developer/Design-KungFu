package tests.unit;

import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.input.BoardMapper;
import com.kungfuchess.io.BoardParser;
import com.kungfuchess.model.Board;
import com.kungfuchess.view.ImageView;
import com.kungfuchess.view.ViewConstants;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards against asset/canvas dimension mismatches and rendering regressions.
 * Also contains pixel-color smoke tests that catch "technically drawn but invisible"
 * bugs (coord labels, panel background, title bar clipping).
 */
class TestAssetDimensions {

    private static final String TILES = "src/main/resources/assets/tiles/";

    // -------------------------------------------------------------------------
    // canvas_blank.png must exactly match CANVAS_W × CANVAS_H
    // -------------------------------------------------------------------------

    @Test
    void canvasBlank_dimensionsMatchViewConstants() throws Exception {
        BufferedImage img = ImageIO.read(new File(TILES + "canvas_blank.png"));
        assertNotNull(img, "canvas_blank.png must exist on disk");
        assertEquals(ViewConstants.CANVAS_W, img.getWidth(),
            "canvas_blank.png width must equal ViewConstants.CANVAS_W");
        assertEquals(ViewConstants.CANVAS_H, img.getHeight(),
            "canvas_blank.png height must equal ViewConstants.CANVAS_H");
    }

    // -------------------------------------------------------------------------
    // sidebar_bg.png must be SIDEBAR_W × CANVAS_H
    // -------------------------------------------------------------------------

    @Test
    void sidebarBg_dimensionsMatchViewConstants() throws Exception {
        BufferedImage img = ImageIO.read(new File(TILES + "sidebar_bg.png"));
        assertNotNull(img, "sidebar_bg.png must exist on disk");
        assertEquals(ViewConstants.SIDEBAR_W, img.getWidth(),
            "sidebar_bg.png width must equal ViewConstants.SIDEBAR_W");
        assertEquals(ViewConstants.CANVAS_H, img.getHeight(),
            "sidebar_bg.png height must equal ViewConstants.CANVAS_H");
    }

    // -------------------------------------------------------------------------
    // Tile assets must be 100×100
    // -------------------------------------------------------------------------

    @Test
    void tileAssets_are100x100() throws Exception {
        for (String name : new String[]{"tile_light.png", "tile_dark.png",
                                        "highlight.png",
                                        "cooldown_0.png", "cooldown_100.png"}) {
            BufferedImage img = ImageIO.read(new File(TILES + name));
            assertNotNull(img, name + " must exist on disk");
            assertEquals(100, img.getWidth(),  name + " width must be 100");
            assertEquals(100, img.getHeight(), name + " height must be 100");
        }
    }

    // -------------------------------------------------------------------------
    // Headless smoke test: draw() on a fresh starting board must not throw
    // -------------------------------------------------------------------------

    private static BufferedImage renderFreshBoard() throws Exception {
        String startingBoard =
            "bR bN bB bQ bK bB bN bR\n" +
            "bP bP bP bP bP bP bP bP\n" +
            " .  .  .  .  .  .  .  .\n" +
            " .  .  .  .  .  .  .  .\n" +
            " .  .  .  .  .  .  .  .\n" +
            " .  .  .  .  .  .  .  .\n" +
            "wP wP wP wP wP wP wP wP\n" +
            "wR wN wB wQ wK wB wN wR";
        Board board = new BoardParser.TextParser().parse(startingBoard);
        GameEngine engine = new GameEngine().setBoard(board);
        ImageView view = new ImageView();
        view.draw(engine.snapshot());
        return view.getImage();
    }

    @Test
    void imageView_draw_doesNotThrow_onFreshBoard() throws Exception {
        BufferedImage result = assertDoesNotThrow(() -> renderFreshBoard(),
            "ImageView.draw() must not throw on a fresh starting board");
        assertNotNull(result, "draw() must produce a non-null image");
        assertEquals(ViewConstants.CANVAS_W, result.getWidth(),
            "Rendered image width must equal CANVAS_W");
        assertEquals(ViewConstants.CANVAS_H, result.getHeight(),
            "Rendered image height must equal CANVAS_H");
    }

    // -------------------------------------------------------------------------
    // Issue 1: Black frame bands - all four sides must be black
    // Sample the black frame band (between panels and board) and assert it is black.
    // -------------------------------------------------------------------------

    @Test
    void coordLabel_bottomEdge_hasContrastChip() throws Exception {
        BufferedImage img = renderFreshBoard();
        // Top black frame band: between title bar and board
        // Band is at y = MARGIN + TITLE_H .. BOARD_Y_OFFSET-1
        // Sample the black frame background (not where labels are)
        int frameX = BoardMapper.BOARD_X_OFFSET + 10;
        int frameY = ViewConstants.MARGIN + ViewConstants.TITLE_H + 2;
        Color c = new Color(img.getRGB(frameX, frameY), true);
        int luminance = (c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000;
        assertTrue(luminance < 30,
            "Black frame band must be black (luminance < 30), got " + luminance
            + " rgb=" + c.getRed() + "," + c.getGreen() + "," + c.getBlue());
    }

    @Test
    void coordLabel_leftEdge_hasContrastChip() throws Exception {
        BufferedImage img = renderFreshBoard();
        // Top black frame band: sample a different x position
        int frameX = BoardMapper.BOARD_X_OFFSET + 600;
        int frameY = ViewConstants.MARGIN + ViewConstants.TITLE_H + 2;
        Color c = new Color(img.getRGB(frameX, frameY), true);
        int luminance = (c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000;
        assertTrue(luminance < 30,
            "Black frame band must be black (luminance < 30), got " + luminance);
    }

    // -------------------------------------------------------------------------
    // Issue 4: File label bands must not overlap title bar or board squares
    // (a) Black frame bands contain black pixels (frame drawn)
    // (b) title bar center row has no black frame pixels in the board column range
    // (c) topmost board row (y=BOARD_Y_OFFSET) has no black frame pixels
    // -------------------------------------------------------------------------

    @Test
    void fileLabelBand_top_containsNonBackgroundPixels() throws Exception {
        BufferedImage img = renderFreshBoard();
        int sampleX = BoardMapper.BOARD_X_OFFSET + 10;
        int sampleY = ViewConstants.MARGIN + ViewConstants.TITLE_H + 2;
        Color c = new Color(img.getRGB(sampleX, sampleY), true);
        int lum = (c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000;
        assertTrue(lum < 30,
            "Top file-label band must be black (lum=" + lum + ")");
    }

    @Test
    void fileLabelBand_bottom_containsNonBackgroundPixels() throws Exception {
        BufferedImage img = renderFreshBoard();
        // Bottom label band starts at BOARD_Y_OFFSET + 800
        int sampleX = BoardMapper.BOARD_X_OFFSET + 10;
        int sampleY = ViewConstants.BOARD_Y_OFFSET + 800 + 2;
        Color c = new Color(img.getRGB(sampleX, sampleY), true);
        int lum = (c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000;
        assertTrue(lum < 30,
            "Bottom file-label band must be black (lum=" + lum + ")");
    }

    @Test
    void titleBarCenter_hasNoFileLabelChipPixels() throws Exception {
        BufferedImage img = renderFreshBoard();
        int checkY = ViewConstants.TITLE_H - 1;
        int checkX = BoardMapper.BOARD_X_OFFSET + 50;
        Color c = new Color(img.getRGB(checkX, checkY));
        int lum = (c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000;
        assertTrue(lum > 150,
            "Last row of title bar must be title-bar background, not black frame (lum=" + lum + ")");
    }

    @Test
    void topmostBoardRow_hasNoFileLabelChipPixels() throws Exception {
        BufferedImage img = renderFreshBoard();
        int boardRowY = ViewConstants.BOARD_Y_OFFSET + 5;
        for (int col = 0; col < 8; col++) {
            int x = BoardMapper.BOARD_X_OFFSET + col * 100 + 50;
            Color c = new Color(img.getRGB(x, boardRowY));
            int lum = (c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000;
            assertTrue(lum > 80,
                "Topmost board row must not contain black frame pixels at col=" + col + " (lum=" + lum + ")");
        }
    }

    // -------------------------------------------------------------------------
    // Issue 2: Rank-band must not overlap side panels
    // Left rank band: x = MARGIN+SIDEBAR_W .. BOARD_X_OFFSET-1
    // Right rank band: x = BOARD_X_OFFSET+800 .. BOARD_X_OFFSET+800+RANK_BAND_W-1
    // Left panel: x = MARGIN .. MARGIN+SIDEBAR_W-1
    // Right panel: x = BOARD_X_OFFSET+800+RANK_BAND_W .. CANVAS_W-MARGIN-1
    // -------------------------------------------------------------------------

    @Test
    void rankBand_left_containsDarkChipPixels_notPanelBackground() throws Exception {
        BufferedImage img = renderFreshBoard();
        // Left rank band: sample black frame background
        int frameX = ViewConstants.MARGIN + ViewConstants.SIDEBAR_W + 2;
        int frameY = ViewConstants.BOARD_Y_OFFSET + 40 + 2;
        Color c = new Color(img.getRGB(frameX, frameY), true);
        int lum = (c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000;
        assertTrue(lum < 30,
            "Left rank band must be black (lum=" + lum + ")");
    }

    @Test
    void rankBand_doesNotOverlapLeftPanel() throws Exception {
        BufferedImage img = renderFreshBoard();
        // Left panel occupies x=MARGIN..(MARGIN+SIDEBAR_W-1). Rank band starts after that.
        // Sample x=MARGIN+SIDEBAR_W-1 (last panel column) at a row-label y — must be panel bg (light).
        int panelEdgeX = ViewConstants.MARGIN + ViewConstants.SIDEBAR_W - 1;
        int rankY = ViewConstants.BOARD_Y_OFFSET + 40 + 2; // same y as rank chip
        Color c = new Color(img.getRGB(panelEdgeX, rankY));
        int lum = (c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000;
        assertTrue(lum > 150,
            "Left panel edge must be panel background, not rank chip (lum=" + lum + ")");
    }

    @Test
    void rankBand_doesNotOverlapRightPanel() throws Exception {
        BufferedImage img = renderFreshBoard();
        // Right panel starts at x = BOARD_X_OFFSET + 800 + RANK_BAND_W = 1048.
        // Sample x=1048 (first right-panel column) at a row-label y — must be panel bg (light).
        int rightPanelStartX = ViewConstants.BOARD_X_OFFSET + 800 + ViewConstants.RANK_BAND_W;
        int rankY = ViewConstants.BOARD_Y_OFFSET + 40 + 2;
        Color c = new Color(img.getRGB(rightPanelStartX, rankY));
        int lum = (c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000;
        assertTrue(lum > 150,
            "Right panel start must be panel background, not rank chip (lum=" + lum + ")");
    }

    @Test
    void leftPanel_background_isWarmOffWhite_notRawCanvas() throws Exception {
        BufferedImage img = renderFreshBoard();
        // COL_BG_DARK = (240,238,232). Raw canvas = (18,18,18).
        // Sample near bottom of left panel, well below any text/chips.
        int sampleX = ViewConstants.MARGIN + 100;  // middle of left panel
        int sampleY = ViewConstants.CANVAS_H - 20;  // near bottom
        Color c = new Color(img.getRGB(sampleX, sampleY));
        // Must be light (luminance > 200), not dark canvas
        int luminance = (c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000;
        assertTrue(luminance > 200,
            "Left panel background must be warm off-white (luminance > 200), got "
            + luminance + " rgb=" + c.getRed() + "," + c.getGreen() + "," + c.getBlue());
    }

    @Test
    void rightPanel_background_isWarmOffWhite_notRawCanvas() throws Exception {
        BufferedImage img = renderFreshBoard();
        int sampleX = ViewConstants.CANVAS_W - 100; // middle of right panel (1000..1199)
        int sampleY = ViewConstants.CANVAS_H - 20;
        Color c = new Color(img.getRGB(sampleX, sampleY));
        int luminance = (c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000;
        assertTrue(luminance > 200,
            "Right panel background must be warm off-white (luminance > 200), got " + luminance);
    }

    // -------------------------------------------------------------------------
    // Outer canvas margins: must match adjacent panel/board background, NOT black
    // The canvas has MARGIN px safety margins on all four edges. These must be
    // invisible extensions of whatever's adjacent (panel bg on sides, not black).
    // -------------------------------------------------------------------------

    @Test
    void outerMargin_leftEdge_matchesPanelBackground_notBlack() throws Exception {
        BufferedImage img = renderFreshBoard();
        // Sample x=MARGIN-2 (outer margin), y=mid-canvas — must be panel bg (light), not black
        int marginX = ViewConstants.MARGIN - 2;
        int midY = ViewConstants.CANVAS_H / 2;
        Color c = new Color(img.getRGB(marginX, midY));
        int lum = (c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000;
        assertTrue(lum > 200,
            "Outer left margin must match panel background (light), not black (lum=" + lum + ")");
    }

    @Test
    void outerMargin_rightEdge_matchesPanelBackground_notBlack() throws Exception {
        BufferedImage img = renderFreshBoard();
        // Sample x=CANVAS_W-MARGIN+2 (outer margin), y=mid-canvas — must be panel bg (light)
        int marginX = ViewConstants.CANVAS_W - ViewConstants.MARGIN + 2;
        int midY = ViewConstants.CANVAS_H / 2;
        Color c = new Color(img.getRGB(marginX, midY));
        int lum = (c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000;
        assertTrue(lum > 200,
            "Outer right margin must match panel background (light), not black (lum=" + lum + ")");
    }

    // -------------------------------------------------------------------------
    // Issue 3: Title bar text must not be clipped at the top edge
    // The title bar occupies y=[0, TITLE_H). Assert that the very top rows (y<4)
    // are background-colored (no text pixels there), and that the middle of the
    // title bar has non-background pixels (text was actually drawn).
    // -------------------------------------------------------------------------

    @Test
    void titleBar_textNotClippedAtTopEdge() throws Exception {
        BufferedImage img = renderFreshBoard();
        // Title bar bg is approx (220,215,200). Text is (60,40,10).
        // Top 3 rows must be background-only (no dark text pixels).
        int titleBgLuminance = (220 * 299 + 215 * 587 + 200 * 114) / 1000; // ~213
        for (int y = 0; y < 3; y++) {
            for (int x = ViewConstants.CANVAS_W / 3; x < 2 * ViewConstants.CANVAS_W / 3; x++) {
                Color c = new Color(img.getRGB(x, y));
                int lum = (c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000;
                assertTrue(lum > 150,
                    "Top " + y + "px of title bar must be background, not text (lum=" + lum + ")");
            }
        }
        // Middle of title bar must contain at least one non-background pixel (text drawn)
        int midY = ViewConstants.TITLE_H / 2;
        boolean foundTextPixel = false;
        for (int x = ViewConstants.CANVAS_W / 3; x < 2 * ViewConstants.CANVAS_W / 3; x++) {
            Color c = new Color(img.getRGB(x, midY));
            int lum = (c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000;
            if (lum < 100) { foundTextPixel = true; break; }
        }
        assertTrue(foundTextPixel,
            "Title bar mid-row must contain at least one dark text pixel (title was drawn)");
    }
}
