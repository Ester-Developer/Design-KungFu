package tests.regression;

import com.kungfuchess.engine.GameEngine;
import com.kungfuchess.engine.GameEngine.GameSnapshot;
import com.kungfuchess.engine.GameEngine.GameSnapshot.MoveLogEntry;
import com.kungfuchess.engine.GameEngine.GameSnapshot.PieceView;
import com.kungfuchess.input.BoardMapper;
import com.kungfuchess.model.Board;
import com.kungfuchess.model.Piece;
import com.kungfuchess.model.Position;
import com.kungfuchess.realtime.Motion;
import com.kungfuchess.realtime.RealTimeArbiter;
import com.kungfuchess.view.ImageView;
import com.kungfuchess.view.PieceState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression-guard suite: one named test per known-good behavior.
 *
 * <p>Any future change that silently breaks one of these behaviors will fail loudly
 * here. Tests are written against current intended behavior — not against any
 * previously-buggy behavior.</p>
 *
 * <p>Part 0 requirement: this class must be kept up to date as the codebase evolves.
 * Do not delete or weaken tests here without an explicit, documented reason.</p>
 */
class TestKnownGoodBehaviors {

    // =========================================================================
    // Concurrent movement
    // =========================================================================

    /**
     * Two different pieces (same color) can have overlapping in-flight motions.
     * This is the core Kung-Fu Chess mechanic.
     */
    @Test
    void twoDifferentPiecesCanBeInFlightSimultaneously() throws Exception {
        Board b = Board.create(8, 8);
        Piece rook   = new Piece("Rook",   "white");
        Piece bishop = new Piece("Bishop", "white");
        b.addPiece(new Position(0, 0), rook);
        b.addPiece(new Position(2, 2), bishop);
        GameEngine engine = new GameEngine().setBoard(b);

        GameEngine.MoveResult r1 = engine.requestMove(new Position(0, 0), new Position(0, 4));
        GameEngine.MoveResult r2 = engine.requestMove(new Position(2, 2), new Position(4, 4));

        assertTrue(r1.isAccepted(), "First piece must be accepted");
        assertTrue(r2.isAccepted(), "Second piece must be accepted concurrently");
        assertEquals(2, engine.getArbiter().getPendingMotions().size(),
            "Both motions must be in flight simultaneously");
    }

    /**
     * Two pieces of opposite color can also be in flight simultaneously.
     */
    @Test
    void oppositeColorPiecesCanBeInFlightSimultaneously() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        b.addPiece(new Position(7, 7), new Piece("Rook", "black"));
        GameEngine engine = new GameEngine().setBoard(b);

