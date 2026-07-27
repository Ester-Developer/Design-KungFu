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
 * Owns real-time movement: simulated elapsed time, in-flight {@link Motion motions},
 * per-piece cooldown windows, temporary jump protections, and first-move tracking.
 *
 * <p>Pieces move concurrently — there is no global single-motion lock. Each piece is
 * gated only by its own per-piece cooldown (travel time + rest chain), checked by
 * {@link com.kungfuchess.engine.GameEngine} via {@link #isOnCooldown(Piece)}.</p>
 *
 * <p>The {@link #addProtection}/{@link #isProtected} mechanism is retained as a
 * dormant capability for a future "simultaneous arrival" feature (where two pieces
 * land on the same square at the same instant and one should bounce). It is NOT
 * called from {@link #startMotion} — a Knight that has landed is immediately
 * capturable like any other stationary piece.</p>
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
     * Pieces that were removed from their origin square by an enemy landing there
     * while they were in-flight. Their motion is still valid and they will land
     * at their destination normally — the origin check is skipped for these.
     */
    private final Set<Piece> displacedPieces =
        Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Per-piece "available again at" timestamp (absolute game-clock ms).
     * A piece is on cooldown while {@code clock < cooldownUntil[piece]}.
     */
    private final Map<Piece, Long> cooldownUntil = new IdentityHashMap<>();

    /**
     * Per-piece "cooldown started at" timestamp (absolute game-clock ms).
     * Set at the same time as {@link #cooldownUntil} so the renderer can compute
     * a progress fraction without needing to infer the start time indirectly.
     */
    private final Map<Piece, Long> cooldownStart = new IdentityHashMap<>();

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
     * @return the absolute game-clock ms at which this piece's cooldown ends, or
     *         {@code 0} if the piece has no active cooldown entry.
     */
    public long cooldownUntilMs(Piece piece) {
        Long until = cooldownUntil.get(piece);
        return until != null ? until : 0L;
    }

    /**
     * @return the absolute game-clock ms at which this piece's current cooldown window
     *         began, or {@code 0} if the piece has no active cooldown entry.
     *         Together with {@link #cooldownUntilMs} this lets the renderer compute a
     *         progress fraction: {@code (until - clock) / (until - start)}.
     */
    public long cooldownStartMs(Piece piece) {
        Long start = cooldownStart.get(piece);
        return start != null ? start : 0L;
    }

    /**
     * @return a read-only view of the per-piece cooldown map (identity-keyed).
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

    /** Speed multiplier applied to all motion travel times (0.70 = 30% faster). */
    public static final double SPEED_MULTIPLIER = 0.70;

    /**
     * Starts a validated motion for {@code piece} from {@code from} to {@code to}.
     *
     * <p>Travel duration is derived from the piece's own {@code speed_m_per_sec} in
     * its {@code move} config (1 cell = 1 m), then multiplied by {@link #SPEED_MULTIPLIER}
     * (30% faster). The per-piece cooldown window covers the full travel time plus the
     * rest-state chain that follows.</p>
     *
     * @param piece the piece in flight
     * @param from  origin cell
     * @param to    destination cell
     */
    public void startMotion(Piece piece, Position from, Position to) {
        String code  = PieceConfig.pieceCode(piece);
        boolean isKnightJump = "Knight".equals(piece.getKind());
        String  stateKey = isKnightJump ? "jump" : "move";
        PieceConfig.StateConfig moveCfg = PieceConfig.get(code, stateKey);

        long travel  = Math.round(travelTime(from, to, moveCfg.speedMPerSec) * SPEED_MULTIPLIER);
        long dueTime = clock + travel;
        pendingMotions.add(new Motion(piece, from, to, clock, dueTime, isKnightJump));
        markMoved(piece);

        // Cooldown = travel time + full rest chain after landing
        long restChain = restChainDurationMs(code, moveCfg.nextState);
        cooldownUntil.put(piece, dueTime + restChain);
        cooldownStart.put(piece, clock);  // record when this cooldown window began
    }

    /**
     * @return {@code true} if the given destination is already targeted by an
     * active in-flight motion. Used by {@link com.kungfuchess.engine.GameEngine}
     * to reject a second move to the same square while the first is in flight.
     *
     * <p>Dodge exception: a dodge motion targeting its own square is allowed to
     * coexist with the specific attacker motion it was issued against. This exception
     * is scoped narrowly via {@link Motion#getDodgeThreatMotion()} — only the exact
     * paired attacker+defender are exempt; any unrelated third motion is still rejected.</p>
     *
     * @param destination the square to check
     * @param requestingMotion the motion being considered (null for non-dodge checks)
     */
    public boolean isDestinationReserved(Position destination, Motion requestingMotion) {
        for (Motion m : pendingMotions) {
            if (!m.getTo().equals(destination)) continue;
            // Dodge exception: if the requesting motion is a dodge that was issued
            // against this exact existing motion, allow the coexistence.
            if (requestingMotion != null && requestingMotion.isDodge()
                    && requestingMotion.getDodgeThreatMotion() == m) continue;
            return true;
        }
        return false;
    }

    /**
     * Backward-compatible overload (no requesting motion — used for normal moves).
     */
    public boolean isDestinationReserved(Position destination) {
        return isDestinationReserved(destination, null);
    }

    /**
     * @return {@code true} if the given position is the FROM square of any currently
     * in-flight motion. Used by {@link com.kungfuchess.engine.GameEngine} to prevent
     * a second piece from targeting or departing from a square that is already vacating.
     */
    public boolean isSourceReserved(Position source) {
        for (Motion m : pendingMotions) {
            if (m.getFrom().equals(source)) return true;
        }
        return false;
    }

    /**
     * @return {@code true} if this piece kind uses a jump motion.
     * Currently always {@code false} — the jump animation state is not used.
     * Retained for API compatibility.
     */
    public static boolean isJumpPiece(String kind) {
        return false;
    }

    /** Low-level escape hatch used by extra-route variants that build their own Motion. */
    public void addMotion(Motion motion) {
        pendingMotions.add(motion);
    }

    /**
     * Executes an instant Scream: the screaming piece stays at {@code from} and
     * immediately removes the enemy piece at {@code target}. The result is returned
     * as an {@link ArrivalEvents} with a single scream-flagged event so the caller
     * can update scores and the move log normally.
     *
     * <p>No Motion is queued — the effect is immediate. The screaming piece's cooldown
     * is set to the same window as a normal move (travel=0, rest-chain follows).</p>
     *
     * @param screamer the piece performing the scream
     * @param from     the screamer's current position
     * @param target   the enemy piece's position (must be adjacent)
     * @param board    the live board to mutate
     * @return a single-event {@link ArrivalEvents} for the caller to process
     * @throws Board.OutOfBoundsException if any position is out of bounds
     */
    public ArrivalEvents startScream(Piece screamer, Position from, Position target, Board board)
            throws Board.OutOfBoundsException {
        Optional<Piece> targetOccupant = board.pieceAt(target);
        Piece capturedPiece = targetOccupant.orElse(null);
        if (capturedPiece != null) {
            board.removePiece(target);
        }

        markMoved(screamer);

        // Cooldown: use the "move" state's rest chain (screamer stays in place)
        String code = PieceConfig.pieceCode(screamer);
        PieceConfig.StateConfig moveCfg = PieceConfig.get(code, "move");
        long restChain = restChainDurationMs(code, moveCfg.nextState);
        cooldownUntil.put(screamer, clock + restChain);
        cooldownStart.put(screamer, clock);

        List<ArrivalEvents.ArrivalEvent> events = new ArrayList<>();
        events.add(new ArrivalEvents.ArrivalEvent(
            screamer, from, from, capturedPiece, false, false, false, true)); // scream=true
        return new ArrivalEvents(events);
    }

    /**
     * Registers a Dodge motion for {@code piece} at {@code square} (from==to), timed
     * to resolve {@link #DODGE_BUFFER_MS} after the threatening motion's dueTime.
     *
     * <p>The dodge motion is stored with a reference to the exact threat motion so the
     * destination-reservation exception can be scoped narrowly to this pair only.</p>
     *
     * @param piece        the defending piece
     * @param square       the square to dodge on (piece's current position)
     * @param threatMotion the specific attacker motion being countered
     */
    public void startDodge(Piece piece, Position square, Motion threatMotion) {
        long dueTime = threatMotion.getDueTime() + DODGE_BUFFER_MS;
        Motion dodge = new Motion(piece, square, square, clock, dueTime, false, true, threatMotion);
        pendingMotions.add(dodge);
        markMoved(piece);
        // Cooldown extends to cover the dodge resolution
        String code = PieceConfig.pieceCode(piece);
        PieceConfig.StateConfig moveCfg = PieceConfig.get(code, "move");
        long restChain = restChainDurationMs(code, moveCfg.nextState);
        cooldownUntil.put(piece, dueTime + restChain);
        cooldownStart.put(piece, clock);
    }

    /** Buffer (ms) added to the threat's dueTime to guarantee the dodge resolves after the attacker. */
    public static final long DODGE_BUFFER_MS = 150L;

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

        // Collect all due motions first to avoid ConcurrentModificationException
        // when resolveMotion needs to cancel a related in-flight motion.
        List<Motion> due = new ArrayList<>();
        Iterator<Motion> it = pendingMotions.iterator();
        while (it.hasNext()) {
            Motion motion = it.next();
            if (motion.getDueTime() <= clock) {
                it.remove();
                due.add(motion);
            }
        }

        for (Motion motion : due) {
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

        // --- Dodge motion resolution ---
        // A dodge motion has from==to. The piece was removed from the board when the
        // attacker arrived (see below), so we skip the origin-occupancy check and
        // treat this as a normal landing on the square (capturing whatever is there).
        if (motion.isDodge()) {
            Optional<Piece> destinationOccupant = board.pieceAt(motion.getTo());
            Piece capturedPiece = destinationOccupant.orElse(null);
            // Place the dodging piece back on its square (capturing the attacker if present)
            if (capturedPiece != null) {
                board.removePiece(motion.getTo());
            }
            try {
                board.addPiece(motion.getTo(), motion.getPiece());
            } catch (Board.OccupiedCellException e) {
                throw new IllegalStateException("Dodge landing target unexpectedly occupied", e);
            }
            boolean promoted = promoteIfEligible(board, motion.getTo());
            cooldownStart.put(motion.getPiece(), motion.getDueTime());
            arrivals.add(new ArrivalEvents.ArrivalEvent(
                motion.getPiece(), motion.getFrom(), motion.getTo(),
                capturedPiece, false, promoted, true));  // dodge=true
            return;
        }

        // --- Normal motion resolution ---
        // Skip origin check for displaced pieces (removed from origin by an enemy
        // landing there while this piece was in-flight — not a capture).
        Optional<Piece> originOccupant = board.pieceAt(motion.getFrom());
        boolean displaced = displacedPieces.remove(motion.getPiece());
        if (!displaced) {
            if (originOccupant.isEmpty() || originOccupant.get() != motion.getPiece()) {
                return; // piece was captured or otherwise gone — motion is moot
            }
        }

        Optional<Piece> destinationOccupant = board.pieceAt(motion.getTo());

        // In-transit ghost: if the piece at the destination has already departed
        // (its own outgoing motion is still pending), treat the square as empty —
        // the departed piece continues to its own destination unaffected.
        if (destinationOccupant.isPresent()) {
            Piece occupant = destinationOccupant.get();
            for (Motion m : pendingMotions) {
                if (!m.isDodge() && m.getPiece() == occupant && m.getFrom().equals(motion.getTo())) {
                    // Mark occupant as displaced so its own motion can still land
                    displacedPieces.add(occupant);
                    board.removePiece(motion.getTo());
                    destinationOccupant = Optional.empty();
                    break;
                }
            }
        }

        if (destinationOccupant.isPresent() && isProtected(motion.getTo(), clock)) {
            // Defender jumped to safety — attacker crashes
            board.removePiece(motion.getFrom());
            return;
        }

        // Check if the destination piece has a pending Dodge motion countering THIS motion.
        // If so: remove the defender from the board (without capture) so the attacker can
        // occupy the square, but keep the Piece instance alive in the pending dodge motion.
        if (destinationOccupant.isPresent()) {
            Piece defender = destinationOccupant.get();
            boolean defenderIsDodging = false;
            for (Motion m : pendingMotions) {
                if (m.isDodge() && m.getPiece() == defender && m.getDodgeThreatMotion() == motion) {
                    defenderIsDodging = true;
                    break;
                }
            }
            if (defenderIsDodging) {
                // Remove defender silently (no capture event) — it will land back via its own dodge motion
                board.removePiece(motion.getTo());
                destinationOccupant = Optional.empty(); // attacker lands on now-empty square
            }
        }

        Piece capturedPiece = destinationOccupant.orElse(null);
        if (capturedPiece != null) {
            board.removePiece(motion.getTo());
        }
        if (displaced) {
            // Piece was displaced from its origin — place it directly at destination
            try {
                board.addPiece(motion.getTo(), motion.getPiece());
            } catch (Board.OccupiedCellException e) {
                throw new IllegalStateException("Displaced piece landing target unexpectedly occupied", e);
            }
        } else {
            board.movePiece(motion.getFrom(), motion.getTo());
        }
        boolean promoted = promoteIfEligible(board, motion.getTo());

        // Reset cooldownStart to the landing time so the rest-chain fraction
        // starts at 0% from the moment the piece lands, not from when it departed.
        cooldownStart.put(motion.getPiece(), motion.getDueTime());

        arrivals.add(new ArrivalEvents.ArrivalEvent(
            motion.getPiece(), motion.getFrom(), motion.getTo(), capturedPiece, false, promoted, false));
    }

    private boolean isProtected(Position position, long clock) {
        for (Protection p : pendingProtections) {
            if (p.position.equals(position) && p.dueTime >= clock) return true;
        }
        return false;
    }

    /**
     * Promotes a pawn that has reached the far rank to a Queen of the same color.
     * White pawns promote at row 0; black pawns promote at row {@code height - 1}.
     * {@link Piece} is immutable, so promotion replaces the piece on the board.
     *
     * @return {@code true} if a promotion occurred
     */
    private boolean promoteIfEligible(Board board, Position at) throws Board.OutOfBoundsException {
        Optional<Piece> occupant = board.pieceAt(at);
        if (occupant.isEmpty() || !"Pawn".equals(occupant.get().getKind())) return false;

        Piece pawn = occupant.get();
        boolean reachedFarEdge =
            ("white".equals(pawn.getColor()) && at.getRow() == 0)
            || ("black".equals(pawn.getColor()) && at.getRow() == board.getHeight() - 1);
        if (!reachedFarEdge) return false;

        board.removePiece(at);
        try {
            board.addPiece(at, new Piece("Queen", pawn.getColor()));
        } catch (Board.OccupiedCellException e) {
            throw new IllegalStateException("Promotion target unexpectedly occupied", e);
        }
        return true;
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
            private final boolean  jump;
            private final boolean  promoted;
            private final boolean  dodge;
            private final boolean  scream;

            public ArrivalEvent(Piece piece, Position source, Position destination,
                                Piece capturedPiece, boolean jump, boolean promoted,
                                boolean dodge, boolean scream) {
                this.piece         = piece;
                this.source        = source;
                this.destination   = destination;
                this.capturedPiece = capturedPiece;
                this.jump          = jump;
                this.promoted      = promoted;
                this.dodge         = dodge;
                this.scream        = scream;
            }

            /** Backward-compatible constructor (scream defaults to false). */
            public ArrivalEvent(Piece piece, Position source, Position destination,
                                Piece capturedPiece, boolean jump, boolean promoted, boolean dodge) {
                this(piece, source, destination, capturedPiece, jump, promoted, dodge, false);
            }

            /** Backward-compatible constructor (promoted and dodge default to false). */
            public ArrivalEvent(Piece piece, Position source, Position destination,
                                Piece capturedPiece, boolean jump, boolean promoted) {
                this(piece, source, destination, capturedPiece, jump, promoted, false, false);
            }

            /** Backward-compatible constructor (promoted, dodge, scream default to false). */
            public ArrivalEvent(Piece piece, Position source, Position destination,
                                Piece capturedPiece, boolean jump) {
                this(piece, source, destination, capturedPiece, jump, false, false, false);
            }

            public Piece    piece()         { return piece; }
            public Position source()        { return source; }
            public Position destination()   { return destination; }
            public Piece    capturedPiece() { return capturedPiece; }
            /** @return {@code true} if this arrival was from a jump-type motion. */
            public boolean  isJump()        { return jump; }
            /** @return {@code true} if this arrival triggered a pawn promotion. */
            public boolean  isPromoted()    { return promoted; }
            /** @return {@code true} if this was a Dodge motion resolution. */
            public boolean  isDodge()       { return dodge; }
            /** @return {@code true} if this was a Scream capture (no movement). */
            public boolean  isScream()      { return scream; }

            @Override
            public String toString() {
                return "ArrivalEvent(piece=" + piece + ", source=" + source
                    + ", destination=" + destination + ", capturedPiece=" + capturedPiece
                    + ", jump=" + jump + ", promoted=" + promoted
                    + ", dodge=" + dodge + ", scream=" + scream + ")";
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
