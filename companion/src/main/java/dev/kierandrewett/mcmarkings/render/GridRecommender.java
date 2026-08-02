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

    /**
     * How much of the frames a grid asks for the sign has to actually cover.
     *
     * <p>Publishing fits the image to the grid keeping its shape and centring it, so
     * a grid whose proportions differ from the image's does not squash it, it leaves
     * transparent space. On a wall that space is invisible, which changes what the
     * choice is about: not how wrong the sign will look, but how many item frames
     * you place and do not use.
     *
     * <p>So the recommendation is the fewest frames that still put the sign on most
     * of them. Just under half, which for a 1601x445 direction sign is 2x1 rather
     * than 4x1: two frames with the sign across the middle of them instead of four
     * with the sign filling them. Fewer frames to place, and nobody standing in front
     * of it can tell the difference.
     */
    public static final double MINIMUM_COVERAGE = 0.45;

    /** Fallback ceiling when nothing reaches {@link #ACCEPTABLE}. */
    public static final int DEFAULT_MAX_FRAMES = 16;

    private GridRecommender() {
    }

    /**
     * Best grid when the server will do the fitting, never null.
     *
     * <p>The difference from {@link #best} is who fits the image. Publishing squares
     * the PNG up to the grid itself, keeping the shape and centring it, so any grid
     * is safe there and the only cost of a small one is frames placed with nothing on
     * them. Placing an image straight from a repository does not: the mod hands
     * ImageFrame a URL and a grid, and what happens between the two is the server's
     * business.
     *
     * <p>So this one goes back to matching the shape. It is the rule the recommender
     * had before, and changing that rule everywhere was a mistake on this path: it
     * could hand the server a 2x1 for a 4:1 image, and if the server stretches rather
     * than pads then the sign on the wall is squashed to half its width with nothing
     * here able to tell.
     *
     * <p>Which of the two ImageFrame does is not written down anywhere I can check
     * from here, so this picks the grid where it does not matter.
     */
    public static GridSize bestMatchingShape(int imageWidth, int imageHeight) {
        List<GridSuggestion> frontier = suggest(imageWidth, imageHeight, DEFAULT_MAX_DIMENSION);

        for (GridSuggestion suggestion : frontier) {
            if (suggestion.distortion() <= ACCEPTABLE) {
                return suggestion.grid();
            }
        }

        return frontier.stream()
                .filter(suggestion -> suggestion.grid().frameCount() <= DEFAULT_MAX_FRAMES)
                .min(Comparator.comparingDouble(GridSuggestion::distortion))
                .map(GridSuggestion::grid)
                .orElseGet(() -> frontier.getFirst().grid());
    }

    /** Best grid for an image the mod fits itself, never null. */
    public static GridSize best(int imageWidth, int imageHeight) {
        List<GridSuggestion> frontier = suggest(imageWidth, imageHeight, DEFAULT_MAX_DIMENSION);

        // Smallest first, so the first grid the sign covers enough of wins. This used
        // to look for the first grid the sign would not be visibly squashed on, which
        // was the right question while publishing left the fitting to the server.
        // Publishing keeps the shape and centres it now, so nothing is ever squashed
        // and the cost of a mismatch is frames placed and not used.
        double imageAspect = imageWidth / (double) Math.max(1, imageHeight);
        for (GridSuggestion suggestion : frontier) {
            if (GridSuggestion.coveragePercent(suggestion.grid(), imageAspect)
                    >= MINIMUM_COVERAGE * 100.0) {
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
        return offered(imageWidth, imageHeight, count, false);
    }

    /**
     * The same list, for a path where the server does the fitting.
     *
     * <p>Pairs with {@link #bestMatchingShape}. Recommending one rule and offering
     * another is worse than either on its own: the button the browser lands on would
     * be a grid the image fills and every alternative beside it would not, with
     * nothing saying why they differ.
     *
     * <p>Found by listing every caller of the two rules rather than by tripping over
     * it, which is the third of these in a row and the first I did not find by
     * accident.
     */
    public static List<GridSuggestion> topMatchingShape(int imageWidth, int imageHeight, int count) {
        return offered(imageWidth, imageHeight, count, true);
    }

    private static List<GridSuggestion> offered(int imageWidth, int imageHeight, int count,
            boolean matchShape) {
        List<GridSuggestion> frontier = suggest(imageWidth, imageHeight, DEFAULT_MAX_DIMENSION);

        // Starts where best() would stop, so the first button offered is the one it
        // recommends and the rest are the larger grids someone might want instead.
        // Anything smaller is left out: it is offered as a choice, and a grid the sign
        // sits on a third of is not one.
        double imageAspect = imageWidth / (double) Math.max(1, imageHeight);
        int start = 0;
        while (start < frontier.size() && !goodEnough(frontier.get(start), imageAspect, matchShape)) {
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
    /** Whether a candidate clears whichever bar this path is judged against. */
    private static boolean goodEnough(GridSuggestion suggestion, double imageAspect,
            boolean matchShape) {
        return matchShape
                ? suggestion.distortion() <= ACCEPTABLE
                : GridSuggestion.coveragePercent(suggestion.grid(), imageAspect)
                        >= MINIMUM_COVERAGE * 100.0;
    }

    private static double distortion(GridSize grid, double imageAspect) {
        double ratio = grid.aspect() / imageAspect;
        return Math.abs(ratio >= 1.0 ? ratio - 1.0 : (1.0 / ratio) - 1.0);
    }
}
