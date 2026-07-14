package com.kungfuchess.realtime;
import com.kungfuchess.model.Board;

import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Owns real-time movement: simulated elapsed time, pending in-flight {@link Motion
 * motions}, temporary jump "protections", and which pieces have already made their
 * first move (for pawn double-stepping).
 *
 * <p>Movement is isolated here, not mixed into {@code Board}, the renderer, or the
 * controller. {@code Board} represents logical occupancy only; this class owns the
 * collection of active motions and applies them to the board only on arrival — see
 * {@link #advanceTime}.</p>
 *
 * <p>The common route enforces a single active motion at a time: {@link
 * #hasActiveMotion()} is {@code true} whenever any motion — of either color — is still
 * in flight, and {@code GameEngine} must reject a new move request with reason
 * {@code "motion_in_progress"} while that holds.</p>
 */
public class RealTimeArbiter {

    /** Travel time, in milliseconds, per board cell of (Chebyshev) distance. */
    public static final long CELL_DURATION_MS = 1000;

    /** How long a jumped piece remains protected (immune to capture) before landing. */
    public static final long JUMP_DURATION_MS = 1000;

    private long clock = 0;

    private final List<Motion> pendingMotions = new ArrayList<>();
    private final List<Protection> pendingProtections = new ArrayList<>();
    private final Set<Piece> movedPieces = Collections.newSetFromMap(new IdentityHashMap<>());

    /** A temporary shield over a cell, protecting whatever piece jumped there. */
    private static final class Protection {
        final Position position;
        final long dueTime;

        Protection(Position position, long dueTime) {
            this.position = position;
            this.dueTime = dueTime;
        }
    }

    // -------------------------------------------------------------------------
    // Simulated time
    // -------------------------------------------------------------------------

    /** @return the arbiter's current simulated clock, in milliseconds. */
    public long getClock() {
        return clock;
    }

    // -------------------------------------------------------------------------
    // First-move tracking (for pawn double-stepping)
    // -------------------------------------------------------------------------

    /** @return {@code true} if this exact piece instance has already moved once. */
    public boolean hasMoved(Piece piece) {
        return movedPieces.contains(piece);
    }

    /** Marks this piece instance as having moved (spends its double-step eligibility). */
    public void markMoved(Piece piece) {
        movedPieces.add(piece);
    }

    // -------------------------------------------------------------------------
    // Common-route single-active-motion policy
    // -------------------------------------------------------------------------

    /** @return {@code true} if any motion — of either color — is currently in flight. */
    public boolean hasActiveMotion() {
        return !pendingMotions.isEmpty();
    }

    /**
     * @param color the color attempting to start a new move
     * @return {@code true} if a piece of the opposite color already has an unresolved
     * in-flight motion (used only by the extra-route simultaneous-movement variant;
     * the common route instead calls {@link #hasActiveMotion()}, which is stricter).
     */
    public boolean isBlockedByOppositeColor(String color) {
        for (Motion motion : pendingMotions) {
            if (!motion.getColor().equals(color)) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Registering new motions
    // -------------------------------------------------------------------------

    /**
     * Starts a validated motion: the caller (GameEngine) is responsible for having
     * already confirmed the move is legal and that no other motion is currently
     * active. Duration is derived from travel distance at a fixed speed.
     *
     * @param piece the piece in flight
     * @param from  origin cell
     * @param to    destination cell
     */
    public void startMotion(Piece piece, Position from, Position to) {
        long dueTime = clock + travelTime(from, to);
        pendingMotions.add(new Motion(piece, from, to, dueTime));
        markMoved(piece);
    }

    /** Low-level escape hatch used by extra-route variants that build their own Motion. */
    public void addMotion(Motion motion) {
        pendingMotions.add(motion);
    }

    public void addProtection(Position position, long dueTime) {
        pendingProtections.add(new Protection(position, dueTime));
    }

    /** @return travel time in ms for a straight/diagonal move of this many cells. */
    public static long travelTime(Position from, Position to) {
        int dr = Math.abs(to.getRow() - from.getRow());
        int dc = Math.abs(to.getCol() - from.getCol());
        return Math.max(dr, dc) * CELL_DURATION_MS;
    }

    // -------------------------------------------------------------------------
    // Resolution
    // -------------------------------------------------------------------------

    /**
     * Advances the simulated clock by {@code ms} and resolves every pending motion and
     * jump-landing whose time has now come.
     *
     * <p>Tests never sleep in real time; this is the only way simulated time moves
     * forward, and the only trigger for arrival resolution.</p>
     *
     * @param ms milliseconds to advance
     * @param board the board to mutate on arrival
     * @return every arrival that occurred as a result of this advance (possibly empty)
     */
    public ArrivalEvents advanceTime(long ms, Board board) {
        clock += ms;

        List<ArrivalEvents.ArrivalEvent> arrivals = new ArrayList<>();

        Iterator<Motion> it = pendingMotions.iterator();
        while (it.hasNext()) {
            Motion motion = it.next();
            if (motion.getDueTime() > clock) {
                continue;
            }
            it.remove();
            try {
                resolveMotion(board, motion, arrivals);
            } catch (Board.OutOfBoundsException e) {
                // Defensive: a malformed motion simply fizzles rather than crashing the script.
            }
        }

        pendingProtections.removeIf(protection -> protection.dueTime <= clock);

        return new ArrivalEvents(arrivals);
    }

    private void resolveMotion(Board board, Motion motion, List<ArrivalEvents.ArrivalEvent> arrivals)
            throws Board.OutOfBoundsException {
        Optional<Piece> originOccupant = board.pieceAt(motion.getFrom());
        if (originOccupant.isEmpty() || originOccupant.get() != motion.getPiece()) {
            // The piece was captured, or otherwise no longer there — this motion is moot.
            return;
        }

        Optional<Piece> destinationOccupant = board.pieceAt(motion.getTo());

        if (destinationOccupant.isPresent() && isProtected(motion.getTo(), clock)) {
            // The defender jumped to safety before we arrived: we crash instead of landing.
            board.removePiece(motion.getFrom());
            return;
        }

        Piece capturedPiece = destinationOccupant.orElse(null);

        board.movePiece(motion.getFrom(), motion.getTo());
        promoteIfEligible(board, motion.getTo());

        arrivals.add(new ArrivalEvents.ArrivalEvent(motion.getPiece(), motion.getFrom(), motion.getTo(), capturedPiece));
    }

    private boolean isProtected(Position position, long clock) {
        for (Protection protection : pendingProtections) {
            if (protection.position.equals(position) && protection.dueTime >= clock) {
                return true;
            }
        }
        return false;
    }

    private void promoteIfEligible(Board board, Position at) throws Board.OutOfBoundsException {
        Optional<Piece> occupant = board.pieceAt(at);
        if (occupant.isEmpty() || !"Pawn".equals(occupant.get().getKind())) {
            return;
        }

        Piece pawn = occupant.get();
        boolean reachedFarEdge = ("white".equals(pawn.getColor()) && at.getRow() == 0)
            || ("black".equals(pawn.getColor()) && at.getRow() == board.getHeight() - 1);
        if (!reachedFarEdge) {
            return;
        }

        board.removePiece(at);
        try {
            board.addPiece(at, new Piece("Queen", pawn.getColor()));
        } catch (Board.OccupiedCellException e) {
            // Unreachable: the cell was just vacated by removePiece above.
            throw new IllegalStateException("Promotion target unexpectedly occupied", e);
        }
    }

    /**
     * The batch of motions that landed during one {@link #advanceTime} call.
     *
     * <p>{@code GameEngine} inspects these to detect a king capture and set
     * {@code game_over} — {@code RealTimeArbiter} itself does not know about
     * game-over semantics.</p>
     *
     * <p>Nested here (rather than its own file) to match the project's exact package
     * structure, in which the {@code realtime} package holds exactly {@code Motion}
     * and {@code RealTimeArbiter}.</p>
     */
    public static final class ArrivalEvents {

        /** One piece's arrival: where it came from, where it landed, and what (if anything) it captured. */
        public static final class ArrivalEvent {
            private final Piece piece;
            private final Position source;
            private final Position destination;
            private final Piece capturedPiece;

            public ArrivalEvent(Piece piece, Position source, Position destination, Piece capturedPiece) {
                this.piece = piece;
                this.source = source;
                this.destination = destination;
                this.capturedPiece = capturedPiece;
            }

            public Piece piece() { return piece; }
            public Position source() { return source; }
            public Position destination() { return destination; }
            public Piece capturedPiece() { return capturedPiece; }

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

        public List<ArrivalEvent> arrivals() {
            return arrivals;
        }

        public static ArrivalEvents empty() {
            return new ArrivalEvents(Collections.emptyList());
        }
    }
}
