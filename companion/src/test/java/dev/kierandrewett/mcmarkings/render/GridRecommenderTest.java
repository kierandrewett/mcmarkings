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
        // Regression, from real use. A 601x1024 image was once recommended at 2x3,
        // then at 1x2, and now sits on a single frame: it covers 59% of one, which is
        // enough, and one frame is fewer than two.
        GridSize best = GridRecommender.best(601, 1024);

        assertEquals(new GridSize(1, 1), best);
        assertEquals(1, best.frameCount());
    }

    @Test
    @DisplayName("a wide banner takes the fewest frames it still covers most of")
    void wideBannerTakesSmallestReasonableGrid() {
        // 450x170 is 2.65:1. On one frame the sign would cover 38% of it, which is
        // mostly empty wall; on two it covers 75%.
        GridSize best = GridRecommender.best(450, 170);

        assertEquals(new GridSize(2, 1), best);
        assertTrue(best.frameCount() <= 4, "a small banner should not need a wall, got " + best);
    }

    @Test
    @DisplayName("a nearly square sign fits on one frame rather than six")
    void typicalSignPrefersFewerFrames() {
        // 1024x733 is roughly the shape of the warning signs here, 1.40:1. On a single
        // frame the sign covers 71% of it. The old answer was 3x2, six frames, chosen
        // to get the proportions within 7% back when the proportions decided whether
        // the sign would be squashed. Publishing keeps the shape now, so they do not.
        GridSize best = GridRecommender.best(1024, 733);

        assertEquals(new GridSize(1, 1), best);
    }

    /**
     * An exact fit no longer wins by being exact.
     *
     * <p>A 4:1 image on a 4x1 grid wastes nothing, and on a 2x1 it covers half. Both
     * look the same on a wall, because the shape is kept either way and the spare
     * space is transparent. One costs four item frames and the other two.
     *
     * <p>This is the trade the change was asked for. A square image still takes one
     * frame, which is both the exact fit and the fewest.
     */
    @Test
    @DisplayName("frames are spent only where the sign covers them")
    void exactFitsAreTakenWhenCheap() {
        assertEquals(new GridSize(1, 1), GridRecommender.best(1000, 1000));
        assertEquals(new GridSize(2, 1), GridRecommender.best(2048, 512));
        assertEquals(new GridSize(1, 2), GridRecommender.best(512, 2048));
        assertEquals(new GridSize(2, 1), GridRecommender.best(1536, 512));
    }

    /**
     * The sign that prompted all this, at the size the generator makes it.
     */
    @Test
    @DisplayName("a three destination direction sign takes two frames")
    void aDirectionSignTakesTwoFrames() {
        assertEquals(new GridSize(2, 1), GridRecommender.best(1601, 445));
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
    @DisplayName("top() leads with the recommendation, not with a grid of mostly empty wall")
    void topLeadsWithTheRecommendation() {
        List<GridSuggestion> top = GridRecommender.top(1024, 733, 3);

        assertTrue(top.size() <= 3);
        assertEquals(GridRecommender.best(1024, 733), top.getFirst().grid());
        double imageAspect = 1024 / 733.0;
        for (GridSuggestion suggestion : top) {
            int coverage = GridSuggestion.coveragePercent(suggestion.grid(), imageAspect);
            assertTrue(coverage >= GridRecommender.MINIMUM_COVERAGE * 100.0,
                    "top() offered a grid the sign barely covers: " + suggestion.grid()
                            + " at " + coverage + "%");
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

    /**
     * Two rules, because there are two situations and only one of them is the mod's.
     *
     * <p>Publishing squares the PNG up to the grid itself, so any grid is safe and
     * the only cost of a small one is frames placed with nothing on them. Placing an
     * image straight from a repository hands ImageFrame a URL and a grid and lets it
     * fit the image, and what happens there is the server's business.
     *
     * <p>Changing the rule everywhere was a mistake on that second path. A 4:1 image
     * on a 2x1 is fine when the mod pads it and half its width when the server
     * stretches it, and which of those ImageFrame does is not something this can find
     * out from here.
     */
    @Test
    @DisplayName("where the server does the fitting, the grid matches the shape")
    void theServerFittedPathMatchesTheShape() {
        // The sign that prompted the change. Two frames when the mod pads it, three
        // when something else decides: at 3.60:1 a 3x1 is 19.9% off, which slips under
        // the twenty per cent this rule has always allowed. I guessed four writing
        // this and the numbers say three.
        assertEquals(new GridSize(2, 1), GridRecommender.best(1601, 445));
        assertEquals(new GridSize(3, 1), GridRecommender.bestMatchingShape(1601, 445));

        // An exact fit is taken outright, which is the whole point of this rule.
        assertEquals(new GridSize(4, 1), GridRecommender.bestMatchingShape(2048, 512));
        assertEquals(new GridSize(1, 1), GridRecommender.bestMatchingShape(1000, 1000));
    }

    /**
     * And that it is still not extravagant. The old rule was already tuned to spend
     * frames sparingly, since every one is placed by hand, and that has not changed.
     */
    @Test
    @DisplayName("matching the shape does not mean spending frames without limit")
    void shapeMatchingIsStillFrugal() {
        assertTrue(GridRecommender.bestMatchingShape(1024, 733).frameCount() <= 6);
        assertTrue(GridRecommender.bestMatchingShape(601, 1024).frameCount() <= 4);
    }

    /**
     * That each path asks for the rule it needs.
     *
     * <p>The two rules above are facts about arithmetic and say nothing about which
     * screen uses which. Swapping the browser back to the fewest-frames rule left
     * every test green, and that swap is the regression this pair exists to prevent:
     * it hands the server a grid the image does not fill and leaves the result to
     * whatever the server does about it.
     */
    @Test
    @DisplayName("the browser asks for the shape-matching rule, publishing asks for the other")
    void eachPathAsksForItsOwnRule() throws java.io.IOException {
        String shell = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/dev/kierandrewett/mcmarkings/gui/imgui/ImGuiShell.java"));

        assertTrue(shell.contains("GridRecommender.bestMatchingShape("),
                "the browser is choosing a grid without matching the image's shape, and it "
                        + "cannot fit the image itself");
        assertTrue(shell.contains("GridRecommender.topMatchingShape("),
                "the browser recommends one rule and offers another, so the button it lands "
                        + "on and the buttons beside it disagree with nothing saying why");

        // The generator publishes through the mod, which squares the image up, so it
        // is the one that may prefer fewer frames.
        String generator = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/dev/kierandrewett/mcmarkings/gui/imgui/panel/GeneratorPanel.java"));
        assertTrue(generator.contains("GridRecommender.best("),
                "the generator is no longer using the rule for images the mod fits itself");
    }

    /**
     * What is offered follows what is recommended, under either rule.
     *
     * <p>The list starts where the recommendation stops, so the first button is the
     * one the mod would pick. Two rules means two starting points, and getting that
     * wrong shows up as a panel recommending a grid it does not offer.
     */
    @Test
    @DisplayName("the first thing offered is the thing recommended, whichever rule is asked for")
    void whatIsOfferedLeadsWithWhatIsRecommended() {
        for (int[] size : new int[][] {{1601, 445}, {2048, 512}, {1024, 733}, {601, 1024}}) {
            List<GridSuggestion> padded = GridRecommender.top(size[0], size[1], 3);
            assertEquals(GridRecommender.best(size[0], size[1]), padded.getFirst().grid(),
                    size[0] + "x" + size[1] + " offers a different grid than it recommends");

            List<GridSuggestion> shaped = GridRecommender.topMatchingShape(size[0], size[1], 3);
            assertEquals(GridRecommender.bestMatchingShape(size[0], size[1]), shaped.getFirst().grid(),
                    size[0] + "x" + size[1] + " offers a different shape-matched grid than it recommends");
        }
    }
}
