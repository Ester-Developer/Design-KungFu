package com.kungfuchess.realtime;
import com.kungfuchess.model.Board;

import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import com.kungfuchess.view.PieceConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Owns real-time movement: simulated elapsed time, concurrent in-flight
 * {@link Motion motions}, per-piece cooldown windows, temporary jump protections,
 * and first-move tracking (for pawn double-stepping).
 *
 * <p>Pieces move independently and concurrently — there is no global single-motion
 * lock. A piece is blocked only by its own per-piece cooldown (set when it starts
 * moving and covering the full travel + rest chain), not by any other piece's motion.
 * {@link GameEngine} enforces this via {@link #isOnCooldown(Piece)}.</p>
 *
 * <p>{@link #hasActiveMotion()} is retained for callers that need to know whether
 * <em>any</em> motion is in flight (e.g. the game-loop renderer), but it is no longer
 * used as a move gate.</p>
 */
public class RealTimeArbiter {

    /**
     * Fallback travel time per board cell (Chebyshev distance) when no piece-specific
     * speed is available. Used by the static {@link #travelTime(Position, Position)}
     * helper and by existing tests.
     */
    public static final long CELL_DURATION_MS = 1000;

    /** How long a jumped piece remains protected (immune to capture) before landing. */
    public static final long JUMP_DURATION_MS = 1000;

    private long clock = 0;

    private final List<Motion>     pendingMotions      = new ArrayList<>();
    private final List<Protection> pendingProtections  = new ArrayList<>();
    private final Set<Piece>       movedPieces         =
        Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Per-piece "available again at" timestamp (absolute game-clock ms).
     * A piece is on cooldown while {@code clock < cooldownUntil[piece]}.
     */
    private final Map<Piece, Long> cooldownUntil = new IdentityHashMap<>();

    /** A temporary shield over a cell, protecting whatever piece jumped there. */
    private static final class Protection {
        final Position position;
        final long dueTime;
        Protection(Position position, long dueTime) {
            this.position = position;
            this.dueTime  = dueTime;
        }
    }

    // -------------------------------------------------------------------------
    // Simulated time
    // -------------------------------------------------------------------------

    /** @return the arbiter's current simulated clock, in milliseconds. */
    public long getClock() { return clock; }

    // -------------------------------------------------------------------------
    // First-move tracking (for pawn double-stepping)
    // -------------------------------------------------------------------------

    /** @return {@code true} if this exact piece instance has already moved once. */
    public boolean hasMoved(Piece piece) { return movedPieces.contains(piece); }

    /** Marks this piece instance as having moved (spends its double-step eligibility). */
    public void markMoved(Piece piece) { movedPieces.add(piece); }

    // -------------------------------------------------------------------------
    // Per-piece cooldown
    // -------------------------------------------------------------------------

    /**
     * @return {@code true} if this specific piece is still within its cooldown window.
     */
    public boolean isOnCooldown(Piece piece) {
        Long until = cooldownUntil.get(piece);
        return until != null && clock < until;
    }

    /**
     * @return a read-only view of the per-piece cooldown map (identity-keyed).
     *         Used by {@link com.kungfuchess.engine.GameEngine.GameSnapshot}.
     */
    public java.util.Map<Piece, Long> getCooldownMap() {
        return Collections.unmodifiableMap(cooldownUntil);
    }

    // -------------------------------------------------------------------------
    // Motion queries (retained for renderer / game-loop use)
    // -------------------------------------------------------------------------

    /** @return {@code true} if any motion — of either color — is currently in flight. */
    public boolean hasActiveMotion() { return !pendingMotions.isEmpty(); }

    /** @return a read-only snapshot of all currently in-flight motions. */
    public List<Motion> getPendingMotions() {
        return Collections.unmodifiableList(pendingMotions);
    }

    /**
     * @param color the color to check
     * @return {@code true} if a piece of the opposite color has an unresolved motion
     *         (used by extra-route simultaneous-movement variants).
     */
    public boolean isBlockedByOppositeColor(String color) {
        for (Motion motion : pendingMotions) {
            if (!motion.getColor().equals(color)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Registering new motions
    // -------------------------------------------------------------------------

    /**
     * Starts a validated motion for {@code piece} from {@code from} to {@code to}.
     *
     * <p>Travel duration is derived from the piece's own {@code speed_m_per_sec} in
     * its {@code move} config (1 cell = 1 m). The per-piece cooldown window is set to
     * cover the full travel time plus the total duration of the rest-state chain that
     * follows (read from the JSON config chain).</p>
     *
     * @param piece the piece in flight
     * @param from  origin cell
     * @param to    destination cell
     */
    public void startMotion(Piece piece, Position from, Position to) {
        String code  = PieceConfig.pieceCode(piece);
        PieceConfig.StateConfig moveCfg = PieceConfig.get(code, "move");

        long travel  = travelTime(from, to, moveCfg.speedMPerSec);
        long dueTime = clock + travel;
        pendingMotions.add(new Motion(piece, from, to, clock, dueTime));
        markMoved(piece);

        // Cooldown = travel time + full rest chain after landing
        long restChain = restChainDurationMs(code, moveCfg.nextState);
        cooldownUntil.put(piece, dueTime + restChain);
    }

    /** Low-level escape hatch used by extra-route variants that build their own Motion. */
    public void addMotion(Motion motion) {
        pendingMotions.add(motion);
    }

    public void addProtection(Position position, long dueTime) {
        pendingProtections.add(new Protection(position, dueTime));
    }

    // -------------------------------------------------------------------------
    // Travel-time helpers
    // -------------------------------------------------------------------------

    /**
     * Travel time in ms using a piece's own speed (1 cell = 1 m).
     * Returns {@link #CELL_DURATION_MS} * distance when speed is 0 or negative
     * (safe fallback).
     */
    public static long travelTime(Position from, Position to, double speedMPerSec) {
        int dist = chebyshev(from, to);
        if (speedMPerSec <= 0) return dist * CELL_DURATION_MS;
        return Math.round(dist / speedMPerSec * 1000.0);
    }

    /**
     * Fallback travel time using the fixed {@link #CELL_DURATION_MS} constant.
     * Retained for backward compatibility with existing tests and the interpolation
     * helper in {@code ImageView}.
     */
    public static long travelTime(Position from, Position to) {
        return chebyshev(from, to) * CELL_DURATION_MS;
    }

    private static int chebyshev(Position from, Position to) {
        return Math.max(Math.abs(to.getRow() - from.getRow()),
                        Math.abs(to.getCol() - from.getCol()));
    }

    /**
     * Sums the durations of all non-looping states in the rest chain starting from
     * {@code startState}, following {@code next_state_when_finished} until a looping
     * state is reached.
     */
    private static long restChainDurationMs(String code, String startState) {
        long total = 0;
        String s = startState;
        for (int i = 0; i < 10; i++) {
            PieceConfig.StateConfig c = PieceConfig.get(code, s);
            if (c.loop) break;
            total += c.durationMs();
            s = c.nextState;
        }
        return total;
    }

    // -------------------------------------------------------------------------
    // Resolution
    // -------------------------------------------------------------------------

    /**
     * Advances the simulated clock by {@code ms} and resolves every pending motion
     * whose due time has now passed.
     *
     * @param ms    milliseconds to advance
     * @param board the board to mutate on arrival
     * @return every arrival that occurred as a result of this advance (possibly empty)
     */
    public ArrivalEvents advanceTime(long ms, Board board) {
        clock += ms;

        List<ArrivalEvents.ArrivalEvent> arrivals = new ArrayList<>();

        Iterator<Motion> it = pendingMotions.iterator();
        while (it.hasNext()) {
            Motion motion = it.next();
            if (motion.getDueTime() > clock) continue;
            it.remove();
            try {
                resolveMotion(board, motion, arrivals);
            } catch (Board.OutOfBoundsException e) {
                // Defensive: a malformed motion simply fizzles.
            }
        }

        pendingProtections.removeIf(p -> p.dueTime <= clock);

        return new ArrivalEvents(arrivals);
    }

    private void resolveMotion(Board board, Motion motion,
                                List<ArrivalEvents.ArrivalEvent> arrivals)
            throws Board.OutOfBoundsException {
        Optional<Piece> originOccupant = board.pieceAt(motion.getFrom());
        if (originOccupant.isEmpty() || originOccupant.get() != motion.getPiece()) {
            return; // piece was captured or otherwise gone — motion is moot
        }

        Optional<Piece> destinationOccupant = board.pieceAt(motion.getTo());

        if (destinationOccupant.isPresent() && isProtected(motion.getTo(), clock)) {
            // Defender jumped to safety — attacker crashes
            board.removePiece(motion.getFrom());
            return;
        }

        Piece capturedPiece = destinationOccupant.orElse(null);
        board.movePiece(motion.getFrom(), motion.getTo());
        promoteIfEligible(board, motion.getTo());

        arrivals.add(new ArrivalEvents.ArrivalEvent(
            motion.getPiece(), motion.getFrom(), motion.getTo(), capturedPiece));
    }

    private boolean isProtected(Position position, long clock) {
        for (Protection p : pendingProtections) {
            if (p.position.equals(position) && p.dueTime >= clock) return true;
        }
        return false;
    }

    private void promoteIfEligible(Board board, Position at) throws Board.OutOfBoundsException {
        Optional<Piece> occupant = board.pieceAt(at);
        if (occupant.isEmpty() || !"Pawn".equals(occupant.get().getKind())) return;

        Piece pawn = occupant.get();
        boolean reachedFarEdge =
            ("white".equals(pawn.getColor()) && at.getRow() == 0)
            || ("black".equals(pawn.getColor()) && at.getRow() == board.getHeight() - 1);
        if (!reachedFarEdge) return;

        board.removePiece(at);
        try {
            board.addPiece(at, new Piece("Queen", pawn.getColor()));
        } catch (Board.OccupiedCellException e) {
            throw new IllegalStateException("Promotion target unexpectedly occupied", e);
        }
    }

    // -------------------------------------------------------------------------
    // ArrivalEvents
    // -------------------------------------------------------------------------

    /**
     * The batch of motions that landed during one {@link #advanceTime} call.
     *
     * <p>Nested here to match the project's exact package structure.</p>
     */
    public static final class ArrivalEvents {

        /** One piece's arrival event. */
        public static final class ArrivalEvent {
            private final Piece    piece;
            private final Position source;
            private final Position destination;
            private final Piece    capturedPiece;

            public ArrivalEvent(Piece piece, Position source, Position destination,
                                Piece capturedPiece) {
                this.piece         = piece;
                this.source        = source;
                this.destination   = destination;
                this.capturedPiece = capturedPiece;
            }

            public Piece    piece()         { return piece; }
            public Position source()        { return source; }
            public Position destination()   { return destination; }
            public Piece    capturedPiece() { return capturedPiece; }

            @Override
            public String toString() {
                return "ArrivalEvent(piece=" + piece + ", source=" + source
                    + ", destination=" + destination + ", capturedPiece=" + capturedPiece + ")";
            }
        }

        private final List<ArrivalEvent> arrivals;

        public ArrivalEvents(List<ArrivalEvent> arrivals) {
            this.arrivals = Collections.unmodifiableList(arrivals);
        }

        public List<ArrivalEvent> arrivals() { return arrivals; }

        public static ArrivalEvents empty() {
            return new ArrivalEvents(Collections.emptyList());
        }
    }
}
