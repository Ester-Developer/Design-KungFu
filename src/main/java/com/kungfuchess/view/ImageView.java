package com.kungfuchess.view;

import com.kungfuchess.engine.GameEngine.GameSnapshot;
import com.kungfuchess.engine.GameEngine.GameSnapshot.MoveLogEntry;
import com.kungfuchess.engine.GameEngine.GameSnapshot.PieceView;
import com.kungfuchess.input.BoardMapper;
import com.kungfuchess.model.Position;
import com.kungfuchess.realtime.Motion;
import com.kungfuchess.view.util.Img;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the full 1200×800 game view using only the {@link Img} API.
 *
 * <h3>Layout (left to right)</h3>
 * <ul>
 *   <li>0–199 px: left panel — Player B (black): name, score, move log.</li>
 *   <li>200–999 px: chess board (tiles + animated pieces + cooldown overlay +
 *       selection highlight + coordinate labels).</li>
 *   <li>1000–1199 px: right panel — Player A (white): name, score, move log.</li>
 * </ul>
 *
 * <h3>Visual constants</h3>
 * All colors, spacing, and font sizes are declared as named constants near the top of
 * this class so they can be retuned in one place.
 *
 * <h3>Cooldown overlay (Part 1)</h3>
 * For every stationary piece whose {@link PieceView#restUntilMs()} is in the future,
 * a radial "pie-wipe" overlay ({@code cooldown_N.png}) is composited on top of the
 * piece sprite. The fraction remaining is computed as
 * {@code (restUntilMs - clock) / (restUntilMs - restStartMs)}, clamped to [0,1], then
 * mapped to the nearest multiple of 10 (0–100).
 *
 * <h3>Animator key fix</h3>
 * On landing, the animator is re-keyed from {@code "code@from"} to {@code "code@to"}
 * so {@link #drawBoard} finds the correct rest-state animator for the stationary piece.
 * The old from-key entry is removed to prevent unbounded map growth.
 *
 * <h3>Jump animation fix</h3>
 * While a jump motion is in-flight, the {@code "jump"} state is re-asserted each frame
 * to prevent frame-exhaustion auto-transition before the board resolves the landing.
 */
public class ImageView {

    // =========================================================================
    // Visual constants — retune all colors/spacing here
    // =========================================================================

    // Layout
    private static final int CELL        = BoardMapper.CELL_SIZE_PIXELS;   // 100
    private static final int BOARD_OFF_X = ViewConstants.BOARD_X_OFFSET;   // margin + sidebar + rank band
    private static final int BOARD_OFF_Y = ViewConstants.BOARD_Y_OFFSET;   // margin + title + label band
    private static final int BOARD_W     = 800;
    private static final int CANVAS_W    = ViewConstants.CANVAS_W;
    private static final int CANVAS_H    = ViewConstants.CANVAS_H;
    private static final int TITLE_H     = ViewConstants.TITLE_H;
    private static final int LEFT_SB_X   = ViewConstants.MARGIN;
    private static final int RIGHT_SB_X  = BOARD_OFF_X + BOARD_W + ViewConstants.RANK_BAND_W;

    // Sidebar layout
    private static final int   SB_PADDING      = 14;
    private static final int   SB_INNER_W      = 200 - SB_PADDING * 2;    // usable text width
    private static final int   SB_HEADER_H     = 52;   // player name row height (larger heading)
    private static final int   SB_SCORE_H      = 34;   // score chip row height
    private static final int   SB_DIVIDER_Y_OFF = 14;  // gap above divider line
    private static final int   SB_LOG_LINE_H   = 22;   // move-log row height
    private static final int   SB_LOG_ALT_H    = SB_LOG_LINE_H; // alternating row height

    private static final float FONT_PLAYER  = 2.8f;   // player name — large bold heading
    private static final float FONT_SCORE   = 1.4f;   // score value
    private static final float FONT_LABEL   = 1.1f;   // "Score:" label / turn indicator
    private static final float FONT_LOG     = 1.05f;  // move log entries
    private static final float FONT_COORD   = 0.7f;   // board coordinate labels (chip-rendered)

    // Colors — light theme
    private static final Color COL_BG_DARK      = new Color(240, 238, 232);   // warm off-white panel bg
    private static final Color COL_TEXT_BRIGHT   = new Color(30, 28, 24);      // near-black text
    private static final Color COL_TEXT_DIM      = new Color(110, 105, 95);    // muted label text
    private static final Color COL_ACCENT_WHITE  = new Color(180, 100, 20);    // warm amber — white player
    private static final Color COL_ACCENT_BLACK  = new Color(40, 100, 180);    // steel blue — black player
    private static final Color COL_SCORE_BG      = new Color(220, 215, 200);   // light tan chip bg
    private static final Color COL_DIVIDER       = new Color(180, 170, 150);   // warm gray divider
    private static final Color COL_LOG_ALT       = new Color(228, 224, 215);   // alternating row shade
    private static final Color COL_LOG_CAPTURE   = new Color(180, 50, 30);     // capture entries — dark red
    private static final Color COL_LOG_DODGE     = new Color(30, 120, 60);     // dodge entries — dark green
    private static final Color COL_LOG_JUMP      = new Color(30, 130, 50);     // jump entries — green
    private static final Color COL_LOG_SCREAM    = new Color(160, 45, 25);     // scream capture — dark red (same feel as capture)
    private static final Color COL_LOG_NORMAL    = new Color(40, 38, 34);      // plain moves — near-black
    private static final Color COL_LOG_LATEST    = new Color(20, 180, 70);     // latest move highlight — bright green
    private static final Color COL_LOG_LATEST_BG = new Color(230, 255, 235);   // latest move background — light green tint
    private static final Color COL_LOG_HEADER    = new Color(200, 195, 180);   // table header bg
    private static final Color COL_LOG_BORDER    = new Color(160, 150, 130);   // table cell borders
    private static final Color COL_COORD         = new Color(90, 85, 75);      // board labels
    private static final Color COL_TURN_ACTIVE   = new Color(30, 140, 50);     // active turn indicator
    private static final Color COL_REJECTED      = new Color(220, 60, 60, 160); // illegal-move flash
    private static final Color COL_GAMEOVER_SCRIM = new Color(0, 0, 0, 160);    // game-over dark scrim
    private static final Color COL_GAMEOVER_TEXT  = new Color(255, 220, 60);     // game-over banner text

    // Table layout for move log
    private static final int LOG_TIME_W  = 36;  // width of "Time" column in pixels
    private static final int LOG_ROW_H   = 21;  // row height including border
    private static final int LOG_HDR_H   = 20;  // header row height

    // Arc height for dodge hop animation (pixels above the cell)
    private static final int ARC_HEIGHT = 40;

    // Asset paths
    private static final String TILE_ASSETS  = "src/main/resources/assets/tiles/";
    private static final String PIECE_ASSETS = "src/main/resources/pieces/";

    // =========================================================================
    // Instance state
    // =========================================================================

    // Tile / overlay cache (loaded once)
    private Img tileLight;
    private Img tileDark;
    private Img highlight;
    private Img sidebarBg;
    private final Img[] cooldownFrames = new Img[11]; // indices 0..10 → 0%..100%

    // Frame cache: "{code}_{state}_{frameNum}" -> Img
    private final Map<String, Img> frameCache = new HashMap<>();

    // Per-piece animator, keyed by stable string id.
    // In-flight: "pieceCode@fromPosition"; stationary: "pieceCode@currentPosition".
    private final Map<String, PieceAnimator> animators = new HashMap<>();

    // Motions that were in-flight on the previous frame: id -> destination position
    private final Map<String, Position> prevMovingIdToTo = new HashMap<>();

    // Player names
    private String whitePlayerName = "White";
    private String blackPlayerName = "Black";

    // Current frame canvas
    private Img canvas;

    // =========================================================================
    // Public API
    // =========================================================================

    public void setPlayerNames(String white, String black) {
        this.whitePlayerName = white;
        this.blackPlayerName = black;
    }

    public BufferedImage getImage() { return canvas == null ? null : canvas.get(); }

    public void draw(GameSnapshot snapshot) {
        ensureAssetsLoaded();
        updateAnimatorStates(snapshot);
        tickAllAnimators();
        resetCanvas();
        drawTitleBar();
        drawLeftPanel(snapshot);
        drawBoard(snapshot);
        drawRightPanel(snapshot);
        if (snapshot.gameOver()) drawGameOverOverlay(snapshot);
    }

    // =========================================================================
    // Animator management
    // =========================================================================

    /**
     * Synchronises animator states with the model snapshot.
     *
     * <p>In-flight: key {@code "code@from"}, state re-asserted each frame (prevents
     * jump-animation auto-transition before board resolves landing).</p>
     * <p>Just landed: animator removed from from-key, transitioned to rest state,
     * re-stored under {@code "code@to"} — the key {@link #drawBoard} uses for
     * stationary pieces.</p>
     */
    private void updateAnimatorStates(GameSnapshot snapshot) {
        Map<String, Position> nowMovingIdToTo = new HashMap<>();
        for (Motion m : snapshot.activeMotions()) {
            nowMovingIdToTo.put(motionId(m), m.getTo());
        }

        // In-flight: re-assert state each frame to block auto-transition
        for (Motion m : snapshot.activeMotions()) {
            String id = motionId(m);
            PieceAnimator anim = animators.computeIfAbsent(id, k -> new PieceAnimator(null));
            String targetState = m.isJump() ? PieceState.JUMP.folderName : PieceState.MOVE.folderName;
            if (!targetState.equals(anim.currentStateName())) {
                anim.transition(targetState);
            }
        }

        // Just landed: transition to rest state, re-key to destination
        for (Map.Entry<String, Position> entry : prevMovingIdToTo.entrySet()) {
            String   id = entry.getKey();
            Position to = entry.getValue();
            if (!nowMovingIdToTo.containsKey(id)) {
                PieceAnimator anim = animators.remove(id);
                if (anim != null) {
                    String cur  = anim.currentStateName();
                    String code = id.length() >= 2 ? id.substring(0, 2) : "wR";
                    if (PieceState.MOVE.folderName.equals(cur) || PieceState.JUMP.folderName.equals(cur)) {
                        anim.transition(PieceConfig.get(code, cur).nextState);
                    }
                    animators.put(code + "@" + to, anim);
                }
            }
        }

        // Prune stale stationary animators for positions no longer on the board.
        // This prevents a captured piece's animator from lingering after waitMs resolves.
        java.util.Set<String> liveKeys = new java.util.HashSet<>();
        for (Motion m : snapshot.activeMotions()) liveKeys.add(motionId(m));
        for (GameSnapshot.PieceView pv : snapshot.pieces()) liveKeys.add(pieceViewId(pv));
        animators.keySet().removeIf(k -> !liveKeys.contains(k));

        prevMovingIdToTo.clear();
        prevMovingIdToTo.putAll(nowMovingIdToTo);
    }

    private void tickAllAnimators() {
        for (PieceAnimator anim : animators.values()) anim.tick();
    }

    private PieceAnimator animatorFor(String id) {
        return animators.computeIfAbsent(id, k -> new PieceAnimator(null));
    }

    private static String motionId(Motion m) {
        // Dodge motions have from==to; use a distinct key suffix to avoid collision
        // with the stationary animator key for the same square.
        String base = PieceConfig.pieceCode(m.getPiece()) + "@" + m.getFrom();
        return m.isDodge() ? base + "#dodge" : base;
    }

    private static String pieceViewId(PieceView pv) {
        return PieceConfig.pieceCode(pv.kind(), pv.color()) + "@" + pv.position();
    }

    // =========================================================================
    // Board rendering
    // =========================================================================

    private void drawBoard(GameSnapshot snapshot) {
        Position sel   = snapshot.selectedCell();
        long     clock = snapshot.clock();

        // Tiles + coordinate labels + selection highlight + rejection flash
        for (int row = 0; row < snapshot.boardHeight(); row++) {
            for (int col = 0; col < snapshot.boardWidth(); col++) {
                int px = BOARD_OFF_X + col * CELL, py = BOARD_OFF_Y + row * CELL;
                tile(row, col).drawOn(canvas, px, py);
                if (sel != null && sel.getRow() == row && sel.getCol() == col) {
                    highlight.drawOn(canvas, px, py);
                }
                Position rej = snapshot.rejectedDest();
                if (rej != null && rej.getRow() == row && rej.getCol() == col) {
                    Graphics2D g = canvas.get().createGraphics();
                    g.setColor(COL_REJECTED);
                    g.fillRect(px, py, CELL, CELL);
                    g.dispose();
                }
            }
        }

        // Coordinate labels outside the board
        drawCoordinateLabels(snapshot);

        // Stationary pieces + cooldown overlay
        // Only skip drawing a piece if IT ITSELF is in-flight (not just any piece at that position)
        for (PieceView pv : snapshot.pieces()) {
            Position pos = pv.position();
            // Check if THIS SPECIFIC piece (by kind+color+position matching an in-flight piece)
            // is currently in-flight FROM this position
            boolean thisPieceIsInFlight = false;
            for (Motion m : snapshot.activeMotions()) {
                if (m.getFrom().equals(pos) 
                    && m.getPiece().getKind().equals(pv.kind())
                    && m.getPiece().getColor().equals(pv.color())) {
                    thisPieceIsInFlight = true;
                    break;
                }
            }
            if (!thisPieceIsInFlight) {
                int px = BOARD_OFF_X + pos.getCol() * CELL, py = BOARD_OFF_Y + pos.getRow() * CELL;
                drawPieceAt(pieceViewId(pv), pv.kind(), pv.color(), px, py, 0);
                drawCooldownOverlay(pv, clock, px, py);
            }
        }

        // In-flight pieces at interpolated positions
        for (Motion m : snapshot.activeMotions()) {
            long   travel = m.getDueTime() - m.getStartTime();
            double raw    = travel == 0 ? 1.0
                : Math.max(0.0, Math.min(1.0, (double)(clock - m.getStartTime()) / travel));
            double t = easeInOut(raw);

            int fromX = BOARD_OFF_X + m.getFrom().getCol() * CELL, fromY = BOARD_OFF_Y + m.getFrom().getRow() * CELL;
            int toX   = BOARD_OFF_X + m.getTo().getCol()   * CELL, toY   = BOARD_OFF_Y + m.getTo().getRow()   * CELL;
            int cx = (int) Math.round(fromX + (toX - fromX) * t);
            int cy = (int) Math.round(fromY + (toY - fromY) * t);

            // Dodge: purely vertical hop-arc (from==to, no horizontal movement)
            if (m.isDodge()) {
                int arcOffset = (int) Math.round(-ARC_HEIGHT * 4 * t * (1 - t));
                cy = fromY + arcOffset;
                cx = fromX;
            }

            int punch = (raw >= 0.9 && raw < 1.0)
                ? (int) Math.round((raw - 0.9) / 0.1 * 4) : 0;
            drawPieceAt(motionId(m), m.getPiece().getKind(), m.getPiece().getColor(), cx, cy, punch);
        }
    }

    /**
     * Draws the cooldown overlay for a stationary piece that is actively resting.
     * Computes the remaining fraction and selects the nearest cooldown frame (0–100, step 10).
     * No overlay is drawn for idle pieces or pieces currently in motion.
     */
    private void drawCooldownOverlay(PieceView pv, long clock, int px, int py) {
        long until = pv.restUntilMs();
        long start = pv.restStartMs();
        if (until <= clock || until == 0 || start >= until) return; // not resting

        double remaining = (double)(until - clock) / (double)(until - start);
        remaining = Math.max(0.0, Math.min(1.0, remaining));
        int pct = (int) Math.round(remaining * 10) * 10; // nearest multiple of 10
        pct = Math.max(0, Math.min(100, pct));

        Img frame = cooldownFrames[pct / 10];
        if (frame != null) {
            frame.drawOn(canvas, px, py);
        }
    }

    /** Draws coordinate labels outside the board grid for maximum legibility. */
    private void drawCoordinateLabels(GameSnapshot snapshot) {
        int boardH = snapshot.boardHeight();
        int boardW = snapshot.boardWidth();
        int frameBand = ViewConstants.FRAME_BAND;  // single constant for all four sides
        int chipW = frameBand - 4;   // chip fits in band with 2px margin each side
        int chipH = 20;
        int fileChipW = 20;
        int fileChipH = frameBand - 4;

        // Draw uniform black frame background for all four coordinate bands
        Graphics2D g = canvas.get().createGraphics();
        g.setColor(Color.BLACK);
        
        // Top file band: from (BOARD_OFF_X, MARGIN + TITLE_H) width=BOARD_W height=frameBand
        g.fillRect(BOARD_OFF_X, ViewConstants.MARGIN + TITLE_H, BOARD_W, frameBand);
        
        // Bottom file band: from (BOARD_OFF_X, BOARD_OFF_Y + boardH*CELL) width=BOARD_W height=frameBand
        g.fillRect(BOARD_OFF_X, BOARD_OFF_Y + boardH * CELL, BOARD_W, frameBand);
        
        // Left rank band: from (MARGIN + SIDEBAR_W, BOARD_OFF_Y) width=frameBand height=boardH*CELL
        g.fillRect(ViewConstants.MARGIN + ViewConstants.SIDEBAR_W, BOARD_OFF_Y, frameBand, boardH * CELL);
        
        // Right rank band: from (BOARD_OFF_X + BOARD_W, BOARD_OFF_Y) width=frameBand height=boardH*CELL
        g.fillRect(BOARD_OFF_X + BOARD_W, BOARD_OFF_Y, frameBand, boardH * CELL);
        
        g.dispose();

        // File labels (a–h) in the top label band (between title and board)
        for (int col = 0; col < boardW; col++) {
            String label = String.valueOf((char)('a' + col));
            int cx = BOARD_OFF_X + col * CELL + (CELL - fileChipW) / 2;
            int cy = ViewConstants.MARGIN + TITLE_H + (frameBand - fileChipH) / 2;
            drawCoordLabel(label, cx, cy, fileChipW, fileChipH);
        }

        // File labels (a–h) in the bottom label band (below board)
        for (int col = 0; col < boardW; col++) {
            String label = String.valueOf((char)('a' + col));
            int cx = BOARD_OFF_X + col * CELL + (CELL - fileChipW) / 2;
            int cy = BOARD_OFF_Y + boardH * CELL + (frameBand - fileChipH) / 2;
            drawCoordLabel(label, cx, cy, fileChipW, fileChipH);
        }

        // Rank labels (8–1) in the left rank band (between left sidebar and board)
        for (int row = 0; row < boardH; row++) {
            String label = String.valueOf(boardH - row);
            int cx = ViewConstants.MARGIN + ViewConstants.SIDEBAR_W + (frameBand - chipW) / 2;
            int cy = BOARD_OFF_Y + row * CELL + (CELL - chipH) / 2;
            drawCoordLabel(label, cx, cy, chipW, chipH);
        }

        // Rank labels (8–1) in the right rank band (right of board)
        for (int row = 0; row < boardH; row++) {
            String label = String.valueOf(boardH - row);
            int cx = BOARD_OFF_X + BOARD_W + (frameBand - chipW) / 2;
            int cy = BOARD_OFF_Y + row * CELL + (CELL - chipH) / 2;
            drawCoordLabel(label, cx, cy, chipW, chipH);
        }
    }

    /**
     * Draws a single coordinate label on the black frame.
     * The label text is drawn in light color for contrast against the black frame background.
     */
    private void drawCoordLabel(String label, int chipX, int chipY, int chipW, int chipH) {
        Graphics2D g = canvas.get().createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.setColor(new Color(245, 240, 230));  // light text on black frame
        java.awt.FontMetrics fm = g.getFontMetrics();
        int tx = chipX + (chipW - fm.stringWidth(label)) / 2;
        int ty = chipY + (chipH - fm.getAscent() - fm.getDescent()) / 2 + fm.getAscent();
        g.drawString(label, tx, ty);
        g.dispose();
    }

    private void drawPieceAt(String id, String kind, String color, int cellPx, int cellPy, int punch) {
        Img frame = currentFrame(id, kind, color);
        BufferedImage bi = frame.get();
        int drawW = bi.getWidth()  - punch * 2;
        int drawH = bi.getHeight() - punch * 2;
        if (drawW <= 0 || drawH <= 0) return;

        int offX = cellPx + (CELL - drawW) / 2;
        int offY = cellPy + (CELL - drawH) / 2;

        if (punch == 0) {
            frame.drawOn(canvas, offX, offY);
        } else {
            Graphics2D g = canvas.get().createGraphics();
            g.drawImage(bi, offX, offY, offX + drawW, offY + drawH,
                        0, 0, bi.getWidth(), bi.getHeight(), null);
            g.dispose();
        }
    }

    private static double easeInOut(double p) {
        return p < 0.5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2;
    }

    // =========================================================================
    // Title bar
    // =========================================================================

    private void drawTitleBar() {
        Graphics2D g = canvas.get().createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(220, 215, 200));
        g.fillRect(0, 0, CANVAS_W, TITLE_H);
        g.setColor(COL_DIVIDER);
        g.drawLine(0, TITLE_H - 1, CANVAS_W, TITLE_H - 1);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        g.setColor(new Color(60, 40, 10));
        String title = "Kung Fu Chess";
        java.awt.FontMetrics fm = g.getFontMetrics();
        // Vertically center using ascent+descent so no clipping at top edge
        int textY = (TITLE_H - fm.getAscent() - fm.getDescent()) / 2 + fm.getAscent();
        g.drawString(title, (CANVAS_W - fm.stringWidth(title)) / 2, textY);
        g.dispose();
    }

    // =========================================================================
    // Panel rendering
    // =========================================================================

    /**
     * Left panel: Player B (black) — name, score, move log (black's moves only).
     * Player assignment: black = Player B = left panel; white = Player A = right panel.
     */
    private void drawLeftPanel(GameSnapshot snapshot) {
        Graphics2D g = canvas.get().createGraphics();
        g.setColor(COL_BG_DARK);
        g.fillRect(LEFT_SB_X, 0, ViewConstants.SIDEBAR_W, CANVAS_H);
        g.dispose();
        drawPlayerPanel(snapshot, "black", blackPlayerName,
                        snapshot.scoreBlack(), COL_ACCENT_BLACK, LEFT_SB_X);
    }

    /** Right panel: Player A (white) — name, score, turn indicator, move log. */
    private void drawRightPanel(GameSnapshot snapshot) {
        Graphics2D g = canvas.get().createGraphics();
        g.setColor(COL_BG_DARK);
        g.fillRect(RIGHT_SB_X, 0, ViewConstants.SIDEBAR_W, CANVAS_H);
        g.dispose();
        drawPlayerPanel(snapshot, "white", whitePlayerName,
                        snapshot.scoreWhite(), COL_ACCENT_WHITE, RIGHT_SB_X);
    }

    /**
     * Renders one player panel at the given x-offset.
     * Layout: player name → score chip → turn indicator → divider line → move log table.
     */
    private void drawPlayerPanel(GameSnapshot snapshot, String color, String name,
                                  int score, Color accent, int panelX) {
        int x = panelX + SB_PADDING;
        int y = SB_PADDING + SB_HEADER_H;

        // Player name — large, accent color
        canvas.putText(name, x, y, FONT_PLAYER, accent, 0);
        // Use SB_HEADER_H as the gap so the chip never overlaps the name text
        y += SB_HEADER_H;

        // Score chip
        drawScoreChip(score, x, y, accent, panelX);
        y += SB_SCORE_H + 4;

        // Turn indicator
        String turnLabel = snapshot.gameOver() ? "GAME OVER"
                : (snapshot.turn().equals(color) ? "YOUR TURN" : "waiting...");
        Color turnColor = snapshot.gameOver() ? COL_LOG_CAPTURE
                : (snapshot.turn().equals(color) ? COL_TURN_ACTIVE : COL_TEXT_DIM);
        canvas.putText(turnLabel, x, y, FONT_LABEL, turnColor, 0);
        y += 18;

        // Divider line
        y += SB_DIVIDER_Y_OFF;
        drawDividerLine(panelX, y);
        y += 10;

        // Move log table
        drawMoveLog(snapshot, color, accent, panelX, x, y);
    }

    /** Draws a score chip: filled rect background + "Score: N" text with padding. */
    private void drawScoreChip(int score, int x, int y, Color accent, int panelX) {
        Graphics2D g = canvas.get().createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int chipH = 26;
        g.setColor(COL_SCORE_BG);
        g.fillRoundRect(panelX + SB_PADDING - 4, y - chipH + 6, 120, chipH, 8, 8);
        // Accent-colored left edge on the chip
        g.setColor(accent);
        g.fillRoundRect(panelX + SB_PADDING - 4, y - chipH + 6, 5, chipH, 4, 4);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.setColor(accent);
        g.drawString("Score: " + score, x + 8, y);
        g.dispose();
    }

    /** Draws a 1px horizontal divider line across the panel. */
    private void drawDividerLine(int panelX, int y) {
        Graphics2D g = canvas.get().createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Two-tone divider: dim base + subtle bright highlight 1px below
        g.setColor(COL_DIVIDER);
        g.drawLine(panelX + SB_PADDING, y, panelX + 200 - SB_PADDING, y);
        g.setColor(new Color(90, 90, 105));
        g.drawLine(panelX + SB_PADDING, y + 1, panelX + 200 - SB_PADDING, y + 1);
        g.dispose();
    }

    /**
     * Renders the move log as a bordered table with header row ("Time" | "Move"),
     * column separator, row borders, alternating row shading, and capture/jump color-coding.
     */
    private void drawMoveLog(GameSnapshot snapshot, String color, Color accent,
                              int panelX, int x, int startY) {
        List<MoveLogEntry> entries = new ArrayList<>();
        for (MoveLogEntry e : snapshot.moveLog()) {
            if (color.equals(e.color())) entries.add(e);
        }

        int tableX  = panelX + SB_PADDING - 4;
        int tableW  = 200 - SB_PADDING * 2 + 4;
        int moveColX = tableX + LOG_TIME_W + 1; // left edge of Move column
        int y = startY;

        // Header row
        Graphics2D gh = canvas.get().createGraphics();
        gh.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        gh.setColor(COL_LOG_HEADER);
        gh.fillRect(tableX, y, tableW, LOG_HDR_H);
        gh.setColor(COL_LOG_BORDER);
        gh.drawRect(tableX, y, tableW - 1, LOG_HDR_H);
        gh.drawLine(moveColX, y, moveColX, y + LOG_HDR_H); // column separator
        gh.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        gh.setColor(COL_TEXT_DIM);
        gh.drawString("Time", tableX + 2, y + LOG_HDR_H - 3);
        gh.drawString("Move", moveColX + 3, y + LOG_HDR_H - 3);
        gh.dispose();
        y += LOG_HDR_H;

        int maxRows = (CANVAS_H - 20 - y) / LOG_ROW_H;
        int start   = Math.max(0, entries.size() - maxRows);

        for (int i = start; i < entries.size(); i++) {
            MoveLogEntry e = entries.get(i);
            Color rowBg = ((i % 2) == 0) ? COL_LOG_ALT : new Color(240, 238, 232);
            Color entryColor = e.isScream()  ? (e.isCapture() ? COL_LOG_SCREAM : COL_LOG_NORMAL)
                             : e.isCapture() ? COL_LOG_CAPTURE
                             : e.isJump()    ? COL_LOG_JUMP
                             : e.isDodge()   ? COL_LOG_DODGE
                             :                 COL_LOG_NORMAL;

            Graphics2D gr = canvas.get().createGraphics();
            gr.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // Row background
            gr.setColor(rowBg);
            gr.fillRect(tableX, y, tableW, LOG_ROW_H);

            // Row border (bottom only to avoid double-lines)
            gr.setColor(COL_LOG_BORDER);
            gr.drawLine(tableX, y + LOG_ROW_H - 1, tableX + tableW - 1, y + LOG_ROW_H - 1);
            // Left and right borders
            gr.drawLine(tableX, y, tableX, y + LOG_ROW_H - 1);
            gr.drawLine(tableX + tableW - 1, y, tableX + tableW - 1, y + LOG_ROW_H - 1);
            // Column separator
            gr.drawLine(moveColX, y, moveColX, y + LOG_ROW_H - 1);

            // Timestamp cell
            gr.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            gr.setColor(COL_TEXT_DIM);
            gr.drawString(e.timestamp(), tableX + 2, y + LOG_ROW_H - 4);

            // Move cell
            gr.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            gr.setColor(entryColor);
            gr.drawString(formatMoveText(e), moveColX + 3, y + LOG_ROW_H - 4);

            gr.dispose();
            y += LOG_ROW_H;
        }
    }

    /** Move description without timestamp (timestamp drawn separately). */
    private static String formatMoveText(MoveLogEntry e) {
        String from = squareLabel(e.from());
        String to   = squareLabel(e.to());
        StringBuilder sb = new StringBuilder();
        sb.append(e.pieceKind(), 0, Math.min(1, e.pieceKind().length()));
        if (e.isScream()) {
            sb.append(" ").append(from).append(" Scream");
            if (e.isCapture()) sb.append(" x").append(e.capturedKind(), 0, Math.min(1, e.capturedKind().length()));
        } else {
            sb.append(" ").append(from).append("\u2192").append(to);
            if (e.isCapture()) sb.append(" x").append(e.capturedKind(), 0, Math.min(1, e.capturedKind().length()));
            if (e.isJump())    sb.append(" Jump");
            if (e.isDodge())   sb.append(" (jump)");
            if (isCastling(e)) sb.append(" (Castling)");
        }
        return sb.toString();
    }

    /**
     * Formats a {@link MoveLogEntry} as explicit, human-readable text.
     * Public for test access.
     */
    public static String formatEntry(MoveLogEntry e) {
        String from = squareLabel(e.from());
        String to   = squareLabel(e.to());
        StringBuilder sb = new StringBuilder();
        sb.append(e.timestamp()).append("  ");
        sb.append(e.pieceKind()).append("  ");
        if (e.isScream()) {
            sb.append(from).append(" Scream");
            if (e.isCapture()) sb.append(" x").append(e.capturedKind());
        } else {
            sb.append(from).append(" \u2192 ").append(to);
            boolean hasSuffix = e.isJump() || e.isCapture() || e.isDodge() || isCastling(e);
            if (hasSuffix) {
                sb.append("  (");
                boolean first = true;
                if (isCastling(e)) { sb.append("Castling"); first = false; }
                if (e.isJump())    { if (!first) sb.append(", "); sb.append("Jump");    first = false; }
                if (e.isDodge())   { if (!first) sb.append(", "); sb.append("jump"); first = false; }
                if (e.isCapture()) { if (!first) sb.append(", "); sb.append("capture: ").append(e.capturedKind()); }
                sb.append(")");
            }
        }
        return sb.toString();
    }

    /** Returns true if this log entry represents a castling move (King moves 2 squares horizontally). */
    private static boolean isCastling(MoveLogEntry e) {
        if (!"King".equals(e.pieceKind())) return false;
        if (e.from() == null || e.to() == null) return false;
        return e.from().getRow() == e.to().getRow()
            && Math.abs(e.from().getCol() - e.to().getCol()) == 2;
    }

    /** Converts a Position to a chess-style square label, e.g. (6,0) → "a2". Public for test access. */
    public static String squareLabel(Position p) {
        if (p == null) return "?";
        char file = (char)('a' + p.getCol());
        int  rank = 8 - p.getRow();
        return "" + file + rank;
    }

    /**
     * Draws a full-board semi-transparent scrim with a centered "GAME OVER" banner.
     * Covers the entire 1200×845 canvas so it is unmistakable.
     */
    private void drawGameOverOverlay(GameSnapshot snapshot) {
        Graphics2D g = canvas.get().createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Dark scrim over the whole canvas
        g.setColor(COL_GAMEOVER_SCRIM);
        g.fillRect(0, 0, CANVAS_W, CANVAS_H);
        // Banner text — use snapshot.winner() which is set to the capturing player's color
        String winnerColor = snapshot.winner();
        String winner = "white".equals(winnerColor) ? "White" : "black".equals(winnerColor) ? "Black" : "?";
        String line1 = "GAME OVER";
        String line2 = winner + " wins!";
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 72));
        g.setColor(COL_GAMEOVER_TEXT);
        java.awt.FontMetrics fm = g.getFontMetrics();
        g.drawString(line1, (CANVAS_W - fm.stringWidth(line1)) / 2, CANVAS_H / 2 - 20);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 48));
        fm = g.getFontMetrics();
        g.drawString(line2, (CANVAS_W - fm.stringWidth(line2)) / 2, CANVAS_H / 2 + 50);
        g.dispose();
    }

    // =========================================================================
    // Asset helpers
    // =========================================================================

    private void ensureAssetsLoaded() {
        if (tileLight != null) return;
        tileLight = new Img().read(TILE_ASSETS + "tile_light.png");
        tileDark  = new Img().read(TILE_ASSETS + "tile_dark.png");
        highlight = new Img().read(TILE_ASSETS + "highlight.png");
        sidebarBg = new Img().read(TILE_ASSETS + "sidebar_bg.png");
        for (int i = 0; i <= 10; i++) {
            cooldownFrames[i] = new Img().read(TILE_ASSETS + "cooldown_" + (i * 10) + ".png");
        }
    }

    private void resetCanvas() {
        canvas = new Img().read(TILE_ASSETS + "canvas_blank.png");
    }

    private Img tile(int row, int col) {
        return ((row + col) % 2 == 0) ? tileLight : tileDark;
    }

    private Img currentFrame(String id, String kind, String color) {
        PieceAnimator anim  = animatorFor(id);
        String code  = PieceConfig.pieceCode(kind, color);
        String state = anim.currentStateName();
        int    frame = anim.currentFrame();
        String key   = code + "_" + state + "_" + frame;

        return frameCache.computeIfAbsent(key, k ->
            new Img().read(PIECE_ASSETS + code + "/states/" + state + "/sprites/" + frame + ".png",
                           new Dimension(CELL, CELL), true, null));
    }

    // =========================================================================
    // Test support
    // =========================================================================

    /**
     * Test-only entry point: runs only animator state synchronisation (no canvas/assets).
     * Used by {@code TestMotionAndSnapshot.afterMotionResolves_animatorAtDestinationKeyIsInRestState}.
     */
    public void updateAnimatorsOnly(GameSnapshot snapshot) {
        updateAnimatorStates(snapshot);
        tickAllAnimators();
    }

    /**
     * Test accessor: returns the current state name of the animator stored under
     * {@code key}, or {@code "idle"} if no animator exists for that key.
     */
    public String animatorStateForKey(String key) {
        PieceAnimator anim = animators.get(key);
        return anim != null ? anim.currentStateName() : PieceState.IDLE.folderName;
    }

    /**
     * Test accessor: computes the cooldown overlay frame index (0–10) that would be
     * selected for a piece with the given timing values at the given clock.
     * Returns -1 if no overlay would be drawn (piece not resting).
     */
    public static int cooldownFrameIndex(long restUntilMs, long restStartMs, long clock) {
        if (restUntilMs <= clock || restUntilMs == 0 || restStartMs >= restUntilMs) return -1;
        double remaining = (double)(restUntilMs - clock) / (double)(restUntilMs - restStartMs);
        remaining = Math.max(0.0, Math.min(1.0, remaining));
        return (int) Math.round(remaining * 10); // 0..10
    }
}
