package com.kungfuchess.cloud.infra;

import com.kungfuchess.engine.GameEngine;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Durable match history: one row per finished game plus its full move log, written
 * once by the Game Server Shard at game end (Server_Design.md — no separate Rating
 * service, the shard that owns the game is the only writer). The API Gateway reads
 * from here for the "history" side of its login/rooms/history responsibility.
 */
public class GameHistoryRepository {

    private final String jdbcUrl;
    private final String user;
    private final String password;

    public GameHistoryRepository(String host, int port, String db, String user, String password) {
        this.jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + db;
        this.user = user;
        this.password = password;
        initializeSchema();
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    private void initializeSchema() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS games (
                    id BIGSERIAL PRIMARY KEY,
                    room_id TEXT NOT NULL,
                    white_username TEXT NOT NULL,
                    black_username TEXT NOT NULL,
                    winner_username TEXT,
                    white_elo_after INTEGER,
                    black_elo_after INTEGER,
                    started_at TIMESTAMPTZ NOT NULL,
                    ended_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS moves (
                    id BIGSERIAL PRIMARY KEY,
                    game_id BIGINT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
                    seq INTEGER NOT NULL,
                    color TEXT,
                    piece_kind TEXT,
                    from_row INTEGER,
                    from_col INTEGER,
                    to_row INTEGER,
                    to_col INTEGER,
                    captured_kind TEXT,
                    is_jump BOOLEAN,
                    is_promoted BOOLEAN,
                    is_dodge BOOLEAN,
                    is_scream BOOLEAN,
                    move_timestamp TEXT
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_moves_game_id ON moves(game_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_games_players ON games(white_username, black_username)");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize game-history schema", e);
        }
    }

    /** Records a finished game and its full move log in one transaction. */
    public void recordGame(String roomId, String whiteUsername, String blackUsername, String winnerUsername,
                            int whiteEloAfter, int blackEloAfter, long startedAtMs,
                            List<GameEngine.GameSnapshot.MoveLogEntry> moveLog) {
        String insertGame = """
            INSERT INTO games (room_id, white_username, black_username, winner_username,
                                white_elo_after, black_elo_after, started_at, ended_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, now())
            RETURNING id
            """;
        String insertMove = """
            INSERT INTO moves (game_id, seq, color, piece_kind, from_row, from_col, to_row, to_col,
                                captured_kind, is_jump, is_promoted, is_dodge, is_scream, move_timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            long gameId;
            try (PreparedStatement ps = conn.prepareStatement(insertGame)) {
                ps.setString(1, roomId);
                ps.setString(2, whiteUsername);
                ps.setString(3, blackUsername);
                ps.setString(4, winnerUsername);
                ps.setInt(5, whiteEloAfter);
                ps.setInt(6, blackEloAfter);
                ps.setTimestamp(7, new Timestamp(startedAtMs));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    gameId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(insertMove)) {
                int seq = 0;
                for (GameEngine.GameSnapshot.MoveLogEntry e : moveLog) {
                    ps.setLong(1, gameId);
                    ps.setInt(2, seq++);
                    ps.setString(3, e.color());
                    ps.setString(4, e.pieceKind());
                    if (e.from() != null) {
                        ps.setInt(5, e.from().getRow());
                        ps.setInt(6, e.from().getCol());
                    } else {
                        ps.setNull(5, java.sql.Types.INTEGER);
                        ps.setNull(6, java.sql.Types.INTEGER);
                    }
                    if (e.to() != null) {
                        ps.setInt(7, e.to().getRow());
                        ps.setInt(8, e.to().getCol());
                    } else {
                        ps.setNull(7, java.sql.Types.INTEGER);
                        ps.setNull(8, java.sql.Types.INTEGER);
                    }
                    ps.setString(9, e.capturedKind());
                    ps.setBoolean(10, e.isJump());
                    ps.setBoolean(11, e.isPromoted());
                    ps.setBoolean(12, e.isDodge());
                    ps.setBoolean(13, e.isScream());
                    ps.setString(14, e.timestamp());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record game history for room " + roomId, e);
        }
    }

    public record GameSummary(long id, String roomId, String whiteUsername, String blackUsername,
                               String winnerUsername, int whiteEloAfter, int blackEloAfter, String endedAt) {
    }

    /** Most recent games a user played, newest first — backs the API Gateway's /history endpoint. */
    public List<GameSummary> recentGamesForUser(String username, int limit) {
        String sql = """
            SELECT id, room_id, white_username, black_username, winner_username,
                   white_elo_after, black_elo_after, ended_at
            FROM games
            WHERE white_username = ? OR black_username = ?
            ORDER BY ended_at DESC
            LIMIT ?
            """;
        List<GameSummary> results = new ArrayList<>();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, username);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new GameSummary(
                            rs.getLong("id"), rs.getString("room_id"),
                            rs.getString("white_username"), rs.getString("black_username"),
                            rs.getString("winner_username"), rs.getInt("white_elo_after"),
                            rs.getInt("black_elo_after"), rs.getTimestamp("ended_at").toString()));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read game history for " + username, e);
        }
        return results;
    }
}
