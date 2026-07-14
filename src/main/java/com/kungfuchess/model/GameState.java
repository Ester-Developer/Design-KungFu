package com.kungfuchess.model;

public class GameState {
    private Board board;
    private String turn = "white";

    public GameState(Board board) { this.board = board; }

    public Board getBoard() { return board; }
    public String getTurn() { return turn; }

    public void switchTurn() { turn = "white".equals(turn) ? "black" : "white"; }
}
