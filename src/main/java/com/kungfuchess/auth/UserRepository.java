package com.kungfuchess.auth;

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
 * Repository for user authentication and ELO rating management.
 *
 * <p>Uses SQLite to store user credentials with SHA-256 hashed passwords
 * and per-user random salts. Supports user registration, password verification,
 * and ELO rating updates.</p>
 */
public class UserRepository {

    private static final String DEFAULT_DB_PATH = "users.db";
    private static final int DEFAULT_ELO = 1200;
    private static final int ELO_K_FACTOR = 32;
    
    private final String dbPath;

    /**
     * Creates a UserRepository with the default database path (users.db).
     */
    public UserRepository() {
        this(DEFAULT_DB_PATH);
    }

    /**
     * Creates a UserRepository with a custom database path.
     *
     * @param dbPath path to the SQLite database file
     */
    public UserRepository(String dbPath) {
        this.dbPath = dbPath;
        initializeDatabase();
    }

    /**
     * Initializes the database and creates the users table if it doesn't exist.
     */
    private void initializeDatabase() {
        String createTableSQL = """
            CREATE TABLE IF NOT EXISTS users (
                username TEXT PRIMARY KEY,
                password_hash TEXT NOT NULL,
                salt TEXT NOT NULL,
                elo INTEGER DEFAULT 1200
            )
            """;

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSQL);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database", e);
        }
    }

    /**
     * Gets a connection to the SQLite database.
     */
    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    /**
     * Generates a random salt for password hashing.
     *
     * @return Base64-encoded random salt
     */
    private String generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /**
     * Hashes a password with the given salt using SHA-256.
     *
     * @param password the plaintext password
     * @param salt the Base64-encoded salt
     * @return the Base64-encoded hash of password + salt
     */
    private String hashPassword(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String saltedPassword = password + salt;
            byte[] hash = digest.digest(saltedPassword.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Registers a new user with the given username and password.
     *
     * <p>Generates a random salt, hashes the password, and stores the user
     * with a default ELO rating of 1200.</p>
     *
     * @param username the username
     * @param password the plaintext password
     * @return true if registration succeeded, false if username already exists
     * @throws RuntimeException if a database error occurs
     */
    public boolean registerUser(String username, String password) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username cannot be empty");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Password cannot be empty");
        }

        String salt = generateSalt();
        String passwordHash = hashPassword(password, salt);

        String insertSQL = "INSERT INTO users (username, password_hash, salt, elo) VALUES (?, ?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {
            
            pstmt.setString(1, username);
            pstmt.setString(2, passwordHash);
            pstmt.setString(3, salt);
            pstmt.setInt(4, DEFAULT_ELO);
            
            pstmt.executeUpdate();
            return true;

        } catch (SQLException e) {
            // Check if it's a unique constraint violation (username already exists)
            if (e.getMessage().contains("UNIQUE constraint failed") || 
                e.getMessage().contains("PRIMARY KEY")) {
                return false;
            }
            throw new RuntimeException("Failed to register user", e);
        }
    }

    /**
     * Verifies a user's password.
     *
     * @param username the username
     * @param password the plaintext password to verify
     * @return true if the password is correct, false otherwise
     * @throws RuntimeException if a database error occurs
     */
    public boolean verifyPassword(String username, String password) {
        if (username == null || password == null) {
            return false;
        }

        String selectSQL = "SELECT password_hash, salt FROM users WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(selectSQL)) {
            
            pstmt.setString(1, username);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) {
                    // User doesn't exist
                    return false;
                }

                String storedHash = rs.getString("password_hash");
                String salt = rs.getString("salt");
                
                // Hash the provided password with the stored salt
                String computedHash = hashPassword(password, salt);
                
                // Compare hashes
                return storedHash.equals(computedHash);
            }

        } catch (SQLException e) {
            throw new RuntimeException("Failed to verify password", e);
        }
    }

    /**
     * Checks if a user exists in the database.
     *
     * @param username the username to check
     * @return true if the user exists, false otherwise
     */
    public boolean userExists(String username) {
        if (username == null) {
            return false;
        }

        String selectSQL = "SELECT 1 FROM users WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(selectSQL)) {
            
            pstmt.setString(1, username);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }

        } catch (SQLException e) {
            throw new RuntimeException("Failed to check user existence", e);
        }
    }

    /**
     * Gets a user's current ELO rating.
     *
     * @param username the username
     * @return the user's ELO rating, or -1 if the user doesn't exist
     */
    public int getElo(String username) {
        if (username == null) {
            return -1;
        }

        String selectSQL = "SELECT elo FROM users WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(selectSQL)) {
            
            pstmt.setString(1, username);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("elo");
                }
                return -1;
            }

        } catch (SQLException e) {
            throw new RuntimeException("Failed to get ELO rating", e);
        }
    }

    /**
     * Updates ELO ratings for both winner and loser using the standard ELO formula.
     *
     * <p>Formula: newRating = oldRating + K * (actualScore - expectedScore)
     * where K = 32, actualScore is 1 for winner and 0 for loser,
     * and expectedScore = 1 / (1 + 10^((opponentRating - playerRating) / 400))</p>
     *
     * @param winnerUsername the username of the winner
     * @param loserUsername the username of the loser
     * @throws RuntimeException if either user doesn't exist or a database error occurs
     */
    public void updateElo(String winnerUsername, String loserUsername) {
        if (winnerUsername == null || loserUsername == null) {
            throw new IllegalArgumentException("Usernames cannot be null");
        }

        int winnerElo = getElo(winnerUsername);
        int loserElo = getElo(loserUsername);

        if (winnerElo == -1) {
            throw new IllegalArgumentException("Winner user does not exist: " + winnerUsername);
        }
        if (loserElo == -1) {
            throw new IllegalArgumentException("Loser user does not exist: " + loserUsername);
        }

        // Calculate expected scores
        double winnerExpected = 1.0 / (1.0 + Math.pow(10.0, (loserElo - winnerElo) / 400.0));
        double loserExpected = 1.0 / (1.0 + Math.pow(10.0, (winnerElo - loserElo) / 400.0));

        // Calculate new ratings
        // Winner gets actual score of 1, loser gets 0
        int newWinnerElo = (int) Math.round(winnerElo + ELO_K_FACTOR * (1.0 - winnerExpected));
        int newLoserElo = (int) Math.round(loserElo + ELO_K_FACTOR * (0.0 - loserExpected));

        // Update both ratings in the database
        String updateSQL = "UPDATE users SET elo = ? WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(updateSQL)) {
            
            // Update winner
            pstmt.setInt(1, newWinnerElo);
            pstmt.setString(2, winnerUsername);
            pstmt.executeUpdate();

            // Update loser
            pstmt.setInt(1, newLoserElo);
            pstmt.setString(2, loserUsername);
            pstmt.executeUpdate();

        } catch (SQLException e) {
            throw new RuntimeException("Failed to update ELO ratings", e);
        }
    }

    /**
     * Deletes a user from the database (for testing purposes).
     *
     * @param username the username to delete
     * @return true if the user was deleted, false if the user didn't exist
     */
    public boolean deleteUser(String username) {
        if (username == null) {
            return false;
        }

        String deleteSQL = "DELETE FROM users WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(deleteSQL)) {
            
            pstmt.setString(1, username);
            int rowsAffected = pstmt.executeUpdate();
            return rowsAffected > 0;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete user", e);
        }
    }
}
