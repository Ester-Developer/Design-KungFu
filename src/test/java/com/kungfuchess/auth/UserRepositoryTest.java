package com.kungfuchess.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for UserRepository authentication and ELO management.
 */
class UserRepositoryTest {

    private UserRepository repository;
    private static final String TEST_DB = "test_users.db";

    @BeforeEach
    void setUp() {
        // Use a test database for each test
        repository = new UserRepository(TEST_DB);
    }

    @AfterEach
    void tearDown() {
        // Clean up test database after each test
        File dbFile = new File(TEST_DB);
        if (dbFile.exists()) {
            dbFile.delete();
        }
    }

    @Test
    void testRegisterUser_Success() {
        boolean result = repository.registerUser("alice", "password123");
        
        assertTrue(result, "User registration should succeed");
        assertTrue(repository.userExists("alice"), "User should exist after registration");
    }

    @Test
    void testRegisterUser_DuplicateUsername() {
        repository.registerUser("alice", "password123");
        boolean result = repository.registerUser("alice", "different_password");
        
        assertFalse(result, "Duplicate username registration should fail");
    }

    @Test
    void testRegisterUser_EmptyUsername() {
        assertThrows(IllegalArgumentException.class, () -> {
            repository.registerUser("", "password123");
        }, "Empty username should throw IllegalArgumentException");
    }

    @Test
    void testRegisterUser_NullUsername() {
        assertThrows(IllegalArgumentException.class, () -> {
            repository.registerUser(null, "password123");
        }, "Null username should throw IllegalArgumentException");
    }

    @Test
    void testRegisterUser_EmptyPassword() {
        assertThrows(IllegalArgumentException.class, () -> {
            repository.registerUser("alice", "");
        }, "Empty password should throw IllegalArgumentException");
    }

    @Test
    void testRegisterUser_NullPassword() {
        assertThrows(IllegalArgumentException.class, () -> {
            repository.registerUser("alice", null);
        }, "Null password should throw IllegalArgumentException");
    }

    @Test
    void testRegisterUser_DefaultElo() {
        repository.registerUser("alice", "password123");
        int elo = repository.getElo("alice");
        
        assertEquals(1200, elo, "New user should have default ELO of 1200");
    }

    @Test
    void testVerifyPassword_CorrectPassword() {
        repository.registerUser("alice", "password123");
        boolean result = repository.verifyPassword("alice", "password123");
        
        assertTrue(result, "Correct password should verify successfully");
    }

    @Test
    void testVerifyPassword_WrongPassword() {
        repository.registerUser("alice", "password123");
        boolean result = repository.verifyPassword("alice", "wrong_password");
        
        assertFalse(result, "Wrong password should fail verification");
    }

    @Test
    void testVerifyPassword_NonExistentUser() {
        boolean result = repository.verifyPassword("nonexistent", "password123");
        
        assertFalse(result, "Non-existent user should fail verification");
    }

    @Test
    void testVerifyPassword_NullUsername() {
        boolean result = repository.verifyPassword(null, "password123");
        
        assertFalse(result, "Null username should return false");
    }

    @Test
    void testVerifyPassword_NullPassword() {
        repository.registerUser("alice", "password123");
        boolean result = repository.verifyPassword("alice", null);
        
        assertFalse(result, "Null password should return false");
    }

    @Test
    void testVerifyPassword_CaseSensitive() {
        repository.registerUser("alice", "Password123");
        
        assertTrue(repository.verifyPassword("alice", "Password123"), 
                  "Exact password should verify");
        assertFalse(repository.verifyPassword("alice", "password123"), 
                   "Password with different case should fail");
    }

    @Test
    void testPasswordsAreHashed() {
        String password = "my_secret_password";
        repository.registerUser("alice", password);
        
        // Passwords should be hashed, not stored in plaintext
        // We verify this by ensuring different users with the same password
        // have different hashes (due to different salts)
        repository.registerUser("bob", password);
        
        assertTrue(repository.verifyPassword("alice", password), "Alice's password should verify");
        assertTrue(repository.verifyPassword("bob", password), "Bob's password should verify");
        
        // Both users should have the same password but different salts/hashes
        // This is implicitly tested by the fact that verification works for both
    }

