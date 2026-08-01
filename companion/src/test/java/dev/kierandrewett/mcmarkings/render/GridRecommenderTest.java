package dev.kierandrewett.mcmarkings.render;

import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.core.GridSuggestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GridRecommenderTest {

    @Test
    @DisplayName("a typical sign lands on a small comfortable grid")
    void typicalSignGetsSmallGrid() {
        // 1024x733 is roughly the shape of the warning signs in this repo. 4x3 is the
        // smallest grid whose aspect is within the comfortable band of 1.397.
        GridSize best = GridRecommender.best(1024, 733);

        assertEquals(new GridSize(4, 3), best);
        assertTrue(best.frameCount() <= 12, "a sign should not demand a huge wall of frames");
    }

    @Test
    @DisplayName("near-square images stay on a single map")
    void nearSquareGetsSingleFrame() {
        assertEquals(new GridSize(1, 1), GridRecommender.best(1000, 1000));
        assertEquals(new GridSize(1, 1), GridRecommender.best(512, 500));
        assertEquals(new GridSize(1, 1), GridRecommender.best(500, 512));
        assertTrue(GridRecommender.best(1000, 1000).isSingle());
    }

    @Test
    @DisplayName("a 4:1 banner prefers a wide grid")
    void wideBannerPrefersWideGrid() {
        assertEquals(new GridSize(4, 1), GridRecommender.best(2048, 512));
        assertEquals(new GridSize(1, 4), GridRecommender.best(512, 2048));
    }

    @Test
    @DisplayName("distortion is symmetric between a grid twice as wide and one half as wide")
    void distortionIsSymmetric() {
        List<GridSuggestion> suggestions = GridRecommender.suggest(1000, 1000, 4);

        double twiceAsWide = distortionOf(suggestions, new GridSize(2, 1));
        double halfAsWide = distortionOf(suggestions, new GridSize(1, 2));
        assertEquals(twiceAsWide, halfAsWide, 1.0e-12);

        double fourTimes = distortionOf(suggestions, new GridSize(4, 1));
        double quarter = distortionOf(suggestions, new GridSize(1, 4));
        assertEquals(fourTimes, quarter, 1.0e-12);

        // The same has to hold for an image that is not square, otherwise portrait
        // images would be scored differently from landscape ones.
        List<GridSuggestion> landscape = GridRecommender.suggest(1600, 800, 4);
        assertEquals(
                distortionOf(landscape, new GridSize(4, 1)),
                distortionOf(landscape, new GridSize(1, 1)),
                1.0e-12);
    }

    @Test
    @DisplayName("best() never throws for any positive dimensions")
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
    @DisplayName("non-positive dimensions are rejected rather than guessed at")
    void nonPositiveDimensionsThrow() {
        assertThrows(IllegalArgumentException.class, () -> GridRecommender.best(0, 100));
        assertThrows(IllegalArgumentException.class, () -> GridRecommender.best(100, 0));
        assertThrows(IllegalArgumentException.class, () -> GridRecommender.best(-1, -1));
    }

    @Test
    @DisplayName("comfortable fits come before uncomfortable ones")
    void comfortableFitsComeFirst() {
        List<GridSuggestion> suggestions = GridRecommender.suggest(1024, 733, 8);

        boolean seenUncomfortable = false;
        for (GridSuggestion suggestion : suggestions) {
            if (!suggestion.isComfortable()) {
                seenUncomfortable = true;
                continue;
            }
            assertFalse(seenUncomfortable,
                    "comfortable " + suggestion.grid() + " appeared after an uncomfortable fit");
        }
        assertTrue(seenUncomfortable, "an 8x8 sweep should contain some badly shaped grids");
    }

    @Test
    @DisplayName("smaller grids win ties")
    void smallerGridsWinTies() {
        List<GridSuggestion> suggestions = GridRecommender.suggest(2048, 512, 8);

        // 4x1 and 8x2 both fit a 4:1 image perfectly, so the cheaper wall has to win.
        assertEquals(new GridSize(4, 1), suggestions.getFirst().grid());
        assertEquals(0.0, suggestions.getFirst().distortion(), 1.0e-12);

        int previousFrames = 0;
        for (GridSuggestion suggestion : suggestions) {
            if (!suggestion.isComfortable()) {
                break;
            }
            assertTrue(suggestion.grid().frameCount() >= previousFrames,
                    "comfortable suggestions should grow, not shrink: " + suggestion.grid());
            previousFrames = suggestion.grid().frameCount();
        }
    }

    @Test
    @DisplayName("suggest() covers the whole square and top() agrees with best()")
    void suggestCoversTheSquare() {
        assertEquals(16, GridRecommender.suggest(100, 100, 4).size());
        assertEquals(64, GridRecommender.suggest(100, 100, 8).size());

        List<GridSuggestion> top = GridRecommender.top(1024, 733, 3);
        assertEquals(3, top.size());
        assertEquals(GridRecommender.best(1024, 733), top.getFirst().grid());
    }

    @Test
    @DisplayName("a perfect fit reports zero distortion and reads as comfortable")
    void perfectFitReportsZero() {
        List<GridSuggestion> suggestions = GridRecommender.suggest(1536, 512, 8);
        GridSuggestion best = suggestions.getFirst();

        assertEquals(new GridSize(3, 1), best.grid());
        assertEquals(0, best.distortionPercent());
        assertTrue(best.isComfortable());
    }

    private static double distortionOf(List<GridSuggestion> suggestions, GridSize grid) {
        return suggestions.stream()
                .filter(suggestion -> suggestion.grid().equals(grid))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no suggestion for " + grid))
                .distortion();
    }
}
