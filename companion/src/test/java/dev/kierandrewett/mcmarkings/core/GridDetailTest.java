package dev.kierandrewett.mcmarkings.core;

import dev.kierandrewett.mcmarkings.render.GridRecommender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How much of a drawn sign survives being placed.
 *
 * <p>Found by rendering the real generators and measuring what the recommended grid
 * would do to them, rather than by reading the recommender. It picks on aspect ratio
 * and frame count and never considers resolution, which is defensible on its own
 * terms and hides the thing that decides whether a sign can be read.
 *
 * <p>The numbers below are the real ones. A three-destination primary route sign
 * draws at 1387px and is recommended a 3x1 grid, which is 384px, so an x-height set
 * to 50 arrives on the wall at about 14. Nothing said so anywhere.
 */
class GridDetailTest {

    @Test
    @DisplayName("the recommended grid for a realistic sign loses most of the detail")
    void realisticSignsAreDownscaled() {
        // The primary route sign from the generator, at its default x-height.
        GridSize recommended = GridRecommender.best(1387, 445);
        int detail = recommended.detailPercentFor(1387);

        assertTrue(detail < 50,
                () -> "expected the recommendation to cost detail, got " + detail + "% on " + recommended);

        // Stated rather than merely asserted-under, so a change to the recommender
        // shows up here as a number moving instead of a test quietly still passing.
        // It did move: this read 28% when the recommendation was chosen to keep the
        // sign's proportions, and 18% now it is chosen to spend as few frames as the
        // sign still covers most of. Both are the same fact, that placing a sign
        // costs it resolution, and the second is the trade someone asked for after
        // seeing the first on a wall.
        assertEquals(18, detail, 2, "the three-destination sign lands at about 18%");
    }

    @Test
    @DisplayName("a bigger grid keeps more of the sign")
    void moreFramesKeepMoreDetail() {
        int drawn = 1387;
        int small = new GridSize(3, 1).detailPercentFor(drawn);
        int large = new GridSize(8, 3).detailPercentFor(drawn);

        assertTrue(large > small,
                () -> "8x3 should keep more than 3x1, got " + large + "% against " + small + "%");
    }

    /**
     * Scaling up is not a gain, and reporting it as one would be worse than saying
     * nothing: it would read as a reason to spend frames that buy no detail.
     */
    @Test
    @DisplayName("placing a small image on a large grid does not report more than all of it")
    void upscalingIsNotReportedAsAGain() {
        assertEquals(100, new GridSize(8, 8).detailPercentFor(64));
        assertEquals(100, new GridSize(1, 1).detailPercentFor(128));
    }

    @Test
    @DisplayName("an unknown drawn size reads as unknown rather than as perfect")
    void unknownWidthIsZero() {
        assertEquals(0, new GridSize(2, 2).detailPercentFor(0));
        assertEquals(0, new GridSize(2, 2).detailPercentFor(-1));
    }
}
