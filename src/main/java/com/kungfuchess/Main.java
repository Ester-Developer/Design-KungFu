package com.kungfuchess;

import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.io.BoardParser;
import com.kungfuchess.model.Board;
import com.kungfuchess.texttests.ScriptParser;
import com.kungfuchess.texttests.ScriptParser.ParsedScript;
import com.kungfuchess.texttests.ScriptRunner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Application entry point (the equivalent of {@code app.py}).
 *
 * <p>Reads a script on stdin in the form:</p>
 * <pre>
 * Board:
 * wK . . bK
 * . . . .
 * wR . . bR
 * Commands:
 * print board
 * </pre>
 * <p>Splits it via {@link ScriptParser}, parses the board section via {@link
 * BoardParser.TextParser}, then drives the command lines against a fresh {@link GameEngine}
 * via {@link ScriptRunner}. A malformed board prints a single {@code "ERROR <CODE>"}
 * line (see {@link BoardParser.BoardParseException.ErrorCode}) and exits without running any
 * commands.</p>
 */
public class Main {

    public static void main(String[] args) throws Exception {
        System.out.println("Program started successfully!");
        String script = readAll(System.in);

        ParsedScript parsed = ScriptParser.parse(script);

        Board board;
        try {
            board = new BoardParser.TextParser().parse(parsed.boardText());
        } catch (BoardParser.BoardParseException e) {
            System.out.println("ERROR " + e.getErrorCode());
            return;
        }

        GameEngine engine = new GameEngine().setBoard(board);
        ScriptRunner.runScript(parsed.commandLines(), engine);
    }

    private static String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
}
