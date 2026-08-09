package com.kungfuchess.server;

import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.io.BoardParser;
import com.kungfuchess.model.Board;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A single isolated game: its own {@link GameEngine} (and therefore its own board,
 * clock, and event bus), its own White/Black players, and any number of spectators.
 *
 * <p>This is what {@link ChessWebSocketServer} was missing before — every {@code Room}
 * gets a fresh board, so games no longer bleed into each other.</p>
 */
public class Room {

    private static final String STARTING_BOARD =
        "bR bN bB bQ bK bB bN bR\n" +
        "bP bP bP bP bP bP bP bP\n" +
        " .  .  .  .  .  .  .  .\n" +
        " .  .  .  .  .  .  .  .\n" +
        " .  .  .  .  .  .  .  .\n" +
        " .  .  .  .  .  .  .  .\n" +
        "wP wP wP wP wP wP wP wP\n" +
        "wR wN wB wQ wK wB wN wR";

    private final String roomId;
    private final GameEngine engine;
    private final List<ServerSession> spectators = new CopyOnWriteArrayList<>();
    private ServerSession white;
    private ServerSession black;
    private boolean started = false;

    public Room(String roomId) {
        this.roomId = roomId;
        try {
            Board board = new BoardParser.TextParser().parse(STARTING_BOARD);
            this.engine = new GameEngine().setBoard(board);
        } catch (BoardParser.BoardParseException e) {
            throw new IllegalStateException("Failed to parse starting board", e);
        }
    }

    public String getRoomId() {
        return roomId;
    }

    public GameEngine getEngine() {
        return engine;
    }

    public ServerSession getWhite() {
        return white;
    }

    public void setWhite(ServerSession white) {
        this.white = white;
    }

    public ServerSession getBlack() {
        return black;
    }

    public void setBlack(ServerSession black) {
        this.black = black;
    }

    public List<ServerSession> getSpectators() {
        return spectators;
    }

    public void addSpectator(ServerSession session) {
        spectators.add(session);
    }

    public boolean isStarted() {
        return started;
    }

    public void setStarted(boolean started) {
        this.started = started;
    }

    public String getWhiteName() {
        return white != null ? white.getUsername() : "Waiting...";
    }

    public String getBlackName() {
        return black != null ? black.getUsername() : "Waiting...";
    }

    /** All live participants (players + spectators) — used for room-scoped broadcast. */
    public List<ServerSession> getAllSessions() {
        List<ServerSession> all = new CopyOnWriteArrayList<>();
        if (white != null) all.add(white);
        if (black != null) all.add(black);
        all.addAll(spectators);
        return all;
    }

    /** @return the session's opponent within this room, or null (e.g. for a spectator). */
    public ServerSession opponentOf(ServerSession session) {
        if (session == white) return black;
        if (session == black) return white;
        return null;
    }

    public void removeSpectator(ServerSession session) {
        spectators.remove(session);
    }
}
