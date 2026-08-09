package com.kungfuchess.cloud.infra;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;

/**
 * PostgreSQL-backed equivalent of {@code com.kungfuchess.auth.UserRepository}, for
 * the scaled architecture (Server_Design.md §2.1: SQLite doesn't scale to multiple
 * writer processes across services, PostgreSQL does). Same interface/behavior —
 * same salted-SHA-256 scheme, same ELO formula (K=32) — just a different store.
 */
public class PostgresUserRepository {

    private static final int DEFAULT_ELO = 1200;
    private static final int ELO_K_FACTOR = 32;

    private final String jdbcUrl;
    private final String user;
    private final String password;

    public PostgresUserRepository(String host, int port, String db, String user, String password) {
        this.jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + db;
        this.user = user;
        this.password = password;
        initializeDatabase();
    }

    private void initializeDatabase() {
        String createTableSQL = """
            CREATE TABLE IF NOT EXISTS users (
                username TEXT PRIMARY KEY,
                password_hash TEXT NOT NULL,
                salt TEXT NOT NULL,
                elo INTEGER NOT NULL DEFAULT 1200
            )
            """;
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSQL);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize PostgreSQL schema", e);
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    private String generateSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    private String hashPassword(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((password + salt).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    public boolean registerUser(String username, String password) {
        String salt = generateSalt();
        String hash = hashPassword(password, salt);
        String sql = "INSERT INTO users (username, password_hash, salt, elo) VALUES (?, ?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, hash);
            ps.setString(3, salt);
            ps.setInt(4, DEFAULT_ELO);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) { // unique_violation
                return false;
            }
            throw new RuntimeException("Failed to register user", e);
        }
    }

    public boolean verifyPassword(String username, String password) {
        String sql = "SELECT password_hash, salt FROM users WHERE username = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                String storedHash = rs.getString("password_hash");
                String salt = rs.getString("salt");
                return storedHash.equals(hashPassword(password, salt));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to verify password", e);
        }
    }

    public boolean userExists(String username) {
        String sql = "SELECT 1 FROM users WHERE username = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check user existence", e);
        }
    }

    public int getElo(String username) {
        String sql = "SELECT elo FROM users WHERE username = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("elo") : -1;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get ELO rating", e);
        }
    }

    /** Standard ELO update (K=32) — same formula as the Phase 1 SQLite repository. */
    public void updateElo(String winnerUsername, String loserUsername) {
        int winnerElo = getElo(winnerUsername);
        int loserElo = getElo(loserUsername);
        if (winnerElo == -1) throw new IllegalArgumentException("Winner user does not exist: " + winnerUsername);
        if (loserElo == -1) throw new IllegalArgumentException("Loser user does not exist: " + loserUsername);

        double winnerExpected = 1.0 / (1.0 + Math.pow(10.0, (loserElo - winnerElo) / 400.0));
        double loserExpected = 1.0 / (1.0 + Math.pow(10.0, (winnerElo - loserElo) / 400.0));
        int newWinnerElo = (int) Math.round(winnerElo + ELO_K_FACTOR * (1.0 - winnerExpected));
        int newLoserElo = (int) Math.round(loserElo + ELO_K_FACTOR * (0.0 - loserExpected));

        String sql = "UPDATE users SET elo = ? WHERE username = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, newWinnerElo);
            ps.setString(2, winnerUsername);
            ps.executeUpdate();
            ps.setInt(1, newLoserElo);
            ps.setString(2, loserUsername);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update ELO ratings", e);
        }
    }
}