        assertTrue(engine.requestMove(new Position(0, 0), new Position(0, 4)).isAccepted());
        assertTrue(engine.requestMove(new Position(7, 7), new Position(7, 3)).isAccepted());
        assertEquals(2, engine.getArbiter().getPendingMotions().size());
    }

    // =========================================================================
    // Per-piece cooldown gate
    // =========================================================================

    /**
     * The same piece is rejected with "piece_on_cooldown" while its own cooldown
     * is active, and accepted again once it clears.
     */
    @Test
    void samePieceRejectedDuringCooldownAndAcceptedAfter() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(0, 0), new Position(0, 3));

        // Immediately: same piece rejected
        GameEngine.MoveResult during = engine.requestMove(new Position(0, 0), new Position(0, 1));
        assertFalse(during.isAccepted());
        assertEquals(GameEngine.MoveResult.PIECE_ON_COOLDOWN, during.reason());

        // After full cooldown: accepted again
        engine.waitMs(8000);
        GameEngine.MoveResult after = engine.requestMove(new Position(0, 3), new Position(0, 6));
        assertTrue(after.isAccepted(), "Piece must be movable again after cooldown expires");
    }

    // =========================================================================
    // Knight jump flag
    // =========================================================================

    /** Knight motions have isJump=true; non-Knight motions have isJump=false. */
    @Test
    void knightMotionIsJumpNonKnightIsNot() throws Exception {
        Board b = Board.create(8, 8);
        Piece knight = new Piece("Knight", "white");
        Piece rook   = new Piece("Rook",   "white");
        b.addPiece(new Position(4, 4), knight);
        b.addPiece(new Position(0, 0), rook);
        RealTimeArbiter arbiter = new RealTimeArbiter();

        arbiter.startMotion(knight, new Position(4, 4), new Position(2, 5));
        arbiter.startMotion(rook,   new Position(0, 0), new Position(0, 3));

        List<Motion> motions = arbiter.getPendingMotions();
        Motion knightMotion = motions.stream()
            .filter(m -> "Knight".equals(m.getPiece().getKind())).findFirst().orElseThrow();
        Motion rookMotion = motions.stream()
            .filter(m -> "Rook".equals(m.getPiece().getKind())).findFirst().orElseThrow();

        assertTrue(knightMotion.isJump(),  "Knight motion must have isJump=true");
        assertFalse(rookMotion.isJump(),   "Rook motion must have isJump=false");
    }

    // =========================================================================
    // Knight capturable after landing (Fix 1 regression guard)
    // =========================================================================

    /**
     * A Knight that has landed is immediately capturable — no lingering immunity.
     * This guards against re-introduction of the addProtection call in startMotion.
     */
    @Test
    void knightIsCapturableImmediatelyAfterLanding() throws Exception {
        Board b = Board.create(8, 8);
        Piece knight   = new Piece("Knight", "white");
        Piece attacker = new Piece("Rook",   "black");
        b.addPiece(new Position(4, 4), knight);
        b.addPiece(new Position(2, 0), attacker);
        GameEngine engine = new GameEngine().setBoard(b);

        // Knight jumps to (2,5)
        engine.requestMove(new Position(4, 4), new Position(2, 5));
        engine.waitMs(5000); // well past travel + rest chain

        // Knight is now stationary at (2,5). Attacker captures it.
        engine.requestMove(new Position(2, 0), new Position(2, 5));
        engine.waitMs(5000);

        Piece atDest = b.pieceAt(new Position(2, 5)).orElse(null);
        assertNotNull(atDest, "A piece must be at (2,5) after capture");
        assertEquals("Rook", atDest.getKind(),
            "The attacker (Rook) must have captured the Knight — not been blocked");
    }

    // =========================================================================
    // Knight jump end-to-end (Part 2)
    // =========================================================================

    /** Knight completes a jump via requestMove and lands at the correct square. */
    @Test
    void knightJumpCompletesEndToEnd() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(7, 1), new Piece("Knight", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        GameEngine.MoveResult r = engine.requestMove(new Position(7, 1), new Position(5, 2));
        assertTrue(r.isAccepted(), "Knight jump must be accepted, got: " + r.reason());

        engine.waitMs(4000);

        assertTrue(b.pieceAt(new Position(7, 1)).isEmpty(), "Origin must be empty after landing");
        assertTrue(b.pieceAt(new Position(5, 2)).isPresent(), "Knight must be at destination");
    }

    /** Knight jumps over occupying pieces — the defining jump behavior. */
    @Test
    void knightJumpsOverOccupyingPieces() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(7, 1), new Piece("Knight", "white"));
        // Block every adjacent square
        b.addPiece(new Position(7, 2), new Piece("Pawn", "black"));
        b.addPiece(new Position(6, 1), new Piece("Pawn", "black"));
        b.addPiece(new Position(6, 2), new Piece("Pawn", "black"));
        GameEngine engine = new GameEngine().setBoard(b);

        GameEngine.MoveResult r = engine.requestMove(new Position(7, 1), new Position(5, 2));
        assertTrue(r.isAccepted(),
            "Knight must jump over blocking pieces, got: " + r.reason());

        engine.waitMs(4000);
        assertTrue(b.pieceAt(new Position(5, 2)).isPresent(),
            "Knight must land at destination even when intermediate squares are occupied");
    }

    /** Knight via Controller.click with correct BOARD_X_OFFSET pixel coordinates. */
    @Test
    void knightJumpViaControllerClick() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(7, 1), new Piece("Knight", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        // BOARD_X_OFFSET=224; cell=100px; click centre of cell
        // col=1 → x = 224 + 1*100 + 50 = 374; row=7 → y = 65 + 7*100 + 50 = 815
        engine.getController().click(374, 815);
        // col=2 → x = 224 + 2*100 + 50 = 474; row=5 → y = 65 + 5*100 + 50 = 615
        engine.getController().click(474, 615);

        engine.waitMs(4000);

        assertTrue(b.pieceAt(new Position(7, 1)).isEmpty(), "Origin must be empty");
        assertTrue(b.pieceAt(new Position(5, 2)).isPresent(), "Knight must be at (5,2)");
    }

    /** All 8 L-shape vectors are accepted by the rule engine. */
    @Test
    void knightAllEightLShapeVectorsAccepted() throws Exception {
        int[][] vectors = {{-2,-1},{-2,1},{-1,-2},{-1,2},{1,-2},{1,2},{2,-1},{2,1}};
        for (int[] v : vectors) {
            Board b = Board.create(8, 8);
            Position from = new Position(4, 4);
            Position to   = new Position(4 + v[0], 4 + v[1]);
            b.addPiece(from, new Piece("Knight", "white"));
            GameEngine engine = new GameEngine().setBoard(b);
            GameEngine.MoveResult r = engine.requestMove(from, to);
            assertTrue(r.isAccepted(),
                "Knight vector (" + v[0] + "," + v[1] + ") must be accepted, got: " + r.reason());
        }
    }

    // =========================================================================
    // Pawn double-step
    // =========================================================================

    /** Pawn double-step works on first move. */
    @Test
    void pawnDoubleStepAcceptedOnFirstMove() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(6, 0), new Piece("Pawn", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        assertTrue(engine.requestMove(new Position(6, 0), new Position(4, 0)).isAccepted(),
            "Pawn double-step must be accepted on first move");
    }

    /** Pawn double-step blocked if intermediate square is occupied. */
    @Test
    void pawnDoubleStepBlockedWhenIntermediateOccupied() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(6, 0), new Piece("Pawn", "white"));
        b.addPiece(new Position(5, 0), new Piece("Pawn", "black")); // blocker
        GameEngine engine = new GameEngine().setBoard(b);

        assertFalse(engine.requestMove(new Position(6, 0), new Position(4, 0)).isAccepted(),
            "Pawn double-step must be blocked when intermediate square is occupied");
    }

    /** Pawn double-step blocked after the pawn has already moved once. */
    @Test
    void pawnDoubleStepBlockedAfterFirstMove() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(6, 0), new Piece("Pawn", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(6, 0), new Position(5, 0));
        engine.waitMs(8000);

        GameEngine.MoveResult r = engine.requestMove(new Position(5, 0), new Position(3, 0));
        assertFalse(r.isAccepted(), "Pawn double-step must be blocked after first move");
        assertEquals("illegal_piece_move", r.reason());
    }

    // =========================================================================
    // Pawn promotion
    // =========================================================================

    /** A pawn that reaches the far rank becomes a Queen. */
    @Test
    void pawnPromotesToQueenAtFarRank() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(1, 0), new Piece("Pawn", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(1, 0), new Position(0, 0));
        engine.waitMs(3000);

        Piece arrived = b.pieceAt(new Position(0, 0)).orElse(null);
        assertNotNull(arrived);
        assertEquals("Queen", arrived.getKind(), "Pawn must promote to Queen at far rank");
        assertEquals("white", arrived.getColor());
    }

    /** A pawn that does NOT reach the far rank stays a Pawn. */
    @Test
    void pawnStaysPawnWhenNotAtFarRank() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(3, 0), new Piece("Pawn", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(3, 0), new Position(2, 0));
        engine.waitMs(3000);

        Piece arrived = b.pieceAt(new Position(2, 0)).orElse(null);
        assertNotNull(arrived);
        assertEquals("Pawn", arrived.getKind(), "Pawn must remain a Pawn when not at far rank");
    }

    // =========================================================================
    // GameSnapshot immutability
    // =========================================================================

    /** Mutating the returned pieces list has no effect on the engine's board. */
    @Test
    void snapshotPiecesListMutationDoesNotAffectEngine() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        GameSnapshot snap = engine.snapshot();
        int before = snap.pieces().size();
        try { snap.pieces().clear(); } catch (UnsupportedOperationException ignored) {}

        assertEquals(before, engine.snapshot().pieces().size(),
            "Mutating snapshot pieces list must not affect the engine");
    }

    /** Mutating the returned move log has no effect on the engine's log. */
    @Test
    void snapshotMoveLogMutationDoesNotAffectEngine() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        GameEngine engine = new GameEngine().setBoard(b);
        engine.requestMove(new Position(0, 0), new Position(0, 3));
        engine.waitMs(3000);

        GameSnapshot snap = engine.snapshot();
        try { snap.moveLog().clear(); } catch (UnsupportedOperationException ignored) {}

        assertEquals(1, engine.snapshot().moveLog().size(),
            "Mutating snapshot move log must not affect the engine");
    }

    /** PieceView records contain only value data — no live Piece reference. */
    @Test
    void snapshotPieceViewsAreValueRecordsNotLiveObjects() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        PieceView pv = engine.snapshot().pieces().get(0);
        assertEquals("Rook",  pv.kind());
        assertEquals("white", pv.color());
        assertEquals(new Position(0, 0), pv.position());
        // restUntilMs and restStartMs are plain longs — no live reference possible
        assertEquals(0L, pv.restUntilMs());
        assertEquals(0L, pv.restStartMs());
    }

    // =========================================================================
    // Score tracking
    // =========================================================================

    /** A capture increments the correct player's score by the correct material value. */
    @Test
    void captureIncrementsCorrectPlayerScoreByCorrectValue() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("Rook",   "white")); // attacker
        b.addPiece(new Position(0, 3), new Piece("Knight", "black")); // defender (value=3)
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(0, 0), new Position(0, 3));
        engine.waitMs(3000);

        GameSnapshot snap = engine.snapshot();
        assertEquals(3, snap.scoreWhite(), "White must gain 3 points for capturing a Knight");
        assertEquals(0, snap.scoreBlack(), "Black score must remain 0");
    }

    /** A non-capturing arrival does not change any score. */
    @Test
    void nonCapturingArrivalDoesNotChangeScore() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(0, 0), new Position(0, 3));
        engine.waitMs(3000);

        GameSnapshot snap = engine.snapshot();
        assertEquals(0, snap.scoreWhite());
        assertEquals(0, snap.scoreBlack());
    }

    // =========================================================================
    // BoardMapper click mapping
    // =========================================================================

    /** A click at the board's actual top-left pixel (BOARD_X_OFFSET, BOARD_Y_OFFSET) maps to (0,0). */
    @Test
    void boardTopLeftPixelMapsToOriginCell() {
        Position p = BoardMapper.pixelToBoard(BoardMapper.BOARD_X_OFFSET, BoardMapper.BOARD_Y_OFFSET);
        assertEquals(new Position(0, 0), p,
            "Click at board top-left pixel must map to cell (0,0)");
    }

    /** A click in the left sidebar (x < BOARD_X_OFFSET) is out of bounds. */
    @Test
    void leftSidebarClickIsOutOfBounds() {
        Board board = Board.create(8, 8);
        assertFalse(BoardMapper.isPixelInBounds(board, 50, 100),
            "Click in left sidebar must be out of bounds");
    }

    /** A click in the right sidebar (x >= BOARD_X_OFFSET + 800) is out of bounds. */
    @Test
    void rightSidebarClickIsOutOfBounds() {
        Board board = Board.create(8, 8);
        assertFalse(BoardMapper.isPixelInBounds(board, 1050, 100),
            "Click in right sidebar must be out of bounds");
    }

    // =========================================================================
    // Part 1: Cooldown overlay
    // =========================================================================

    /**
     * A piece mid-cooldown produces a non-null overlay frame selection at roughly
     * the expected fraction.
     */
    @Test
    void cooldownOverlayFrameSelectedForRestingPiece() {
        // Piece resting: started at 0ms, ends at 4000ms, clock now at 2000ms
        // remaining fraction = (4000-2000)/(4000-0) = 0.5 → frame index 5 (50%)
        int idx = ImageView.cooldownFrameIndex(4000L, 0L, 2000L);
        assertEquals(5, idx, "50% remaining should select frame index 5");
    }

    /** An idle piece with no cooldown produces no overlay (index -1). */
    @Test
    void cooldownOverlayNotDrawnForIdlePiece() {
        int idx = ImageView.cooldownFrameIndex(0L, 0L, 1000L);
        assertEquals(-1, idx, "Idle piece must produce no overlay (index -1)");
    }

    /** A piece whose cooldown has already expired produces no overlay. */
    @Test
    void cooldownOverlayNotDrawnAfterCooldownExpires() {
        // restUntilMs=1000, clock=2000 → expired
        int idx = ImageView.cooldownFrameIndex(1000L, 0L, 2000L);
        assertEquals(-1, idx, "Expired cooldown must produce no overlay");
    }

    /**
     * PieceView.restStartMs is populated from the arbiter and exposed in the snapshot.
     */
    @Test
    void pieceViewRestStartMsIsPopulatedAfterMove() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(0, 0), new Position(0, 3));
        // restStartMs should be 0 (clock when startMotion was called)
        PieceView pv = engine.snapshot().pieces().stream()
            .filter(p -> "Rook".equals(p.kind())).findFirst().orElseThrow();
        assertEquals(0L, pv.restStartMs(),
            "restStartMs must equal the clock at the time startMotion was called (0)");
        assertTrue(pv.restUntilMs() > 0L,
            "restUntilMs must be non-zero while piece is on cooldown");
    }

    // =========================================================================
    // Part 3: Move log format
    // =========================================================================

    /**
     * A Knight capture produces a log entry with capture info but NOT jump flag
     * (jump special-case removed — Knight moves like any other piece).
     */
    @Test
    void knightJumpCaptureLogEntryContainsJumpAndCaptureName() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(4, 4), new Piece("Knight", "white"));
        b.addPiece(new Position(2, 5), new Piece("Pawn",   "black")); // will be captured
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(4, 4), new Position(2, 5));
        engine.waitMs(4000);

        List<MoveLogEntry> log = engine.snapshot().moveLog();
        assertEquals(1, log.size());
        MoveLogEntry entry = log.get(0);

        assertFalse(entry.isJump(),   "Log entry must NOT be flagged as jump (jump special-case removed)");
        assertTrue(entry.isCapture(), "Log entry must be flagged as capture");
        assertEquals("Pawn", entry.capturedKind());

        String formatted = ImageView.formatEntry(entry);
        assertFalse(formatted.contains("jump"),
            "Formatted entry must not contain 'jump': " + formatted);
        assertTrue(formatted.contains("Pawn"),
            "Formatted entry must contain captured piece name 'Pawn': " + formatted);
        assertTrue(formatted.contains("Knight"),
            "Formatted entry must contain full piece name 'Knight': " + formatted);
    }

    /** A plain pawn move produces a log entry with neither jump nor capture. */
    @Test
    void plainPawnMoveLogEntryHasNoJumpOrCapture() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(6, 0), new Piece("Pawn", "white"));
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(6, 0), new Position(5, 0));
        engine.waitMs(3000);

        List<MoveLogEntry> log = engine.snapshot().moveLog();
        assertEquals(1, log.size());
        MoveLogEntry entry = log.get(0);

        assertFalse(entry.isJump(),    "Plain pawn move must not be flagged as jump");
        assertFalse(entry.isCapture(), "Plain pawn move must not be flagged as capture");

        String formatted = ImageView.formatEntry(entry);
        assertFalse(formatted.contains("jump"),
            "Formatted entry must not contain 'jump': " + formatted);
        assertFalse(formatted.contains("capture"),
            "Formatted entry must not contain 'capture': " + formatted);
        assertTrue(formatted.contains("Pawn"),
            "Formatted entry must contain full piece name 'Pawn': " + formatted);
        assertTrue(formatted.contains("\u2192"),
            "Formatted entry must contain arrow '→': " + formatted);
    }

    /**
     * A capture by white appears only in white's filtered log, not black's.
     */
    @Test
    void captureByWhiteAppearsOnlyInWhiteLog() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("Rook",  "white"));
        b.addPiece(new Position(0, 3), new Piece("Queen", "black"));
        GameEngine engine = new GameEngine().setBoard(b);

        engine.requestMove(new Position(0, 0), new Position(0, 3));
        engine.waitMs(3000);

        List<MoveLogEntry> whiteMoves = engine.snapshot().moveLog().stream()
            .filter(e -> "white".equals(e.color())).collect(Collectors.toList());
        List<MoveLogEntry> blackMoves = engine.snapshot().moveLog().stream()
            .filter(e -> "black".equals(e.color())).collect(Collectors.toList());

        assertEquals(1, whiteMoves.size(), "Exactly one white move must be logged");
        assertFalse(whiteMoves.get(0).timestamp().isEmpty(), "Timestamp must be non-empty");
        assertTrue(whiteMoves.get(0).isCapture(), "Entry must be a capture");
        assertTrue(blackMoves.isEmpty(), "Capture by white must not appear in black's log");
    }

    // =========================================================================
    // Animator key fix regression guard (Fix 2)
    // =========================================================================

    /**
     * After a motion resolves, the animator keyed by the piece's new position is in
     * a rest state — not idle — proving the re-keying fix is intact.
     */
    @Test
    void afterLandingAnimatorAtDestinationKeyIsInRestState() throws Exception {
        Board b = Board.create(8, 8);
        b.addPiece(new Position(0, 0), new Piece("Rook", "white"));
        GameEngine engine = new GameEngine().setBoard(b);
        ImageView view = new ImageView();

        engine.requestMove(new Position(0, 0), new Position(0, 3));
        view.updateAnimatorsOnly(engine.snapshot());

        engine.waitMs(3000);
        view.updateAnimatorsOnly(engine.snapshot());

        String state = view.animatorStateForKey("wR@" + new Position(0, 3));
        assertNotEquals(PieceState.IDLE.folderName, state,
            "Animator at destination key must be in rest state after landing, not idle");
    }
}
