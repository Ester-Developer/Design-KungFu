package com.kungfuchess.texttests;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the text integration DSL into a board section and a list of raw lines
 * (commands interleaved with expected-board rows).
 *
 * <p>Accepts two formats:</p>
 * <ul>
 *   <li><b>Guide format</b> (preferred): {@code Board} header (no colon), board rows,
 *       blank line, then commands — no {@code Commands:} marker needed.</li>
 *   <li><b>Legacy format</b>: {@code Board:} header, board rows, {@code Commands:}
 *       marker, then commands.</li>
 * </ul>
 *
 * <p>After a {@code print board} command line, any immediately following lines that
 * are not themselves commands (i.e. not {@code print board}, {@code click …}, or
 * {@code wait …}) are treated as the expected board rows for that print. These are
 * preserved in {@link ParsedScript#rawLines()} so {@link ScriptRunner} can compare
 * them against actual output.</p>
 */
public final class ScriptParser {

    private ScriptParser() {}

    /**
     * @param rawText the full script document
     * @return parsed board text and raw post-board lines (commands + expected rows)
     */
    public static ParsedScript parse(String rawText) {
        String[] lines = rawText.split("\\r?\\n");

        List<String> boardLines   = new ArrayList<>();
        List<String> rawLines     = new ArrayList<>();

        // Detect format: legacy uses "Commands:" marker; guide uses blank-line separator.
        boolean hasCommandsMarker = rawText.contains("Commands:");

        if (hasCommandsMarker) {
            // Legacy format: Board: ... Commands: ...
            boolean inBoard    = false;
            boolean inCommands = false;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.equalsIgnoreCase("Board:") || trimmed.equalsIgnoreCase("Board")) {
                    inBoard = true;
                    continue;
                }
                if (trimmed.equalsIgnoreCase("Commands:")) {
                    inBoard    = false;
                    inCommands = true;
                    continue;
                }
                if (inBoard && !trimmed.isEmpty()) {
                    boardLines.add(trimmed);
                } else if (inCommands && !trimmed.isEmpty()) {
                    rawLines.add(trimmed);
                }
            }
        } else {
            // Guide format: Board header, board rows, blank line, then commands+expected
            boolean pastHeader    = false;
            boolean inBoardRows   = false;
            boolean inCommands    = false;
            for (String line : lines) {
                String trimmed = line.trim();
                if (!pastHeader) {
                    if (trimmed.equalsIgnoreCase("Board:") || trimmed.equalsIgnoreCase("Board")) {
                        pastHeader  = true;
                        inBoardRows = true;
                    }
                    continue;
                }
                if (inBoardRows) {
                    if (trimmed.isEmpty()) {
                        inBoardRows = false;
                        inCommands  = true;
                    } else {
                        boardLines.add(trimmed);
                    }
                } else if (inCommands) {
                    // Preserve all lines (commands and expected rows) for ScriptRunner
                    if (!trimmed.isEmpty()) {
                        rawLines.add(trimmed);
                    }
                }
            }
        }

        return new ParsedScript(String.join("\n", boardLines), rawLines);
    }

    /**
     * The board section (joined back into a multi-line string) and the raw post-board
     * lines, which include both command lines and expected-board rows interleaved after
     * each {@code print board} command.
     */
    public static final class ParsedScript {
        private final String       boardText;
        private final List<String> rawLines;

        public ParsedScript(String boardText, List<String> rawLines) {
            this.boardText = boardText;
            this.rawLines  = rawLines;
        }

        public String       boardText()    { return boardText; }
        /** All post-board lines: commands and expected rows interleaved. */
        public List<String> rawLines()     { return rawLines; }
        /** Backward-compat alias — returns the same list as {@link #rawLines()}. */
        public List<String> commandLines() { return rawLines; }
    }
}
