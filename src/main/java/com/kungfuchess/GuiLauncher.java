package com.kungfuchess;

import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.input.Controller;
import com.kungfuchess.io.BoardParser;
import com.kungfuchess.model.Board;
import com.kungfuchess.realtime.RealTimeArbiter;
import com.kungfuchess.view.Renderer;
import com.kungfuchess.view.SoundManager;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Manual visual-test entry point — opens a live window with the standard
 * starting position and a 60ms game-loop timer.
 * Does NOT replace {@link Main}'s script-mode entry point.
 */
public class GuiLauncher {

    private static final String STARTING_BOARD =
        "bR bN bB bQ bK bB bN bR\n" +
        "bP bP bP bP bP bP bP bP\n" +
        " .  .  .  .  .  .  .  .\n" +
        " .  .  .  .  .  .  .  .\n" +
        " .  .  .  .  .  .  .  .\n" +
        " .  .  .  .  .  .  .  .\n" +
        "wP wP wP wP wP wP wP wP\n" +
        "wR wN wB wQ wK wB wN wR";

    public static void main(String[] args) throws Exception {
        Board board = new BoardParser.TextParser().parse(STARTING_BOARD);
        GameEngine engine = new GameEngine().setBoard(board);

        Renderer renderer = new Renderer();
        renderer.setPlayerNames("White", "Black");

        SoundManager soundManager = new SoundManager();
        Controller controller = engine.getController();
        controller.setRenderer(renderer);
        controller.setSoundManager(soundManager);

        // Initial render — opens the window synchronously
        renderer.render(engine.snapshot());

        // Wire mouse clicks
        SwingUtilities.invokeLater(() ->
            renderer.getLabel().addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    try {
                        controller.click(e.getX(), e.getY());
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            })
        );

        // Game loop: advance simulated time and repaint every 60ms
        long[] lastTick = { System.currentTimeMillis() };
        boolean[] gameOverSoundPlayed = { false };
        Timer gameLoop = new Timer(60, e -> {
            long now     = System.currentTimeMillis();
            long elapsed = now - lastTick[0];
            lastTick[0]  = now;
            RealTimeArbiter.ArrivalEvents arrivals = engine.waitMs(elapsed);
            for (RealTimeArbiter.ArrivalEvents.ArrivalEvent arrival : arrivals.arrivals()) {
                if (arrival.capturedPiece() != null) {
                    soundManager.playCapture();
                } else {
                    soundManager.playMoveLand();
                }
            }
            if (engine.isGameOver() && !gameOverSoundPlayed[0]) {
                gameOverSoundPlayed[0] = true;
                soundManager.playGameOver();
            }
            renderer.render(engine.snapshot());
        });
        gameLoop.setCoalesce(true);
        gameLoop.start();
    }
}
