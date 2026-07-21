package tests.unit;

import com.kungfuchess.model.Piece;
import com.kungfuchess.view.PieceConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link PieceConfig} correctly loads and caches per-piece JSON config,
 * and that the piece-code convention (lowercase color + uppercase kind) is correct.
 */
class TestPieceConfig {

    @Test
    void pieceCodeIsLowercaseColorThenUppercaseKind() {
        assertEquals("wR", PieceConfig.pieceCode(new Piece("Rook",   "white")));
        assertEquals("bK", PieceConfig.pieceCode(new Piece("King",   "black")));
        assertEquals("wP", PieceConfig.pieceCode(new Piece("Pawn",   "white")));
        assertEquals("bN", PieceConfig.pieceCode(new Piece("Knight", "black")));
        assertEquals("wQ", PieceConfig.pieceCode(new Piece("Queen",  "white")));
        assertEquals("bB", PieceConfig.pieceCode(new Piece("Bishop", "black")));
        // King and Knight must not collide
        assertNotEquals(PieceConfig.pieceCode(new Piece("King", "white")),
                        PieceConfig.pieceCode(new Piece("Knight", "white")));
    }

    @Test
    void moveConfigHasCorrectSpeedAndNextState() {
        PieceConfig.StateConfig cfg = PieceConfig.get("wR", "move");
        assertEquals(1.5, cfg.speedMPerSec, 0.001);
        assertEquals("long_rest", cfg.nextState);
        assertTrue(cfg.loop, "move state must be looping");
    }

    @Test
    void longRestConfigIsNonLoopingAndLeadsToIdle() {
        PieceConfig.StateConfig cfg = PieceConfig.get("wR", "long_rest");
        assertFalse(cfg.loop, "long_rest must not loop");
        assertEquals("idle", cfg.nextState);
        assertEquals(2, cfg.fps);
    }

    @Test
    void shortRestConfigLeadsToLongRest() {
        PieceConfig.StateConfig cfg = PieceConfig.get("wR", "short_rest");
        assertFalse(cfg.loop);
        assertEquals("long_rest", cfg.nextState);
    }

    @Test
    void jumpConfigLeadsToShortRest() {
        PieceConfig.StateConfig cfg = PieceConfig.get("wR", "jump");
        assertFalse(cfg.loop);
        assertEquals("short_rest", cfg.nextState);
    }

    @Test
    void idleConfigIsLooping() {
        PieceConfig.StateConfig cfg = PieceConfig.get("wR", "idle");
        assertTrue(cfg.loop);
    }

    @Test
    void frameCountIsDetectedFromSpritesFolder() {
        // All shipped states have 5 sprites
        PieceConfig.StateConfig cfg = PieceConfig.get("wR", "move");
        assertEquals(5, cfg.frameCount);
    }

    @Test
    void durationMsIsFrameCountDividedByFps() {
        PieceConfig.StateConfig cfg = PieceConfig.get("wR", "long_rest");
        // 5 frames / 2 fps * 1000 = 2500ms
        assertEquals(2500L, cfg.durationMs());
    }

    @Test
    void configIsCachedAcrossMultipleCalls() {
        PieceConfig.StateConfig a = PieceConfig.get("wK", "move");
        PieceConfig.StateConfig b = PieceConfig.get("wK", "move");
        assertSame(a, b, "repeated calls must return the same cached instance");
    }

    @Test
    void allPieceCodesLoadWithoutError() {
        // Smoke-test that all 12 piece codes × 5 states load without throwing
        String[] codes  = {"wK","wQ","wR","wB","wN","wP","bK","bQ","bR","bB","bN","bP"};
        String[] states = {"idle","move","jump","short_rest","long_rest"};
        for (String code : codes) {
            for (String state : states) {
                assertDoesNotThrow(() -> PieceConfig.get(code, state),
                    "Loading " + code + "/" + state + " must not throw");
            }
        }
    }
}
