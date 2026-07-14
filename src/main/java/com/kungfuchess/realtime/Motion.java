package com.kungfuchess.realtime;

import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;

/**
 * An in-flight move: a piece travelling from one cell to another, which only takes
 * effect on the board once the game clock reaches {@link #getDueTime()}.
 *
 * <p>Immutable record used by {@link RealTimeArbiter} to track pending motions between
 * the moment a move is commanded (click-to-click) and the moment it actually lands.</p>
 */
public final class Motion {

    private final Piece piece;
    private final Position from;
    private final Position to;
    private final long dueTime;

    /**
     * @param piece   the piece in flight (identity is used to detect if it was
     *                captured/removed before this motion resolves)
     * @param from    origin cell
     * @param to      destination cell
     * @param dueTime absolute game-clock time (ms) at which this motion resolves
     */
    public Motion(Piece piece, Position from, Position to, long dueTime) {
        this.piece = piece;
        this.from = from;
        this.to = to;
        this.dueTime = dueTime;
    }

    public Piece getPiece() { return piece; }
    public Position getFrom() { return from; }
    public Position getTo() { return to; }
    public long getDueTime() { return dueTime; }

    /** @return the color of the travelling piece, e.g. {@code "white"}. */
    public String getColor() { return piece.getColor(); }
}
