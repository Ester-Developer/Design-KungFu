package com.kungfuchess.model;

public class Piece {
    private final String kind;
    private final String color;

    public Piece(String kind, String color) {
        this.kind = kind;
        this.color = color;
    }

    public String getKind() { return kind; }
    public String getColor() { return color; }

    @Override
    public String toString() { return "Piece(" + kind + ", " + color + ")"; }
}