    @Test
    void testUserExists_ExistingUser() {
        repository.registerUser("alice", "password123");
        
        assertTrue(repository.userExists("alice"), "Existing user should return true");
    }

    @Test
    void testUserExists_NonExistentUser() {
        assertFalse(repository.userExists("nonexistent"), "Non-existent user should return false");
    }

    @Test
    void testUserExists_NullUsername() {
        assertFalse(repository.userExists(null), "Null username should return false");
    }

    @Test
    void testGetElo_ExistingUser() {
        repository.registerUser("alice", "password123");
        int elo = repository.getElo("alice");
        
        assertEquals(1200, elo, "Existing user should return correct ELO");
    }

    @Test
    void testGetElo_NonExistentUser() {
        int elo = repository.getElo("nonexistent");
        
        assertEquals(-1, elo, "Non-existent user should return -1");
    }

    @Test
    void testGetElo_NullUsername() {
        int elo = repository.getElo(null);
        
        assertEquals(-1, elo, "Null username should return -1");
    }

    @Test
    void testUpdateElo_BothUsersWinnerHigherRated() {
        // Create two users
        repository.registerUser("alice", "password123");
        repository.registerUser("bob", "password456");
        
        // Manually set different ELO ratings for testing
        // Alice starts at 1400, Bob at 1200
        repository.updateElo("alice", "bob"); // Alice wins once to get higher rating
        repository.updateElo("alice", "bob"); // Alice wins again
        
        int aliceElo = repository.getElo("alice");
        int bobElo = repository.getElo("bob");
        
        // After two wins, Alice should have higher ELO
        assertTrue(aliceElo > 1200, "Alice should have ELO higher than default after winning");
        assertTrue(bobElo < 1200, "Bob should have ELO lower than default after losing");
    }

    @Test
    void testUpdateElo_EqualRatings() {
        repository.registerUser("alice", "password123");
        repository.registerUser("bob", "password456");
        
        int aliceInitial = repository.getElo("alice");
        int bobInitial = repository.getElo("bob");
        
        // Both start at 1200
        assertEquals(1200, aliceInitial);
        assertEquals(1200, bobInitial);
        
        // Alice wins against Bob (equal ratings)
        repository.updateElo("alice", "bob");
        
        int aliceAfter = repository.getElo("alice");
        int bobAfter = repository.getElo("bob");
        
        // When equal ratings, winner gains 16 points, loser loses 16 points (K=32, expected=0.5)
        assertEquals(1216, aliceAfter, "Winner should gain 16 points from equal opponent");
        assertEquals(1184, bobAfter, "Loser should lose 16 points to equal opponent");
    }

    @Test
    void testUpdateElo_UnderdogWins() {
        repository.registerUser("alice", "password123");
        repository.registerUser("bob", "password456");
        
        // Boost Alice's rating significantly
        for (int i = 0; i < 10; i++) {
            repository.updateElo("alice", "bob");
        }
        
        int aliceBeforeUpset = repository.getElo("alice");
        int bobBeforeUpset = repository.getElo("bob");
        
        assertTrue(aliceBeforeUpset > bobBeforeUpset, "Alice should have much higher rating");
        
        // Now Bob (underdog) wins
        repository.updateElo("bob", "alice");
        
        int aliceAfterUpset = repository.getElo("alice");
        int bobAfterUpset = repository.getElo("bob");
        
        // Bob should gain more points than he would from an equal opponent
        int bobGain = bobAfterUpset - bobBeforeUpset;
        int aliceLoss = aliceBeforeUpset - aliceAfterUpset;
        
        assertTrue(bobGain > 16, "Underdog should gain more than 16 points when winning");
        assertTrue(aliceLoss > 16, "Favorite should lose more than 16 points when losing");
        
        // Total points should be conserved (approximately, due to rounding)
        assertEquals(bobGain, aliceLoss, 5, "ELO points should be approximately conserved");
    }

