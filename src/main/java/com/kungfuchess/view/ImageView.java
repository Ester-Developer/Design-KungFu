package com.kungfuchess.view;

import com.kungfuchess.engine.GameEngine.GameSnapshot;
import com.kungfuchess.input.BoardMapper;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import com.kungfuchess.realtime.Motion;
import com.kungfuchess.realtime.RealTimeArbiter;
import com.kungfuchess.view.util.Img;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Renders the full 1000×800 game view using only the {@link Img} API.
 * Left 800×800: chess board (tiles + animated pieces + selection highlight).
 * Right 200×800: sidebar (player names, turn indicator, move log).
 *
 * <p>All sprite paths follow the new asset convention:
 * {@code src/main/resources/pieces/{code}/states/{state}/sprites/{n}.png}
 * where {@code code} is lowercase-color + uppercase-kind (e.g. {@code "wR"}, {@code "bK"}).</p>
 *
 * <p>All animation timing (fps, loop, frame count, next-state) is read from
 * {@link PieceConfig} JSON files at runtime — no hardcoded values.</p>
 *
 * <p>Visual cooldown state is driven by the {@link GameSnapshot#cooldownUntilMs()} map
 * from the model layer, ensuring visual and legal state can never disagree.</p>
 */
public class ImageView {

    private static final int CELL      = BoardMapper.CELL_SIZE_PIXELS; // 100
    private static final int BOARD_W   = 800;
    private static final int SIDEBAR_X = BOARD_W;
    private static final String TILE_ASSETS  = "src/main/resources/assets/tiles/";
    private static final String PIECE_ASSETS = "src/main/resources/pieces/";

    // Sidebar layout
    private static final int   SB_PADDING  = 12;
    private static final float FONT_HEADER = 1.4f;
    private static final float FONT_BODY   = 1.0f;
    private static final Color COL_TEXT    = new Color(220, 220, 220);
    private static final Color COL_YELLOW  = new Color(255, 215, 0);
    private static final Color COL_DIM     = new Color(160, 160, 160);

    // Tile / overlay cache (loaded once)
    private Img tileLight;
    private Img tileDark;
    private Img highlight;
    private Img sidebarBg;
    private final Img[] cooldownFrames = new Img[11]; // [0]=0% .. [10]=100%

    // Frame cache: "{code}_{state}_{frameNum}" -> Img  e.g. "wR_move_3"
    private final Map<String, Img> frameCache = new HashMap<>();

    // Per-Piece-instance animator, keyed by identity
    private final IdentityHashMap<Piece, PieceAnimator> animators = new IdentityHashMap<>();

    // Pieces that were in-flight on the previous frame (identity set)
    private final Set<Piece> prevMoving =
        Collections.newSetFromMap(new IdentityHashMap<>());

    // Move log and player names
    private final List<String> moveLog = new ArrayList<>();
    private String whitePlayerName = "White";
    private String blackPlayerName = "Black";

    // Current frame canvas
    private Img canvas;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public void setPlayerNames(String white, String black) {
        this.whitePlayerName = white;
        this.blackPlayerName = black;
    }

    public void logMove(String entry) { moveLog.add(entry); }

    public BufferedImage getImage() { return canvas == null ? null : canvas.get(); }

    public void draw(GameSnapshot snapshot) {
        ensureAssetsLoaded();
        updateAnimatorStates(snapshot);
        tickAllAnimators();
        resetCanvas();
        drawBoard(snapshot);
        drawSidebar(snapshot);
    }

    // -------------------------------------------------------------------------
    // Animator management — driven by model-layer cooldown data
    // -------------------------------------------------------------------------

    /**
     * Synchronises animator states with the model snapshot.
     *
     * <ul>
     *   <li>Pieces newly in {@code activeMotions} → transition to {@code "move"}.</li>
     *   <li>Pieces that just left {@code activeMotions} → transition to
     *       {@code "long_rest"} (the next state after move, per JSON config).</li>
     * </ul>
     *
     * <p>The cooldown overlay fraction is computed directly from
     * {@link GameSnapshot#cooldownUntilMs()} so visual and legal state always agree.</p>
     */
    private void updateAnimatorStates(GameSnapshot snapshot) {
        Set<Piece> nowMoving = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Motion m : snapshot.activeMotions()) nowMoving.add(m.getPiece());

        // Newly in-flight → MOVE
        for (Piece p : nowMoving) {
            PieceAnimator anim = animators.computeIfAbsent(p, PieceAnimator::new);
            if ("idle".equals(anim.currentStateName())) {
                anim.transition(PieceState.MOVE.folderName);
            }
        }

        // Just landed → follow next_state_when_finished from JSON (long_rest)
        for (Piece p : prevMoving) {
            if (!nowMoving.contains(p)) {
                PieceAnimator anim = animators.get(p);
                if (anim != null && PieceState.MOVE.folderName.equals(anim.currentStateName())) {
                    String nextState = PieceConfig.get(PieceConfig.pieceCode(p), "move").nextState;
                    anim.transition(nextState);
                }
            }
        }

        prevMoving.clear();
        prevMoving.addAll(nowMoving);
    }

    private void tickAllAnimators() {
        for (PieceAnimator anim : animators.values()) anim.tick();
    }

    private PieceAnimator animatorFor(Piece piece) {
        return animators.computeIfAbsent(piece, PieceAnimator::new);
    }

    // -------------------------------------------------------------------------
    // Board rendering
    // -------------------------------------------------------------------------

    private void drawBoard(GameSnapshot snapshot) {
        Board board    = snapshot.board();
        Position sel   = snapshot.selectedCell();
        long clock     = snapshot.clock();

        Set<Piece> inFlight = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Motion m : snapshot.activeMotions()) inFlight.add(m.getPiece());

        // Static tiles, highlights, and stationary pieces
        for (int row = 0; row < snapshot.boardHeight(); row++) {
            for (int col = 0; col < snapshot.boardWidth(); col++) {
                int px = col * CELL, py = row * CELL;
                tile(row, col).drawOn(canvas, px, py);

                if (sel != null && sel.getRow() == row && sel.getCol() == col) {
                    highlight.drawOn(canvas, px, py);
                }

                try {
                    Optional<Piece> opt = board.pieceAt(new Position(row, col));
                    if (opt.isPresent()) {
                        Piece piece = opt.get();
                        if (!inFlight.contains(piece)) {
                            drawPieceAt(piece, px, py, 0);
                            drawCooldownOverlay(piece, px, py, snapshot);
                        }
                    }
                } catch (Board.OutOfBoundsException ignored) {}
            }
        }

        // In-flight pieces at interpolated positions
        for (Motion m : snapshot.activeMotions()) {
            // Use the motion's own start/due times for accurate interpolation
            long travel = m.getDueTime() - m.getStartTime();
            double raw  = travel == 0 ? 1.0
                : Math.max(0.0, Math.min(1.0, (double)(clock - m.getStartTime()) / travel));
            double t = easeInOut(raw);

            int fromX = m.getFrom().getCol() * CELL, fromY = m.getFrom().getRow() * CELL;
            int toX   = m.getTo().getCol()   * CELL, toY   = m.getTo().getRow()   * CELL;
            int cx = (int) Math.round(fromX + (toX - fromX) * t);
            int cy = (int) Math.round(fromY + (toY - fromY) * t);

            // Subtle landing punch: last 10% of travel
            int punch = (raw >= 0.9 && raw < 1.0)
                ? (int) Math.round((raw - 0.9) / 0.1 * 4) : 0;
            drawPieceAt(m.getPiece(), cx, cy, punch);
        }
    }

    private void drawPieceAt(Piece piece, int cellPx, int cellPy, int punch) {
        Img frame = currentFrame(piece);
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

    /** Cubic ease-in-out. */
    private static double easeInOut(double p) {
        return p < 0.5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2;
    }

    // -------------------------------------------------------------------------
    // Sidebar rendering
    // -------------------------------------------------------------------------

    private void drawSidebar(GameSnapshot snapshot) {
        sidebarBg.drawOn(canvas, SIDEBAR_X, 0);
        int x = SIDEBAR_X + SB_PADDING, y = 30;

        canvas.putText(blackPlayerName, x, y, FONT_HEADER, COL_TEXT, 0);
        y += 28;
        String turnLabel = snapshot.gameOver() ? "Game Over"
                : "Turn: " + capitalize(snapshot.turn());
        canvas.putText(turnLabel, x, y, FONT_BODY, COL_YELLOW, 0);
        y += 24;
        canvas.putText("------------", x, y, FONT_BODY, COL_DIM, 0);
        y += 20;
        canvas.putText("Moves:", x, y, FONT_BODY, COL_DIM, 0);
        y += 18;

        int lineH = 16, maxLines = (700 - y) / lineH;
        int start = Math.max(0, moveLog.size() - maxLines);
        for (int i = start; i < moveLog.size(); i++) {
            canvas.putText(moveLog.get(i), x, y, FONT_BODY, COL_TEXT, 0);
            y += lineH;
        }
        canvas.putText(whitePlayerName, x, 780, FONT_HEADER, COL_TEXT, 0);
    }

    // -------------------------------------------------------------------------
    // Cooldown overlay — fraction derived from model-layer cooldown data
    // -------------------------------------------------------------------------

    private void drawCooldownOverlay(Piece piece, int px, int py, GameSnapshot snapshot) {
        Long until = snapshot.cooldownUntilMs().get(piece);
        if (until == null) return;
        long clock = snapshot.clock();
        if (clock >= until) return; // cooldown already expired

        // Also skip if the piece is still in flight (overlay only during rest)
        for (Motion m : snapshot.activeMotions()) {
            if (m.getPiece() == piece) return;
        }

        // Compute how far through the rest window we are
        PieceAnimator anim = animatorFor(piece);
        double fraction = anim.restFraction();   // 0.0=just started, 1.0=done
        double remaining = 1.0 - fraction;       // 1.0=full bar, 0.0=gone
        int frameIdx = (int) Math.round(remaining * 10); // 0..10
        if (frameIdx > 0) {
            cooldownFrames[frameIdx].drawOn(canvas, px, py);
        }
    }

    // -------------------------------------------------------------------------
    // Asset helpers
    // -------------------------------------------------------------------------

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

    /**
     * Returns the current animation frame for a piece.
     * Path: {@code pieces/{code}/states/{state}/sprites/{frame}.png}
     */
    private Img currentFrame(Piece piece) {
        PieceAnimator anim = animatorFor(piece);
        String code  = PieceConfig.pieceCode(piece);
        String state = anim.currentStateName();
        int    frame = anim.currentFrame();
        String key   = code + "_" + state + "_" + frame;

        return frameCache.computeIfAbsent(key, k ->
            new Img().read(PIECE_ASSETS + code + "/states/" + state + "/sprites/" + frame + ".png",
                           new Dimension(CELL, CELL), true, null));
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? s
                : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
