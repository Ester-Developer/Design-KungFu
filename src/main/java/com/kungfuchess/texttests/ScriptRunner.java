package com.kungfuchess.texttests;

import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.io.BoardPrinter;

import java.util.List;

/**
 * Drives already-split command lines against a {@link GameEngine}, in order.
 *
 * <p>The text integration DSL contains only three verbs, per the course's common
 * route: {@code click <x> <y>}, {@code wait <ms>}, and {@code print board}. This is
 * the single place that recognises them and calls straight into the public API
 * ({@code Controller.click}, {@code GameEngine.waitMs}, {@code BoardPrinter.print}) —
 * there is no separate command layer to keep in sync.</p>
 *
 * <p>Unrecognised lines are silently skipped so a stray/unsupported verb doesn't abort
 * the rest of the script.</p>
 */
public final class ScriptRunner {

    private static final String PRINT_BOARD = "print board";
    private static final BoardPrinter<String> PRINTER = new BoardPrinter.TextPrinter();

    private ScriptRunner() {}

    /**
     * @param commandLines already-split, trimmed, non-blank command lines
     * @param engine       the engine to drive
     * @throws Exception propagated from board/controller operations
     */
    public static void runScript(List<String> commandLines, GameEngine engine) throws Exception {
        for (String line : commandLines) {
            String lower = line.toLowerCase();

            if (lower.equals(PRINT_BOARD)) {
                System.out.println(PRINTER.print(engine.getBoard()));
            } else if (lower.startsWith("click ")) {
                String[] parts = line.split("\\s+");
                int pixelX = Integer.parseInt(parts[1]);
                int pixelY = Integer.parseInt(parts[2]);
                engine.getController().click(pixelX, pixelY);
            } else if (lower.startsWith("wait ")) {
                String[] parts = line.split("\\s+");
                long ms = Long.parseLong(parts[1]);
                engine.waitMs(ms);
            }
            // Any other verb is outside the common-route DSL and is ignored.
        }
    }
}
