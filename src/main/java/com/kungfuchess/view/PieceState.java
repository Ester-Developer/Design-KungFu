package com.kungfuchess.view;

/**
 * Named animation states for a chess piece.
 *
 * <p>This enum exists solely as a set of well-known state-name constants so that
 * call sites can write {@code PieceState.MOVE} instead of the raw string
 * {@code "move"}. All timing data (fps, loop flag, frame count, next-state) is
 * loaded at runtime from the per-piece JSON config files via {@link PieceConfig} —
 * there are no hardcoded values here.</p>
 */
public enum PieceState {

    IDLE      ("idle"),
    MOVE      ("move"),
    JUMP      ("jump"),
    SHORT_REST("short_rest"),
    LONG_REST ("long_rest");

    /** Folder name used in asset paths and JSON config directories. */
    public final String folderName;

    PieceState(String folderName) {
        this.folderName = folderName;
    }

    /** Looks up the enum constant whose {@link #folderName} matches {@code name}. */
    public static PieceState fromName(String name) {
        for (PieceState s : values()) {
            if (s.folderName.equals(name)) return s;
        }
        return IDLE; // safe fallback
    }
}
