package com.kungfuchess.net;

/**
 * Client-to-server message requesting a Dodge: a same-square second click on a piece
 * that is currently threatened by an incoming enemy motion.
 */
public class DodgeMessage {
    private String type = "DODGE";
    private String square;

    public DodgeMessage() {
        // Default constructor for Gson
    }

    public DodgeMessage(String square) {
        this.square = square;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSquare() {
        return square;
    }

    public void setSquare(String square) {
        this.square = square;
    }

    @Override
    public String toString() {
        return "DodgeMessage{type='" + type + "', square='" + square + "'}";
    }
}
