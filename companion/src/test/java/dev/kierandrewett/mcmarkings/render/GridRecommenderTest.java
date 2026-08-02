package dev.kierandrewett.mcmarkings.render;

import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.core.GridSuggestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GridRecommenderTest {

    @Test
    @DisplayName("a small plate stays on one map instead of demanding a wall")
    void smallPlateStaysOnOneFrame() {
        // Regression. The generated speed limit plate is 480x546, which is within
        // 2.6% of 6x7 but 13.7% off 1x1. Chasing the best fit recommended 42 frames
        // for a sign that reads perfectly on one.
        GridSize best = GridRecommender.best(480, 546);

        assertEquals(new GridSize(1, 1), best);
        assertTrue(best.isSingle());
    }

    @Test
    @DisplayName("a tall image takes two frames rather than six for a few percent")
    void tallImagePrefersTheCheaperGrid() {
        // Regression, from real use. A 601x1024 image was recommended at 2x3 because
        // 1x2 is 17% off and the threshold was 15%. Six frames to save four percent
        // of stretch is a bad trade when every frame is placed by hand.
        GridSize best = GridRecommender.best(601, 1024);

        assertEquals(new GridSize(1, 2), best);
        assertEquals(2, best.frameCount());
    }

    @Test
    @DisplayName("a wide banner takes the smallest grid that is not obviously stretched")
    void wideBannerTakesSmallestReasonableGrid() {
        // 450x170 is 2.65:1. On one map it would be squashed beyond recognition, so
        // some frames are justified here, but only a few.
        GridSize best = GridRecommender.best(450, 170);

        assertEquals(new GridSize(3, 1), best);
        assertTrue(best.frameCount() <= 4, "a small banner should not need a wall, got " + best);
    }

    @Test
    @DisplayName("a typical sign trades a little stretch for far fewer frames")
    void typicalSignPrefersFewerFrames() {
        // 1024x733 is roughly the shape of the warning signs here. 7x5 fits to within
        // 0.2% but costs 35 frames; 3x2 is 7.4% off and costs 6.
        GridSize best = GridRecommender.best(1024, 733);

        assertEquals(new GridSize(3, 2), best);
        assertTrue(best.frameCount() <= 6, "should not spend 35 frames chasing an exact fit");
    }

    @Test
    @DisplayName("exact fits are still taken when they are cheap")
    void exactFitsAreTakenWhenCheap() {
        assertEquals(new GridSize(1, 1), GridRecommender.best(1000, 1000));
        assertEquals(new GridSize(4, 1), GridRecommender.best(2048, 512));
        assertEquals(new GridSize(1, 4), GridRecommender.best(512, 2048));
        assertEquals(new GridSize(3, 1), GridRecommender.best(1536, 512));
    }

    @Test
    @DisplayName("near-square images stay on a single map")
    void nearSquareGetsSingleFrame() {
        assertEquals(new GridSize(1, 1), GridRecommender.best(512, 500));
        assertEquals(new GridSize(1, 1), GridRecommender.best(500, 512));
    }

    @Test
    @DisplayName("the frontier starts at 1x1, grows, and strictly improves")
    void frontierGrowsAndImproves() {
        List<GridSuggestion> frontier = GridRecommender.suggest(1024, 733, 8);

        assertEquals(new GridSize(1, 1), frontier.getFirst().grid(), "the frontier has to start somewhere cheap");

        int previousFrames = 0;
        double previousDistortion = Double.MAX_VALUE;
        for (GridSuggestion suggestion : frontier) {
            assertTrue(suggestion.grid().frameCount() > previousFrames,
                    "frontier should grow: " + suggestion.grid());
            assertTrue(suggestion.distortion() < previousDistortion,
                    "a bigger grid that is not better shaped should have been dropped: " + suggestion.grid());
            previousFrames = suggestion.grid().frameCount();
            previousDistortion = suggestion.distortion();
        }
    }

    @Test
    @DisplayName("distortion is symmetric under transposition")
    void distortionIsSymmetricUnderTransposition() {
        // A portrait image must be scored exactly like the same image on its side,
        // otherwise tall signs would get systematically worse recommendations.
        List<GridSuggestion> landscape = GridRecommender.suggest(1600, 700, 8);
        List<GridSuggestion> portrait = GridRecommender.suggest(700, 1600, 8);

        assertEquals(landscape.size(), portrait.size());
        for (int index = 0; index < landscape.size(); index++) {
            GridSuggestion wide = landscape.get(index);
            GridSuggestion tall = portrait.get(index);
            assertEquals(wide.grid().columns(), tall.grid().rows(), "grids should mirror at index " + index);
            assertEquals(wide.grid().rows(), tall.grid().columns(), "grids should mirror at index " + index);
            assertEquals(wide.distortion(), tall.distortion(), 1.0e-12);
        }
    }

    @Test
    @DisplayName("a perfect fit reports zero distortion and reads as comfortable")
    void perfectFitReportsZero() {
        GridSuggestion exact = GridRecommender.suggest(1536, 512, 8).stream()
                .filter(suggestion -> suggestion.grid().equals(new GridSize(3, 1)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("3x1 should be on the frontier for a 3:1 image"));

        assertEquals(0.0, exact.distortion(), 1.0e-12);
        assertEquals(0, exact.distortionPercent());
        assertTrue(exact.isComfortable());
    }

    @Test
    @DisplayName("top() leads with the recommendation, not with a squashed 1x1")
    void topLeadsWithTheRecommendation() {
        List<GridSuggestion> top = GridRecommender.top(1024, 733, 3);

        assertTrue(top.size() <= 3);
        assertEquals(GridRecommender.best(1024, 733), top.getFirst().grid());
        for (GridSuggestion suggestion : top) {
            assertTrue(suggestion.distortion() <= GridRecommender.ACCEPTABLE,
                    "top() offered an obviously stretched option: " + suggestion.grid());
        }
    }

    @Test
    @DisplayName("top() still offers something when nothing fits well")
    void topDegradesRatherThanReturningNothing() {
        // 64:1 cannot be represented within an 8x8 cap, so the caller still needs
        // options rather than an empty list.
        List<GridSuggestion> top = GridRecommender.top(6400, 100, 3);

        assertTrue(top.size() >= 1, "top() should never come back empty");
    }

    @Test
    @DisplayName("best() never throws and never escapes the cap")
    void bestNeverThrowsForPositiveDimensions() {
        int[] dimensions = { 1, 2, 3, 7, 16, 128, 733, 1024, 4096, 7680, 100_000 };

        for (int width : dimensions) {
            for (int height : dimensions) {
                GridSize grid = GridRecommender.best(width, height);
                assertTrue(grid.columns() >= 1 && grid.rows() >= 1, width + "x" + height + " gave " + grid);
                assertTrue(grid.columns() <= GridRecommender.DEFAULT_MAX_DIMENSION
                        && grid.rows() <= GridRecommender.DEFAULT_MAX_DIMENSION, "grid escaped the cap: " + grid);
            }
        }
    }

    @Test
    @DisplayName("extreme shapes stay within a placeable number of frames")
    void extremeShapesStayPlaceable() {
        // Nothing acceptable exists for these, so the fallback ceiling has to hold.
        for (int[] shape : new int[][] { { 6400, 100 }, { 100, 6400 }, { 10000, 50 } }) {
            GridSize grid = GridRecommender.best(shape[0], shape[1]);
            assertTrue(grid.frameCount() <= GridRecommender.DEFAULT_MAX_FRAMES,
                    shape[0] + "x" + shape[1] + " wanted " + grid + ", which is too many frames");
        }
    }

    @Test
    @DisplayName("non-positive dimensions are rejected rather than guessed at")
    void nonPositiveDimensionsThrow() {
        assertThrows(IllegalArgumentException.class, () -> GridRecommender.best(0, 100));
        assertThrows(IllegalArgumentException.class, () -> GridRecommender.best(100, 0));
        assertThrows(IllegalArgumentException.class, () -> GridRecommender.best(-1, -1));
    }
}
