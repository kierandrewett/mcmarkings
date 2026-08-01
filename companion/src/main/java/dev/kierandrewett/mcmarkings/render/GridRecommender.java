package dev.kierandrewett.mcmarkings.render;

import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.core.GridSuggestion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Works out how many item frames an image should occupy.
 *
 * <p>Maps are square, so a grid's shape is fixed by its frame counts. The score
 * is the relative aspect error between the grid and the image; the preferred
 * answer is the smallest grid whose error is imperceptible, because a wall of
 * frames is expensive and awkward to build.
 */
public final class GridRecommender {

    /** Beyond this the grid is bigger than anyone wants to place by hand. */
    public static final int DEFAULT_MAX_DIMENSION = 8;

    private GridRecommender() {
    }

    /** Best grid for an image, never null. */
    public static GridSize best(int imageWidth, int imageHeight) {
        return suggest(imageWidth, imageHeight, DEFAULT_MAX_DIMENSION).getFirst().grid();
    }

    /**
     * Candidate grids, best first.
     *
     * <p>Ordering prefers a comfortable fit over a tight one: any grid within
     * {@link GridSuggestion#COMFORTABLE} counts as visually exact, so among those
     * the smallest wins. Outside that band, least distortion wins.
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

        candidates.sort(Comparator
                .comparing((GridSuggestion suggestion) -> !suggestion.isComfortable())
                .thenComparingInt(suggestion -> suggestion.grid().frameCount())
                .thenComparingDouble(GridSuggestion::distortion));

        return candidates;
    }

    /** Top {@code count} distinct suggestions, best first. */
    public static List<GridSuggestion> top(int imageWidth, int imageHeight, int count) {
        return suggest(imageWidth, imageHeight, DEFAULT_MAX_DIMENSION).subList(0, count);
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
