package com.kungfuchess.net;

/**
 * Client-to-server message for initial login with username and password.
 *
 * <p>Sent by the client immediately after connection is established to
 * authenticate the player. The server responds with an {@link AuthResultMessage}
 * indicating success or failure, followed by a {@link ColorMessage} if authentication
 * succeeds.</p>
 */
public class LoginMessage {
    private String type = "LOGIN";
    private String username;
    private String password;

    public LoginMessage() {
        // Default constructor for Gson
    }

    public LoginMessage(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public String toString() {
        return "LoginMessage{type='" + type + "', username='" + username + "', password='***'}";
    }
}
