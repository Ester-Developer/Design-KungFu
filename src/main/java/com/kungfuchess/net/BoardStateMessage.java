package com.kungfuchess.net;

import com.kungfuchess.model.Position;
import java.util.List;

/**
 * Server-to-client message containing the full game state snapshot.
 *
 * <p>Extended to carry rich GameSnapshot data: per-piece cooldowns, game-over state,
 * scores, turn, and rejection flash position. This enables the client to render
 * cooldown indicators, selection highlights, and play appropriate sound effects.</p>
 *
 * <p>The {@code board} field is retained for backward compatibility but is redundant
 * when {@code pieces} is populated.</p>
 */
public class BoardStateMessage {
    private String type = "BOARD_STATE";
    
    // Legacy flat board representation (retained for backward compatibility)
    private String[][] board;
    
    // Rich game state (from GameEngine.snapshot())
    private List<PieceData> pieces;
    private int boardWidth;
    private int boardHeight;
    private boolean gameOver;
    private String turn;
    private long clock;
    private int scoreWhite;
    private int scoreBlack;
    private PositionData lastRejectedDest;  // For rejection flash
    private String winner;  // "white", "black", or null
    private List<MotionData> motions;        // in-flight motions, for smooth client-side sliding
    private List<MoveLogEntryData> moveLog;  // structured move history, for the sidebar tables
    private int whiteElo;  // live ELO — refreshed after every game, not just at login
    private int blackElo;

    /**
     * Wire form of {@code com.kungfuchess.realtime.Motion} — an in-flight piece travelling
     * from one cell to another. Lets the client render smooth movement (and correctly
     * withhold the cooldown-ring overlay until the piece actually lands), matching the
     * local/offline rendering path exactly.
     */
    public static class MotionData {
        private String kind;
        private String color;
        private PositionData from;
        private PositionData to;
        private long startTime;
        private long dueTime;
        private boolean jump;
        private boolean dodge;

        public MotionData() {}

        public MotionData(String kind, String color, PositionData from, PositionData to,
                          long startTime, long dueTime, boolean jump, boolean dodge) {
            this.kind = kind;
            this.color = color;
            this.from = from;
            this.to = to;
            this.startTime = startTime;
            this.dueTime = dueTime;
            this.jump = jump;
            this.dodge = dodge;
        }

        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
        public PositionData getFrom() { return from; }
        public void setFrom(PositionData from) { this.from = from; }
        public PositionData getTo() { return to; }
        public void setTo(PositionData to) { this.to = to; }
        public long getStartTime() { return startTime; }
        public void setStartTime(long startTime) { this.startTime = startTime; }
        public long getDueTime() { return dueTime; }
        public void setDueTime(long dueTime) { this.dueTime = dueTime; }
        public boolean isJump() { return jump; }
        public void setJump(boolean jump) { this.jump = jump; }
        public boolean isDodge() { return dodge; }
        public void setDodge(boolean dodge) { this.dodge = dodge; }
    }

    /** Wire form of {@code GameEngine.GameSnapshot.MoveLogEntry} — one row in the move-log tables. */
    public static class MoveLogEntryData {
        private String timestamp;
        private String color;
        private String pieceKind;
        private PositionData from;
        private PositionData to;
        private String capturedKind;
        private boolean jump;
        private boolean promoted;
        private boolean dodge;
        private boolean scream;

        public MoveLogEntryData() {}

        public MoveLogEntryData(String timestamp, String color, String pieceKind,
                                PositionData from, PositionData to, String capturedKind,
                                boolean jump, boolean promoted, boolean dodge, boolean scream) {
            this.timestamp = timestamp;
            this.color = color;
            this.pieceKind = pieceKind;
            this.from = from;
            this.to = to;
            this.capturedKind = capturedKind;
            this.jump = jump;
            this.promoted = promoted;
            this.dodge = dodge;
            this.scream = scream;
        }

        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
        public String getPieceKind() { return pieceKind; }
        public void setPieceKind(String pieceKind) { this.pieceKind = pieceKind; }
        public PositionData getFrom() { return from; }
        public void setFrom(PositionData from) { this.from = from; }
        public PositionData getTo() { return to; }
        public void setTo(PositionData to) { this.to = to; }
        public String getCapturedKind() { return capturedKind; }
        public void setCapturedKind(String capturedKind) { this.capturedKind = capturedKind; }
        public boolean isJump() { return jump; }
        public void setJump(boolean jump) { this.jump = jump; }
        public boolean isPromoted() { return promoted; }
        public void setPromoted(boolean promoted) { this.promoted = promoted; }
        public boolean isDodge() { return dodge; }
        public void setDodge(boolean dodge) { this.dodge = dodge; }
        public boolean isScream() { return scream; }
        public void setScream(boolean scream) { this.scream = scream; }
    }

    /**
     * Per-piece data matching GameSnapshot.PieceView fields.
     */
    public static class PieceData {
        private String kind;
        private String color;
        private PositionData position;
        private long restUntilMs;  // Absolute game-clock time when cooldown ends
        private long restStartMs;  // Absolute game-clock time when cooldown began
        
        public PieceData() {}
        
        public PieceData(String kind, String color, PositionData position, long restUntilMs, long restStartMs) {
            this.kind = kind;
            this.color = color;
            this.position = position;
            this.restUntilMs = restUntilMs;
            this.restStartMs = restStartMs;
        }
        
        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }
        
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
        
        public PositionData getPosition() { return position; }
        public void setPosition(PositionData position) { this.position = position; }
        
        public long getRestUntilMs() { return restUntilMs; }
        public void setRestUntilMs(long restUntilMs) { this.restUntilMs = restUntilMs; }
        