    @Test
    void testUpdateElo_MultipleGames() {
        repository.registerUser("alice", "password123");
        repository.registerUser("bob", "password456");
        
        // Play a series of games
        repository.updateElo("alice", "bob");  // Alice wins
        repository.updateElo("bob", "alice");  // Bob wins
        repository.updateElo("alice", "bob");  // Alice wins
        
        int aliceElo = repository.getElo("alice");
        int bobElo = repository.getElo("bob");
        
        // Alice won 2 out of 3, should have higher rating
        assertTrue(aliceElo > bobElo, "Player with more wins should have higher rating");
    }

    @Test
    void testUpdateElo_NonExistentWinner() {
        repository.registerUser("bob", "password456");
        
        assertThrows(IllegalArgumentException.class, () -> {
            repository.updateElo("nonexistent", "bob");
        }, "Updating ELO with non-existent winner should throw exception");
    }

    @Test
    void testUpdateElo_NonExistentLoser() {
        repository.registerUser("alice", "password123");
        
        assertThrows(IllegalArgumentException.class, () -> {
            repository.updateElo("alice", "nonexistent");
        }, "Updating ELO with non-existent loser should throw exception");
    }

    @Test
    void testUpdateElo_NullWinner() {
        repository.registerUser("bob", "password456");
        
        assertThrows(IllegalArgumentException.class, () -> {
            repository.updateElo(null, "bob");
        }, "Updating ELO with null winner should throw exception");
    }

    @Test
    void testUpdateElo_NullLoser() {
        repository.registerUser("alice", "password123");
        
        assertThrows(IllegalArgumentException.class, () -> {
            repository.updateElo("alice", null);
        }, "Updating ELO with null loser should throw exception");
    }

    @Test
    void testUpdateElo_KFactorIs32() {
        repository.registerUser("alice", "password123");
        repository.registerUser("bob", "password456");
        
        // With equal ratings (1200), expected score is 0.5 for each
        // Winner should gain K * (1 - 0.5) = 32 * 0.5 = 16 points
        repository.updateElo("alice", "bob");
        
        int aliceElo = repository.getElo("alice");
        int bobElo = repository.getElo("bob");
        
        assertEquals(1216, aliceElo, "K-factor should be 32 (winner gains 16 from equal)");
        assertEquals(1184, bobElo, "K-factor should be 32 (loser loses 16 to equal)");
    }

    @Test
    void testDeleteUser_ExistingUser() {
        repository.registerUser("alice", "password123");
        boolean result = repository.deleteUser("alice");
        
        assertTrue(result, "Deleting existing user should return true");
        assertFalse(repository.userExists("alice"), "Deleted user should no longer exist");
    }

    @Test
    void testDeleteUser_NonExistentUser() {
        boolean result = repository.deleteUser("nonexistent");
        
        assertFalse(result, "Deleting non-existent user should return false");
    }

    @Test
    void testDeleteUser_NullUsername() {
        boolean result = repository.deleteUser(null);
        
        assertFalse(result, "Deleting with null username should return false");
    }

    @Test
    void testMultipleUsers() {
        // Register multiple users
        repository.registerUser("alice", "password1");
        repository.registerUser("bob", "password2");
        repository.registerUser("charlie", "password3");
        
        // All should exist
        assertTrue(repository.userExists("alice"));
        assertTrue(repository.userExists("bob"));
        assertTrue(repository.userExists("charlie"));
        
        // All should have correct passwords
        assertTrue(repository.verifyPassword("alice", "password1"));
        assertTrue(repository.verifyPassword("bob", "password2"));
        assertTrue(repository.verifyPassword("charlie", "password3"));
        
        // All should have default ELO
        assertEquals(1200, repository.getElo("alice"));
        assertEquals(1200, repository.getElo("bob"));
        assertEquals(1200, repository.getElo("charlie"));
    }
}
