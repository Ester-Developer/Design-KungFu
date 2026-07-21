package com.kungfuchess.view;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads and caches per-piece, per-state animation/physics configuration from
 * {@code src/main/resources/pieces/{code}/states/{state}/config.json}.
 *
 * <p>The cache is keyed by {@code "{code}_{state}"} (e.g. {@code "wR_move"}) and
 * populated on first access. All timing values come exclusively from the JSON files —
 * there are no hardcoded fps, loop, or duration constants here.</p>
 *
 * <p>JSON is parsed with a minimal hand-rolled extractor; no external library is
 * required.</p>
 */
public final class PieceConfig {

    /** Parsed data for one piece-code + state combination. */
    public static final class StateConfig {
        /** Frames per second for this animation state. */
        public final int     fps;
        /** Whether this state loops indefinitely. */
        public final boolean loop;
        /** The state name to transition to when this one finishes (from JSON). */
        public final String  nextState;
        /** Movement speed in m/s (only meaningful for the "move" state; 0 otherwise). */
        public final double  speedMPerSec;
        /** Number of sprite frames found in the sprites/ folder. */
        public final int     frameCount;

        StateConfig(int fps, boolean loop, String nextState, double speedMPerSec, int frameCount) {
            this.fps          = fps;
            this.loop         = loop;
            this.nextState    = nextState;
            this.speedMPerSec = speedMPerSec;
            this.frameCount   = frameCount;
        }

        /**
         * Duration of one full play-through of this state in milliseconds.
         * For looping states this is the duration of a single cycle (one full loop).
         */
        public long durationMs() {
            if (fps <= 0 || frameCount <= 0) return 0;
            return (long) frameCount * 1000L / fps;
        }
    }

    private static final String BASE = "src/main/resources/pieces/";
    private static final Map<String, StateConfig> CACHE = new HashMap<>();

    private PieceConfig() {}

    /**
     * Returns the {@link StateConfig} for the given piece code and state name,
     * loading from disk on first access.
     *
     * @param pieceCode two-character code, e.g. {@code "wR"}, {@code "bK"}
     * @param stateName state folder name, e.g. {@code "move"}, {@code "long_rest"}
     * @return the config, or a safe default if the file cannot be read
     */
    public static StateConfig get(String pieceCode, String stateName) {
        String key = pieceCode + "_" + stateName;
        return CACHE.computeIfAbsent(key, k -> load(pieceCode, stateName));
    }

    /**
     * Converts a {@link com.kungfuchess.model.Piece} to the two-character asset code
     * used in the new folder structure: lowercase color initial first, then uppercase
     * kind initial — e.g. {@code "wR"}, {@code "bK"}, {@code "wN"}.
     *
     * <p>Knight is a special case: {@code getKind()} returns {@code "Knight"} whose
     * first character is {@code 'K'}, but the asset folder uses {@code 'N'} to avoid
     * collision with King.</p>
     */
    public static String pieceCode(com.kungfuchess.model.Piece piece) {
        char color = "white".equals(piece.getColor()) ? 'w' : 'b';
        char kind;
        switch (piece.getKind()) {
            case "Knight": kind = 'N'; break;
            default:       kind = piece.getKind().charAt(0); break;
        }
        return "" + color + kind;
    }

    // -------------------------------------------------------------------------
    // Internal loading
    // -------------------------------------------------------------------------

    private static StateConfig load(String code, String state) {
        String dir  = BASE + code + "/states/" + state + "/";
        String json = readFile(dir + "config.json");

        int    fps          = parseIntField(json, "frames_per_sec", 8);
        boolean loop        = parseBoolField(json, "is_loop", true);
        String  nextState   = parseStringField(json, "next_state_when_finished", "idle");
        double  speed       = parseDoubleField(json, "speed_m_per_sec", 0.0);
        int     frameCount  = countSprites(dir + "sprites/");

        return new StateConfig(fps, loop, nextState, speed, frameCount);
    }

    private static String readFile(String path) {
        try {
            return Files.readString(new File(path).toPath());
        } catch (IOException e) {
            return "{}";
        }
    }

    private static int countSprites(String spritesDir) {
        File dir = new File(spritesDir);
        if (!dir.isDirectory()) return 5; // safe default
        int count = 0;
        for (File f : dir.listFiles()) {
            String name = f.getName();
            if (name.endsWith(".png") && isNumeric(name.replace(".png", ""))) count++;
        }
        return count > 0 ? count : 5;
    }

    private static boolean isNumeric(String s) {
        try { Integer.parseInt(s); return true; } catch (NumberFormatException e) { return false; }
    }

    // -------------------------------------------------------------------------
    // Minimal JSON field extractors (no external library)
    // -------------------------------------------------------------------------

    private static int parseIntField(String json, String key, int def) {
        String val = extractValue(json, key);
        if (val == null) return def;
        try { return Integer.parseInt(val.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static boolean parseBoolField(String json, String key, boolean def) {
        String val = extractValue(json, key);
        if (val == null) return def;
        return "true".equalsIgnoreCase(val.trim());
    }

    private static String parseStringField(String json, String key, String def) {
        String val = extractValue(json, key);
        if (val == null) return def;
        return val.trim().replace("\"", "");
    }

    private static double parseDoubleField(String json, String key, double def) {
        String val = extractValue(json, key);
        if (val == null) return def;
        try { return Double.parseDouble(val.trim()); } catch (NumberFormatException e) { return def; }
    }

    /**
     * Extracts the raw value string for a JSON key (works for string, number, boolean
     * values in a flat or nested object — sufficient for our two-level config shape).
     */
    private static String extractValue(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;
        int start = colon + 1;
        // Skip whitespace
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return null;
        char first = json.charAt(start);
        if (first == '"') {
            // String value
            int end = json.indexOf('"', start + 1);
            return end < 0 ? null : json.substring(start + 1, end);
        }
        // Number or boolean: read until delimiter
        int end = start;
        while (end < json.length() && ",}\n\r".indexOf(json.charAt(end)) < 0) end++;
        return json.substring(start, end);
    }
}
