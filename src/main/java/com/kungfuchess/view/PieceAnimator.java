package com.kungfuchess.view;

import com.kungfuchess.model.Piece;

/**
 * Per-piece animation state machine, driven entirely by per-piece JSON config.
 *
 * <p>All timing values (fps, loop flag, frame count, next-state name) are read from
 * {@link PieceConfig} at runtime — there are no hardcoded constants here. The piece
 * identity is needed to look up the correct asset code (e.g. {@code "wR"}).</p>
 *
 * <p>Call {@link #tick()} each render frame to advance, and {@link #currentFrame()}
 * to get the 1-based sprite number to display. Transitions follow
 * {@code next_state_when_finished} from the JSON automatically for non-looping states,
 * or can be triggered externally via {@link #transition(String)}.</p>
 */
public class PieceAnimator {

    private final String pieceCode;   // e.g. "wR", "bK" — for PieceConfig lookups
    private String stateName  = "idle";
    private int    frameIdx   = 0;    // 0-based
    private long   lastTickMs = System.currentTimeMillis();

    // Cooldown tracking: wall-clock ms when the current rest state began, and its
    // total duration (sum of all remaining rest states in the chain from this point).
    private long restStartMs      = 0;
    private long totalRestMs      = 0;

    public PieceAnimator(Piece piece) {
        this.pieceCode = PieceConfig.pieceCode(piece);
    }

    // -------------------------------------------------------------------------
    // State transitions
    // -------------------------------------------------------------------------

    /**
     * Externally trigger a transition to the named state (e.g. {@code "move"},
     * {@code "long_rest"}). No-op if already in that state.
     */
    public void transition(String newStateName) {
        if (stateName.equals(newStateName)) return;
        stateName  = newStateName;
        frameIdx   = 0;
        lastTickMs = System.currentTimeMillis();
        PieceConfig.StateConfig cfg = config();
        if (!cfg.loop) {
            // Entering a non-looping (rest) state: record start and compute total
            // remaining cooldown through the full chain.
            restStartMs = lastTickMs;
            totalRestMs = chainDurationMs(stateName);
        }
    }

    /**
     * Advance the animation based on elapsed wall-clock time.
     * Call once per render tick before reading {@link #currentFrame()}.
     */
    public void tick() {
        PieceConfig.StateConfig cfg = config();
        long now        = System.currentTimeMillis();
        long elapsed    = now - lastTickMs;
        long msPerFrame = cfg.fps > 0 ? 1000L / cfg.fps : 1000L;

        if (elapsed < msPerFrame) return;

        int steps = (int) (elapsed / msPerFrame);
        lastTickMs = now;
        int total = cfg.frameCount;

        if (cfg.loop) {
            frameIdx = (frameIdx + steps) % total;
        } else {
            frameIdx = Math.min(frameIdx + steps, total - 1);
            if (frameIdx == total - 1) {
                // Non-looping animation finished: follow next_state_when_finished
                transition(cfg.nextState);
            }
        }
    }

    /** @return 1-based frame number for use in the asset path. */
    public int currentFrame() {
        return frameIdx + 1;
    }

    /** @return the current animation state name (matches the folder name in assets). */
    public String currentStateName() {
        return stateName;
    }

    /**
     * Fraction of the total cooldown chain elapsed: 0.0 = just entered rest,
     * 1.0 = fully back to idle. Returns 0.0 when in a looping (non-rest) state.
     */
    public double restFraction() {
        PieceConfig.StateConfig cfg = config();
        if (cfg.loop || totalRestMs == 0) return 0.0;
        long elapsed = System.currentTimeMillis() - restStartMs;
        return Math.min(1.0, (double) elapsed / totalRestMs);
    }

    /** @return {@code true} if the piece is currently in any non-looping rest state. */
    public boolean isResting() {
        return !config().loop;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private PieceConfig.StateConfig config() {
        return PieceConfig.get(pieceCode, stateName);
    }

    /**
     * Sums the durations of all non-looping states in the chain starting from
     * {@code startState}, following {@code next_state_when_finished} until a looping
     * state (like {@code idle}) is reached. This gives the total cooldown window.
     */
    private long chainDurationMs(String startState) {
        long total = 0;
        String s = startState;
        // Guard against cycles (max 10 hops is more than enough)
        for (int i = 0; i < 10; i++) {
            PieceConfig.StateConfig c = PieceConfig.get(pieceCode, s);
            if (c.loop) break; // reached a looping state — chain ends
            total += c.durationMs();
            s = c.nextState;
        }
        return total;
    }
}
