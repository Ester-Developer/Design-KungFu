package com.kungfuchess.rules;

/**
 * Material point values for each piece kind, used by the score system.
 *
 * <p>Standard values: Pawn=1, Knight=3, Bishop=3, Rook=5, Queen=9.
 * King is assigned 0 \u2014 it is never captured in normal play (game ends when a King
 * is taken), so its point value is irrelevant in practice.</p>
 *
 * <p>This is the single authoritative place for the point table; both
 * {@link com.kungfuchess.engine.GameEngine} (score tracking) and any future UI
 * that wants to display material balance should read from here.</p>
 */
public final class PieceValues {

    private PieceValues() {}

    /**
     * @param kind the piece kind string (e.g. {@code "Pawn"}, {@code "Queen"})
     * @return the material point value for that kind, or {@code 0} if unknown
     */
    public static int valueOf(String kind) {
        if (kind == null) return 0;
        switch (kind) {
            case "Pawn":   return 1;
            case "Knight": return 3;
            case "Bishop": return 3;
            case "Rook":   return 5;
            case "Queen":  return 9;
            case "King":   return 0; // never captured in normal play
            default:       return 0;
        }
    }
}
