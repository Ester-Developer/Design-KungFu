package tests.integration;

import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.io.BoardParser;
import com.kungfuchess.model.Board;
import com.kungfuchess.texttests.ScriptParser;
import com.kungfuchess.texttests.ScriptRunner;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs every {@code .kfc} script in {@code src/test/resources/scripts} through the
 * exact same pipeline {@code Main} uses ({@link ScriptParser} → {@link
 * BoardParser.TextParser} → {@link GameEngine} → {@link ScriptRunner}).
 *
 * <p>Each script is self-contained: expected board rows are embedded directly after
 * each {@code print board} line. {@link ScriptRunner} owns the comparison and reports
 * failures with a diff. This class simply asserts that no failures were reported.</p>
 */
class TestTextScripts {

    private void runAndAssert(String scriptFileName) throws Exception {
        String script;
        try (InputStream in = getClass().getResourceAsStream("/scripts/" + scriptFileName)) {
            if (in == null) {
                fail("Script not found on classpath: /scripts/" + scriptFileName);
                return;
            }
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        ScriptParser.ParsedScript parsed = ScriptParser.parse(script);
        Board board = new BoardParser.TextParser().parse(parsed.boardText());
        GameEngine engine = new GameEngine().setBoard(board);

        ScriptRunner.runScriptAssertNoFailures(parsed.rawLines(), engine);
    }

    @Test
    void boardParsingScript() throws Exception {
        runAndAssert("01_board_parsing.kfc");
    }

    @Test
    void clickToMoveScript() throws Exception {
        runAndAssert("02_click_to_move.kfc");
    }

    @Test
    void rookMovesScript() throws Exception {
        runAndAssert("03_rook_moves.kfc");
    }

    @Test
    void invalidMovesScript() throws Exception {
        runAndAssert("04_invalid_moves.kfc");
    }

    @Test
    void captureScript() throws Exception {
        runAndAssert("05_capture.kfc");
    }

    @Test
    void gameOverScript() throws Exception {
        runAndAssert("06_game_over.kfc");
    }
}
