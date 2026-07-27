package com.kungfuchess.realtime;

import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;

/**
 * An in-flight move: a piece travelling from one cell to another, which only takes
 * effect on the board once the game clock reaches {@link #getDueTime()}.
 *
 * <p>Immutable record used by {@link RealTimeArbiter} to track pending motions between
 * the moment a move is commanded (click-to-click) and the moment it actually lands.</p>
 *
 * <p>{@link #isJump()} is {@code true} for pieces that leap over intervening squares
 * (currently Knights). This flag is exclusively the Knight geometric-jump animation
 * flag and is completely unrelated to the Dodge mechanic.</p>
 *
 * <p>{@link #isDodge()} is {@code true} for a self-targeting Dodge motion (from==to).
 * The {@link #getDodgeThreatMotion()} reference identifies the specific attacker motion
 * this dodge was issued against, enabling the narrow destination-reservation exception.</p>
 */
public final class Motion {

    private final Piece    piece;
    private final Position from;
    private final Position to;
    private final long     startTime;
    private final long     dueTime;
    private final boolean  jump;
    private final boolean  dodge;
    /** The specific attacker motion this dodge counters; null for non-dodge motions. */
    private final Motion   dodgeThreatMotion;

    /**
     * Full constructor.
     *
     * @param piece            the piece in flight
     * @param from             origin cell
     * @param to               destination cell (equals from for dodge)
     * @param startTime        absolute game-clock time (ms) when this motion was started
     * @param dueTime          absolute game-clock time (ms) at which this motion resolves
     * @param jump             {@code true} if this is a jump-type motion (Knight only)
     * @param dodge            {@code true} if this is a self-targeting Dodge motion
     * @param dodgeThreatMotion the specific threat motion this dodge counters, or null
     */
    public Motion(Piece piece, Position from, Position to,
                  long startTime, long dueTime, boolean jump,
                  boolean dodge, Motion dodgeThreatMotion) {
        this.piece             = piece;
        this.from              = from;
        this.to                = to;
        this.startTime         = startTime;
        this.dueTime           = dueTime;
        this.jump              = jump;
        this.dodge             = dodge;
        this.dodgeThreatMotion = dodgeThreatMotion;
    }

    /**
     * Backward-compatible constructor for jump/non-jump motions (no dodge).
     */
    public Motion(Piece piece, Position from, Position to,
                  long startTime, long dueTime, boolean jump) {
        this(piece, from, to, startTime, dueTime, jump, false, null);
    }

    /**
     * Backward-compatible constructor for non-jump, non-dodge motions.
     */
    public Motion(Piece piece, Position from, Position to, long startTime, long dueTime) {
        this(piece, from, to, startTime, dueTime, false, false, null);
    }

    public Piece    getPiece()     { return piece; }
    public Position getFrom()      { return from; }
    public Position getTo()        { return to; }
    /** @return absolute game-clock time (ms) when this motion started. */
    public long     getStartTime() { return startTime; }
    public long     getDueTime()   { return dueTime; }
    /** @return {@code true} if this is a jump-type motion (Knight only — unrelated to Dodge). */
    public boolean  isJump()       { return jump; }
    /** @return {@code true} if this is a self-targeting Dodge motion (from==to). */
    public boolean  isDodge()      { return dodge; }
    /**
     * @return the specific attacker motion this dodge was issued against, or {@code null}
     *         for non-dodge motions. Used to scope the destination-reservation exception
     *         narrowly to this exact attacker+defender pair.
     */
    public Motion   getDodgeThreatMotion() { return dodgeThreatMotion; }

    /** @return the color of the travelling piece, e.g. {@code "white"}. */
    public String getColor() { return piece.getColor(); }
}