        public long getRestStartMs() { return restStartMs; }
        public void setRestStartMs(long restStartMs) { this.restStartMs = restStartMs; }
    }
    
    /**
     * Position data for JSON serialization.
     */
    public static class PositionData {
        private int row;
        private int col;
        
        public PositionData() {}
        
        public PositionData(int row, int col) {
            this.row = row;
            this.col = col;
        }
        
        public int getRow() { return row; }
        public void setRow(int row) { this.row = row; }
        
        public int getCol() { return col; }
        public void setCol(int col) { this.col = col; }
        
        public Position toPosition() {
            return new Position(row, col);
        }
    }

    public BoardStateMessage() {
        // Default constructor for Gson
    }

    /**
     * Legacy constructor (flat board only).
     */
    public BoardStateMessage(String[][] board) {
        this.board = board;
    }
    
    /**
     * Full constructor with rich game state.
     */
    public BoardStateMessage(String[][] board, List<PieceData> pieces, int boardWidth, int boardHeight,
                             boolean gameOver, String turn, long clock,
                             int scoreWhite, int scoreBlack, PositionData lastRejectedDest, String winner) {
        this(board, pieces, boardWidth, boardHeight, gameOver, turn, clock,
             scoreWhite, scoreBlack, lastRejectedDest, winner, null, null);
    }

    /**
     * Full constructor, also carrying in-flight motions and the structured move log
     * (needed for smooth client-side animation and the move-log sidebar tables).
     */
    public BoardStateMessage(String[][] board, List<PieceData> pieces, int boardWidth, int boardHeight,
                             boolean gameOver, String turn, long clock,
                             int scoreWhite, int scoreBlack, PositionData lastRejectedDest, String winner,
                             List<MotionData> motions, List<MoveLogEntryData> moveLog) {
        this(board, pieces, boardWidth, boardHeight, gameOver, turn, clock,
             scoreWhite, scoreBlack, lastRejectedDest, winner, motions, moveLog, 0, 0);
    }

    /** Full constructor, also carrying each side's live ELO (refreshed after every game). */
    public BoardStateMessage(String[][] board, List<PieceData> pieces, int boardWidth, int boardHeight,
                             boolean gameOver, String turn, long clock,
                             int scoreWhite, int scoreBlack, PositionData lastRejectedDest, String winner,
                             List<MotionData> motions, List<MoveLogEntryData> moveLog,
                             int whiteElo, int blackElo) {
        this.board = board;
        this.pieces = pieces;
        this.boardWidth = boardWidth;
        this.boardHeight = boardHeight;
        this.gameOver = gameOver;
        this.turn = turn;
        this.clock = clock;
        this.scoreWhite = scoreWhite;
        this.scoreBlack = scoreBlack;
        this.lastRejectedDest = lastRejectedDest;
        this.winner = winner;
        this.motions = motions;
        this.moveLog = moveLog;
        this.whiteElo = whiteElo;
        this.blackElo = blackElo;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String[][] getBoard() {
        return board;
    }

    public void setBoard(String[][] board) {
        this.board = board;
    }
    
    public List<PieceData> getPieces() {
        return pieces;
    }
    
    public void setPieces(List<PieceData> pieces) {
        this.pieces = pieces;
    }
    
    public int getBoardWidth() {
        return boardWidth;
    }
    
    public void setBoardWidth(int boardWidth) {
        this.boardWidth = boardWidth;
    }
    
    public int getBoardHeight() {
        return boardHeight;
    }
    
    public void setBoardHeight(int boardHeight) {
        this.boardHeight = boardHeight;
    }
    
    public boolean isGameOver() {
        return gameOver;
    }
    
    public void setGameOver(boolean gameOver) {
        this.gameOver = gameOver;
    }
    
    public String getTurn() {
        return turn;
    }
    
    public void setTurn(String turn) {
        this.turn = turn;
    }
    
    public long getClock() {
        return clock;
    }
    
    public void setClock(long clock) {
        this.clock = clock;
    }
    
    public int getScoreWhite() {
        return scoreWhite;
    }
    
    public void setScoreWhite(int scoreWhite) {
        this.scoreWhite = scoreWhite;
    }
    
    public int getScoreBlack() {
        return scoreBlack;
    }
    
    public void setScoreBlack(int scoreBlack) {
        this.scoreBlack = scoreBlack;
    }
    
    public PositionData getLastRejectedDest() {
        return lastRejectedDest;
    }
    
    public void setLastRejectedDest(PositionData lastRejectedDest) {
        this.lastRejectedDest = lastRejectedDest;
    }
    
    public String getWinner() {
        return winner;
    }

    public void setWinner(String winner) {
        this.winner = winner;
    }

    public List<MotionData> getMotions() {
        return motions;
    }

    public void setMotions(List<MotionData> motions) {
        this.motions = motions;
    }

    public List<MoveLogEntryData> getMoveLog() {
        return moveLog;
    }

    public void setMoveLog(List<MoveLogEntryData> moveLog) {
        this.moveLog = moveLog;
    }

    public int getWhiteElo() {
        return whiteElo;
    }

    public void setWhiteElo(int whiteElo) {
        this.whiteElo = whiteElo;
    }

    public int getBlackElo() {
        return blackElo;
    }

    public void setBlackElo(int blackElo) {
        this.blackElo = blackElo;
    }

    @Override
    public String toString() {
        return "BoardStateMessage{type='" + type + "', pieces=" + (pieces != null ? pieces.size() : 0) 
             + ", gameOver=" + gameOver + ", turn='" + turn + "', clock=" + clock + "}";
    }
}
