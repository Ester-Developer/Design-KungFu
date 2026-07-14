package tests.integration;

import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.io.BoardParser;
import com.kungfuchess.model.Board;
import com.kungfuchess.texttests.ScriptParser;
import com.kungfuchess.texttests.ScriptRunner;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs every {@code .kfc} script in {@code src/test/resources/scripts} through the
 * exact same pipeline {@code Main} uses ({@link ScriptParser} → {@link
 * BoardParser.TextParser} → {@link GameEngine} → {@link ScriptRunner}), and checks the
 * captured stdout against the expected board state.
 *
 * <p>These scripts are the visible specification of the game: each one is a small,
 * readable scenario a non-programmer could review and confirm is correct.</p>
 *
 * <p>Scripts are loaded from the classpath (Maven copies {@code src/test/resources}
 * onto it automatically) rather than a filesystem path relative to the working
 * directory — that way the test passes the same way whether it's run via
 * {@code mvn test}, an IDE's "Run Test" button, or a CI job, regardless of what the
 * current working directory happens to be.</p>
 */
class TestTextScripts {

    private String run(String scriptFileName) throws Exception {
        String script;
        try (InputStream in = getClass().getResourceAsStream("/scripts/" + scriptFileName)) {
            if (in == null) {
                fail("Script not found on classpath: /scripts/" + scriptFileName);
                return null;
            }
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        ScriptParser.ParsedScript parsed = ScriptParser.parse(script);

        Board board = new BoardParser.TextParser().parse(parsed.boardText());
        GameEngine engine = new GameEngine().setBoard(board);

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            ScriptRunner.runScript(parsed.commandLines(), engine);
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8).strip();
    }

    @Test
    void boardParsingScriptPrintsTheBoardUnchanged() throws Exception {
        assertEquals("wK . . bK\n. . . .\nwR . . bR", run("01_board_parsing.kfc"));
    }

    @Test
    void clickToMoveScriptMovesTheKingOneStep() throws Exception {
        assertEquals(". . .\n. wK .\n. . .", run("02_click_to_move.kfc"));
    }

    @Test
    void rookMovesScriptTravelsAcrossTheBoardInRealTime() throws Exception {
        assertEquals(". . . wR\n. . . .\n. . . .", run("03_rook_moves.kfc"));
    }

    @Test
    void invalidMovesScriptLeavesTheBoardUnchanged() throws Exception {
        assertEquals("wK . .\n. . .\n. . .", run("04_invalid_moves.kfc"));
    }

    @Test
    void captureScriptRemovesTheDefendingPiece() throws Exception {
        assertEquals(". . . wR\n. . . .\n. . . .", run("05_capture.kfc"));
    }

    @Test
    void gameOverScriptRejectsMovesAfterTheKingIsCaptured() throws Exception {
        assertEquals(". . wR\n. . .\nwB . .", run("06_game_over.kfc"));
    }
}
