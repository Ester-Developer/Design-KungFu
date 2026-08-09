package com.kungfuchess.net;

/**
 * Server-to-client message assigning the player's color and providing player names.
 *
 * <p>Sent in response to a {@link LoginMessage}. The color will be either
 * "WHITE" or "BLACK" based on connection order (first client = WHITE,
 * second client = BLACK). Also includes the usernames of both players.</p>
 */
public class ColorMessage {
    private String type = "COLOR";
    private String color;
    private String whiteName;  // Username of white player
    private String blackName;  // Username of black player

    public ColorMessage() {
        // Default constructor for Gson
    }

    public ColorMessage(String color) {
        this.color = color;
    }
    
    public ColorMessage(String color, String whiteName, String blackName) {
        this.color = color;
        this.whiteName = whiteName;
        this.blackName = blackName;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }
    
    public String getWhiteName() {
        return whiteName;
    }
    
    public void setWhiteName(String whiteName) {
        this.whiteName = whiteName;
    }
    
    public String getBlackName() {
        return blackName;
    }
    
    public void setBlackName(String blackName) {
        this.blackName = blackName;
    }

    @Override
    public String toString() {
        return "ColorMessage{type='" + type + "', color='" + color + 
               "', whiteName='" + whiteName + "', blackName='" + blackName + "'}";
    }
}
