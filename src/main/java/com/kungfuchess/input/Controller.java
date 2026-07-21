package com.kungfuchess.input;
import com.kungfuchess.model.Board;

import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import com.kungfuchess.view.Renderer;
import com.kungfuchess.view.SoundManager;

import java.util.Optional;

/**
 * Translates user clicks into game commands. It does not decide chess legality —
 * that is entirely {@link com.kungfuchess.rules.RuleEngine}'s (via {@link
 * GameEngine#requestMove}) responsibility.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Receive click coordinates and convert pixels to board cells via {@link
 *       BoardMapper}.</li>
 *   <li>Maintain selected-piece state.</li>
 *   <li>On the first click, select a piece (ignoring first clicks on empty cells).</li>
 *   <li>On the second click, call {@link GameEngine#requestMove}.</li>
 *   <li>Clear the selection after every second in-board click, whether the resulting
 *       move is legal or illegal.</li>
 *   <li>If no piece is selected, ignore clicks outside the board.</li>
 *   <li>If a piece is selected, an outside-board click cancels the selection and sends
 *       no command to {@code GameEngine}.</li>
 * </ul>
 *
 * <p>The controller must not call {@code Board.movePiece} directly, and must not call
 * {@code RuleEngine} directly.</p>
 */
public final class Controller {

    private final GameEngine gameEngine;
    private Position selected;
    private Renderer renderer;
    private SoundManager soundManager;

    public Controller(GameEngine gameEngine) {
        this.gameEngine = gameEngine;
    }

    /** Optional: wire a renderer to be refreshed after every move attempt. */
    public void setRenderer(Renderer renderer) {
        this.renderer = renderer;
    }

    /** Optional: wire a sound manager to play audio feedback. */
    public void setSoundManager(SoundManager soundManager) {
        this.soundManager = soundManager;
    }

    /**
     * @param pixelX the x-coordinate in pixels
     * @param pixelY the y-coordinate in pixels
     * @return whether this click completed a move request, and if so, its source and
     * destination cells
     * @throws Exception propagated from board queries (should not occur in practice)
     */
    public ControllerResult click(int pixelX, int pixelY) throws Exception {
        boolean inBounds = BoardMapper.isPixelInBounds(gameEngine.getBoard(), pixelX, pixelY);

        if (!inBounds) {
            if (selected != null) {
                // A piece was selected: an outside-board click cancels it, no command sent.
                selected = null;
            }
            // No selection: outside-board clicks are simply ignored.
            return ControllerResult.none();
        }

        Position clicked = BoardMapper.pixelToBoard(pixelX, pixelY);

        if (selected == null) {
            Optional<Piece> occupant = gameEngine.getBoard().pieceAt(clicked);
            if (occupant.isPresent()) {
                selected = clicked;
                // Render immediately so the selection highlight appears
                if (renderer != null) renderer.render(gameEngine.snapshot());
            }
            return ControllerResult.none();
        }

        // Second in-board click: always request the move and always clear the
        // selection afterward, regardless of whether GameEngine accepts it.
        Position source = selected;
        selected = null;
        GameEngine.MoveResult result = gameEngine.requestMove(source, clicked);
        if (result.isAccepted() && soundManager != null) {
            soundManager.playMoveStart();
        }
        if (renderer != null) renderer.render(gameEngine.snapshot());
        return new ControllerResult(true, source, clicked);
    }

    /** @return the currently selected cell, if any. */
    public Optional<Position> getSelected() {
        return Optional.ofNullable(selected);
    }

    /**
     * Result of {@link #click}: whether this click completed a move request (a second
     * in-board click after a selection), and — when it did — the source and
     * destination cells that were sent to {@code GameEngine.requestMove}.
     *
     * <p>Nested here (rather than its own file) to match the project's exact package
     * structure, in which the {@code input} package holds exactly {@code BoardMapper}
     * and {@code Controller}.</p>
     */
    public static final class ControllerResult {

        private final boolean moveRequested;
        private final Position source;
        private final Position destination;

        public ControllerResult(boolean moveRequested, Position source, Position destination) {
            this.moveRequested = moveRequested;
            this.source = source;
            this.destination = destination;
        }

        public boolean moveRequested() {
            return moveRequested;
        }

        public Position source() {
            return source;
        }

        public Position destination() {
            return destination;
        }

        /** @return a result representing "no move was requested by this click". */
        public static ControllerResult none() {
            return new ControllerResult(false, null, null);
        }

        @Override
        public String toString() {
            return "ControllerResult(moveRequested=" + moveRequested
                + ", source=" + source + ", destination=" + destination + ")";
        }
    }
}
