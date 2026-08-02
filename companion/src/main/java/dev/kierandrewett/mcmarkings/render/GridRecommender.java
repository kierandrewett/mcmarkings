package dev.kierandrewett.mcmarkings.render;

import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.core.GridSuggestion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Works out how many item frames an image should occupy.
 *
 * <p>Maps are square, so a grid's shape is fixed by its frame counts and an image
 * rarely matches one exactly. The interesting question is therefore not "which
 * grid fits best" but "how much wall is this worth", because every extra frame is
 * something the player has to place by hand.
 *
 * <p>Chasing the best fit alone gives absurd answers: a 480x546 plate is within
 * 2.6% of 6x7, but nobody builds a 42 frame wall for a speed limit sign when 1x1
 * is 13.7% off and completely convincing on a roadside. So the candidates are
 * reduced to a frontier where each entry is bigger than the last but genuinely
 * less distorted, and the recommendation is the smallest entry whose stretch
 * nobody would notice.
 */
public final class GridRecommender {

    /** Beyond this the grid is bigger than anyone wants to place by hand. */
    public static final int DEFAULT_MAX_DIMENSION = 8;

    /**
     * Stretch below this reads as "that is just the shape of the image".
     *
     * <p>Deliberately loose, and far looser than {@link GridSuggestion#COMFORTABLE},
     * which describes a near-exact fit rather than an acceptable one. At 0.15 a
     * 601x1024 image was pushed off 1x2 at 17% onto 2x3, tripling the frames to buy
     * 4% of accuracy that nobody looking at a wall would notice. Frames are placed
     * by hand, so the trade runs heavily in favour of fewer of them.
     */
    public static final double ACCEPTABLE = 0.20;

    /** Fallback ceiling when nothing reaches {@link #ACCEPTABLE}. */
    public static final int DEFAULT_MAX_FRAMES = 16;

    private GridRecommender() {
    }

    /** Best grid for an image, never null. */
    public static GridSize best(int imageWidth, int imageHeight) {
        List<GridSuggestion> frontier = suggest(imageWidth, imageHeight, DEFAULT_MAX_DIMENSION);

        for (GridSuggestion suggestion : frontier) {
            if (suggestion.distortion() <= ACCEPTABLE) {
                return suggestion.grid();
            }
        }

        // Nothing looks right small, so accept a bigger wall, but only up to the
        // point where placing it stops being reasonable.
        return frontier.stream()
                .filter(suggestion -> suggestion.grid().frameCount() <= DEFAULT_MAX_FRAMES)
                .min(Comparator.comparingDouble(GridSuggestion::distortion))
                .map(GridSuggestion::grid)
                .orElseGet(() -> frontier.getFirst().grid());
    }

    /**
     * Candidate grids, smallest first, each one strictly less distorted than every
     * smaller candidate.
     *
     * <p>Dominated grids are dropped: if a grid is both bigger and no better
     * shaped than one already on the list, there is no reason to ever pick it.
     * The result is always non-empty and always starts at 1x1.
     */
    public static List<GridSuggestion> suggest(int imageWidth, int imageHeight, int maxDimension) {
        if (imageWidth <= 0 || imageHeight <= 0) {
            throw new IllegalArgumentException("image dimensions must be positive");
        }

        double imageAspect = (double) imageWidth / (double) imageHeight;

        List<GridSuggestion> candidates = new ArrayList<>();
        for (int columns = 1; columns <= maxDimension; columns++) {
            for (int rows = 1; rows <= maxDimension; rows++) {
                GridSize grid = new GridSize(columns, rows);
                candidates.add(new GridSuggestion(grid, distortion(grid, imageAspect)));
            }
        }

        // Smallest first, and among equal sizes the better shaped one, so that the
        // frontier pass below keeps the best representative of each size.
        candidates.sort(Comparator
                .comparingInt((GridSuggestion suggestion) -> suggestion.grid().frameCount())
                .thenComparingDouble(GridSuggestion::distortion));

        List<GridSuggestion> frontier = new ArrayList<>();
        double bestSoFar = Double.MAX_VALUE;
        for (GridSuggestion suggestion : candidates) {
            if (suggestion.distortion() < bestSoFar) {
                frontier.add(suggestion);
                bestSoFar = suggestion.distortion();
            }
        }

        return frontier;
    }

    /**
     * Up to {@code count} suggestions, best first, starting from the recommended
     * grid rather than from 1x1.
     *
     * <p>Leading entries worse than {@link #ACCEPTABLE} are dropped, since showing
     * a wildly stretched 1x1 above the sensible answer just invites a misclick.
     * If nothing is acceptable the whole frontier is offered instead of nothing.
     */
    public static List<GridSuggestion> top(int imageWidth, int imageHeight, int count) {
        List<GridSuggestion> frontier = suggest(imageWidth, imageHeight, DEFAULT_MAX_DIMENSION);

        int start = 0;
        while (start < frontier.size() && frontier.get(start).distortion() > ACCEPTABLE) {
            start++;
        }
        if (start == frontier.size()) {
            start = 0;
        }

        List<GridSuggestion> offered = frontier.subList(start, frontier.size());
        return List.copyOf(offered.subList(0, Math.min(count, offered.size())));
    }

    /**
     * Relative aspect error, symmetric so that a grid twice as wide as the image
     * scores the same as one half as wide.
     */
    private static double distortion(GridSize grid, double imageAspect) {
        double ratio = grid.aspect() / imageAspect;
        return Math.abs(ratio >= 1.0 ? ratio - 1.0 : (1.0 / ratio) - 1.0);
    }
}
