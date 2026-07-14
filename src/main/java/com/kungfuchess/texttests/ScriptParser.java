package com.kungfuchess.texttests;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the text integration DSL: raw script text in the form
 *
 * <pre>
 * Board:
 * wK . . bK
 * . . . .
 * wR . . bR
 * Commands:
 * print board
 * </pre>
 *
 * <p>into a board section and a list of trimmed, non-blank command lines. This is a
 * purely mechanical parsing step — it knows nothing about how to execute a command
 * (that is {@link ScriptRunner}'s job) or how to interpret board notation (that is
 * {@code BoardParser}'s job).</p>
 */
public final class ScriptParser {

    private static final String BOARD_MARKER = "Board:";
    private static final String COMMANDS_MARKER = "Commands:";

    private ScriptParser() {}

    /**
     * @param text raw script text
     * @return every non-blank, trimmed line, in order
     */
    public static List<String> parseLines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            line = line.trim();
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    /**
     * @param rawText the full script document
     * @return the board section (rejoined into a multi-line string) and the command lines
     */
    public static ParsedScript parse(String rawText) {
        List<String> lines = parseLines(rawText);

        List<String> boardLines = new ArrayList<>();
        List<String> commandLines = new ArrayList<>();
        boolean inCommands = false;

        for (String line : lines) {
            if (line.equalsIgnoreCase(BOARD_MARKER)) {
                continue;
            }
            if (line.equalsIgnoreCase(COMMANDS_MARKER)) {
                inCommands = true;
                continue;
            }
            if (inCommands) {
                commandLines.add(line);
            } else {
                boardLines.add(line);
            }
        }

        return new ParsedScript(String.join("\n", boardLines), commandLines);
    }

    /** The board section (joined back into a multi-line string) and the command lines. */
    public static final class ParsedScript {
        private final String boardText;
        private final List<String> commandLines;

        public ParsedScript(String boardText, List<String> commandLines) {
            this.boardText = boardText;
            this.commandLines = commandLines;
        }

        public String boardText() { return boardText; }
        public List<String> commandLines() { return commandLines; }
    }
}
