package com.kungfuchess.texttests;

import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.io.BoardPrinter;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives already-split raw lines against a {@link GameEngine}, in order.
 *
 * <p>The text integration DSL contains three verbs: {@code click <x> <y>},
 * {@code wait <ms>}, and {@code print board}. After a {@code print board} line,
 * any immediately following non-command lines are treated as the expected board
 * rows. {@link ScriptRunner} compares the actual {@link BoardPrinter} output to
 * those expected rows and records a failure (with a diff) if they disagree.</p>
 *
 * <p>Use {@link #runScript(List, GameEngine)} to execute and collect failures, or
 * {@link #runScriptAssertNoFailures(List, GameEngine)} to throw on the first
 * mismatch.</p>
 */
public final class ScriptRunner {

    private static final String PRINT_BOARD = "print board";
    private static final BoardPrinter<String> PRINTER = new BoardPrinter.TextPrinter();

    private ScriptRunner() {}

    /**
     * Returns {@code true} if the line is a DSL command (not an expected-board row).
     */
    private static boolean isCommand(String line) {
        String lower = line.toLowerCase();
        return lower.equals(PRINT_BOARD)
            || lower.startsWith("click ")
            || lower.startsWith("wait ");
    }

    /**
     * Executes all commands in {@code rawLines} and returns a list of failure
     * messages (empty if everything matched).
     *
     * @param rawLines commands and expected rows interleaved (from {@link ScriptParser})
     * @param engine   the engine to drive
     * @return list of failure descriptions (empty = all passed)
     * @throws Exception propagated from board/controller operations
     */
    public static List<String> runScript(List<String> rawLines, GameEngine engine)
            throws Exception {
        List<String> failures = new ArrayList<>();
        int i = 0;
        while (i < rawLines.size()) {
            String line  = rawLines.get(i);
            String lower = line.toLowerCase();
            i++;

            if (lower.equals(PRINT_BOARD)) {
                // Collect following expected rows (non-command lines)
                List<String> expectedRows = new ArrayList<>();
                while (i < rawLines.size() && !isCommand(rawLines.get(i))) {
                    expectedRows.add(rawLines.get(i));
                    i++;
                }

                String actual = PRINTER.print(engine.getBoard()).strip();

                if (!expectedRows.isEmpty()) {
                    String expected = String.join("\n", expectedRows).strip();
                    if (!actual.equals(expected)) {
                        failures.add("print board mismatch:\n  expected: ["
                            + expected + "]\n  actual:   [" + actual + "]");
                    }
                } else {
                    // No expected rows in script — print to stdout for legacy callers
                    System.out.println(actual);
                }

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
        return failures;
    }

    /**
     * Convenience wrapper: runs the script and throws {@link AssertionError} if any
     * board comparison fails.
     *
     * @param rawLines commands and expected rows interleaved
     * @param engine   the engine to drive
     * @throws Exception propagated from board/controller operations
     */
    public static void runScriptAssertNoFailures(List<String> rawLines, GameEngine engine)
            throws Exception {
        List<String> failures = runScript(rawLines, engine);
        if (!failures.isEmpty()) {
            throw new AssertionError("Script failures:\n" + String.join("\n", failures));
        }
    }
}
