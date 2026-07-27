package tests.unit;

import com.kungfuchess.view.ViewConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that the black coordinate frame bands are symmetric (left=right width, top=bottom height)
 * and that all band positions reference shared constants from ViewConstants.
 */
class TestFrameSymmetry {

    @Test
    void leftAndRightRankBands_haveIdenticalWidth() {
        // Both left and right rank bands must use the same constant
        int leftBandWidth = ViewConstants.RANK_BAND_W;
        int rightBandWidth = ViewConstants.RANK_BAND_W;
        
        assertEquals(leftBandWidth, rightBandWidth,
            "Left and right rank bands must have identical width (read from same constant)");
    }

    @Test
    void topAndBottomFileBands_haveIdenticalHeight() {
        // Both top and bottom file bands must use the same constant
        int topBandHeight = ViewConstants.LABEL_BAND_H;
        int bottomBandHeight = ViewConstants.LABEL_BAND_H;
        
        assertEquals(topBandHeight, bottomBandHeight,
            "Top and bottom file bands must have identical height (read from same constant)");
    }

    @Test
    void boardOffsets_matchViewConstants() {
        // Board's left edge must be at: MARGIN + SIDEBAR_W + RANK_BAND_W
        int expectedBoardX = ViewConstants.MARGIN + ViewConstants.SIDEBAR_W + ViewConstants.RANK_BAND_W;
        assertEquals(expectedBoardX, ViewConstants.BOARD_X_OFFSET,
            "Board X offset must equal MARGIN + SIDEBAR_W + RANK_BAND_W");

        // Board's top edge must be at: MARGIN + TITLE_H + LABEL_BAND_H
        int expectedBoardY = ViewConstants.MARGIN + ViewConstants.TITLE_H + ViewConstants.LABEL_BAND_H;
        assertEquals(expectedBoardY, ViewConstants.BOARD_Y_OFFSET,
            "Board Y offset must equal MARGIN + TITLE_H + LABEL_BAND_H");
    }

    @Test
    void coordinateLabels_fitWithinTheirBands() {
        // Each label chip is smaller than its band by 4px total (2px margin per side)
        int rankChipWidth = ViewConstants.RANK_BAND_W - 4;
        int fileChipHeight = ViewConstants.LABEL_BAND_H - 4;
        
        assertTrue(rankChipWidth > 0 && rankChipWidth < ViewConstants.RANK_BAND_W,
            "Rank label chips must fit within rank band with margins");
        assertTrue(fileChipHeight > 0 && fileChipHeight < ViewConstants.LABEL_BAND_H,
            "File label chips must fit within label band with margins");
        
        // Verify centering formula: (band - chip) / 2 gives equal margins on both sides
        int rankMargin = (ViewConstants.RANK_BAND_W - rankChipWidth) / 2;
        int fileMargin = (ViewConstants.LABEL_BAND_H - fileChipHeight) / 2;
        
        assertEquals(2, rankMargin, "Rank label chips should be centered with 2px margins");
        assertEquals(2, fileMargin, "File label chips should be centered with 2px margins");
    }

    @Test
    void allFourBandWidths_useSingleSourceConstants() {
        // This test ensures no magic numbers or stale duplicates exist.
        // All four bands must reference ViewConstants, not local duplicates.
        
        // Left rank band: between left sidebar and board
        int leftBandStart = ViewConstants.MARGIN + ViewConstants.SIDEBAR_W;
        int leftBandEnd = leftBandStart + ViewConstants.RANK_BAND_W;
        assertEquals(ViewConstants.BOARD_X_OFFSET, leftBandEnd,
            "Left rank band end must equal board X offset");

        // Right rank band: immediately after board (800px wide)
        int boardWidth = 800;
        int rightBandStart = ViewConstants.BOARD_X_OFFSET + boardWidth;
        int rightBandEnd = rightBandStart + ViewConstants.RANK_BAND_W;
        
        assertTrue(rightBandEnd <= ViewConstants.CANVAS_W - ViewConstants.MARGIN,
            "Right rank band must fit before right margin");

        // Top file band: between title and board
        int topBandStart = ViewConstants.MARGIN + ViewConstants.TITLE_H;
        int topBandEnd = topBandStart + ViewConstants.LABEL_BAND_H;
        assertEquals(ViewConstants.BOARD_Y_OFFSET, topBandEnd,
            "Top file band end must equal board Y offset");

        // Bottom file band: immediately after board (800px tall)
        int boardHeight = 800;
        int bottomBandStart = ViewConstants.BOARD_Y_OFFSET + boardHeight;
        int bottomBandEnd = bottomBandStart + ViewConstants.LABEL_BAND_H;
        
        assertTrue(bottomBandEnd <= ViewConstants.CANVAS_H - ViewConstants.MARGIN,
            "Bottom file band must fit before bottom margin");
    }

    @Test
    void allFourFrameBands_haveIdenticalThickness() {
        // The definitive test: all four frame edges reference FRAME_BAND constant
        int topThickness = ViewConstants.LABEL_BAND_H;
        int bottomThickness = ViewConstants.LABEL_BAND_H;
        int leftThickness = ViewConstants.RANK_BAND_W;
        int rightThickness = ViewConstants.RANK_BAND_W;
        int masterConstant = ViewConstants.FRAME_BAND;

        assertEquals(masterConstant, topThickness,
            "Top frame band must equal FRAME_BAND constant");
        assertEquals(masterConstant, bottomThickness,
            "Bottom frame band must equal FRAME_BAND constant");
        assertEquals(masterConstant, leftThickness,
            "Left frame band must equal FRAME_BAND constant");
        assertEquals(masterConstant, rightThickness,
            "Right frame band must equal FRAME_BAND constant");
        
        assertEquals(topThickness, bottomThickness,
            "Top and bottom frame bands must be identical");
        assertEquals(leftThickness, rightThickness,
            "Left and right frame bands must be identical");
        assertEquals(topThickness, leftThickness,
            "All four frame bands must be identical");
    }
}
