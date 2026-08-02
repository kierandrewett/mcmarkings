package dev.kierandrewett.mcmarkings.doc;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out where a layer being dragged should actually land.
 *
 * <p>Kept free of any UI so it can be tested directly. Snapping is the sort of
 * thing that feels wrong long before anyone can say why, and chasing that through
 * a running game is miserable.
 *
 * <p>The tolerance is given in document pixels but should be derived from a fixed
 * number of screen pixels divided by the zoom, so the pull feels identical however
 * far in the canvas is zoomed. Snapping at a constant document distance would feel
 * sticky when zoomed out and useless when zoomed in.
 */
public final class Snapping {

    /** Which edge or centre of the moving layer produced a snap. */
    public enum Edge {
        START, CENTRE, END
    }

    /**
     * A line the canvas should draw to explain a snap.
     *
     * @param vertical    true for a vertical guide, which constrains x
     * @param position    where the guide sits, in document pixels
     * @param movingEdge  which part of the dragged layer landed on it
     */
    public record Guide(boolean vertical, int position, Edge movingEdge) {
    }

    /** Where a drag ended up, and the guides that explain it. */
    public record Result(Layer.Bounds bounds, List<Guide> guides) {

        public boolean snapped() {
            return !guides.isEmpty();
        }
    }

    private Snapping() {
    }

    /**
     * Snaps proposed bounds against the canvas, the frame grid and the other layers.
     *
     * <p>Each axis is resolved independently, and the closest candidate wins. Ties
     * go to whichever was collected first, which puts canvas and frame lines ahead
     * of other layers; those are the ones people are usually aiming for.
     *
     * @param proposed  where the drag would put the layer with no snapping
     * @param document  the canvas being edited
     * @param ignoreId  the layer being dragged, so it cannot snap to itself
     * @param tolerance maximum distance in document pixels that still snaps
     * @param enabled   false to pass the proposal straight through, for a modifier key
     */
    public static Result snap(Layer.Bounds proposed, Document document, String ignoreId,
            int tolerance, boolean enabled) {
        if (!enabled || tolerance <= 0) {
            return new Result(proposed, List.of());
        }

        List<Integer> verticalLines = candidateLines(document, ignoreId, true);
        List<Integer> horizontalLines = candidateLines(document, ignoreId, false);

        List<Guide> guides = new ArrayList<>(2);
        int x = resolve(proposed.x(), proposed.width(), verticalLines, tolerance, true, guides);
        int y = resolve(proposed.y(), proposed.height(), horizontalLines, tolerance, false, guides);

        return new Result(new Layer.Bounds(x, y, proposed.width(), proposed.height()), List.copyOf(guides));
    }

    /**
     * Finds the best snap for one axis and returns the adjusted origin.
     *
     * <p>All three of the moving layer's edges are tested against every candidate
     * line, because aligning a centre to a centre is just as common as aligning
     * edges, and only offering edges makes centring by hand a fiddle.
     */
    private static int resolve(int origin, int size, List<Integer> lines, int tolerance,
            boolean vertical, List<Guide> guides) {
        int bestOrigin = origin;
        int bestDistance = tolerance + 1;
        Integer bestLine = null;
        Edge bestEdge = null;

        for (int line : lines) {
            for (Edge edge : Edge.values()) {
                int edgePosition = switch (edge) {
                    case START -> origin;
                    case CENTRE -> origin + size / 2;
                    case END -> origin + size;
                };

                int distance = Math.abs(line - edgePosition);
                if (distance > tolerance || distance >= bestDistance) {
                    continue;
                }

                bestDistance = distance;
                bestLine = line;
                bestEdge = edge;
                bestOrigin = switch (edge) {
                    case START -> line;
                    case CENTRE -> line - size / 2;
                    case END -> line - size;
                };
            }
        }

        if (bestLine != null) {
            guides.add(new Guide(vertical, bestLine, bestEdge));
        }
        return bestOrigin;
    }

    /**
     * Every line a drag can land on, along one axis.
     *
     * <p>Shared by moving and resizing so the two cannot drift apart. A resize that
     * snapped to a different set of lines than a move would feel like two different
     * tools, and the answer to "why did it stick there" would depend on which one you
     * happened to be using.
     */
    private static List<Integer> candidateLines(Document document, String ignoreId, boolean vertical) {
        List<Integer> lines = vertical ? verticalLines(document) : horizontalLines(document);
        for (Layer layer : document.layers()) {
            if (layer.id().equals(ignoreId) || !layer.visible()) {
                continue;
            }
            Layer.Bounds other = layer.bounds();
            if (vertical) {
                lines.add(other.x());
                lines.add(other.centreX());
                lines.add(other.right());
            } else {
                lines.add(other.y());
                lines.add(other.centreY());
                lines.add(other.bottom());
            }
        }
        return lines;
    }

    /**
     * Snaps the edges a resize is actually moving, leaving the others alone.
     *
     * <p>{@link #snap} moves an origin and keeps the size, which is the right shape
     * for a drag and the wrong one for pulling an edge: applied to a resize it would
     * slide the whole layer sideways instead of stretching it. That is why resizing
     * went unsnapped, and unsnapped is why lining a plate up with the sign under it
     * meant nudging by hand until it looked right.
     *
     * <p>Only the grabbed edges move. Pulling the right handle cannot change where
     * the left edge is, however close a guide runs to it, because that would move the
     * layer while you were sizing it.
     *
     * <p>A minimum of one pixel each way, since a snap that collapses a layer to
     * nothing loses it: there is then no handle left to pull it back out by.
     */
    public static Result snapResize(Layer.Bounds proposed, Document document, String ignoreId,
            int tolerance, boolean enabled, boolean movingLeft, boolean movingRight,
            boolean movingTop, boolean movingBottom) {
        if (!enabled || tolerance <= 0) {
            return new Result(proposed, List.of());
        }

        List<Integer> verticalLines = candidateLines(document, ignoreId, true);
        List<Integer> horizontalLines = candidateLines(document, ignoreId, false);
        List<Guide> guides = new ArrayList<>(2);

        int left = proposed.x();
        int right = proposed.right();
        int top = proposed.y();
        int bottom = proposed.bottom();

        if (movingLeft) {
            left = snapEdge(left, verticalLines, tolerance, true, Edge.START, guides);
        }
        if (movingRight) {
            right = snapEdge(right, verticalLines, tolerance, true, Edge.END, guides);
        }
        if (movingTop) {
            top = snapEdge(top, horizontalLines, tolerance, false, Edge.START, guides);
        }
        if (movingBottom) {
            bottom = snapEdge(bottom, horizontalLines, tolerance, false, Edge.END, guides);
        }

        return new Result(
                new Layer.Bounds(left, top, Math.max(1, right - left), Math.max(1, bottom - top)),
                List.copyOf(guides));
    }

    /** The nearest candidate to one edge, or the edge unchanged. */
    private static int snapEdge(int position, List<Integer> lines, int tolerance,
            boolean vertical, Edge edge, List<Guide> guides) {
        int best = position;
        int bestDistance = tolerance + 1;

        for (int line : lines) {
            int distance = Math.abs(line - position);
            if (distance <= tolerance && distance < bestDistance) {
                bestDistance = distance;
                best = line;
            }
        }

        if (best != position || bestDistance <= tolerance) {
            guides.add(new Guide(vertical, best, edge));
        }
        return best;
    }

    /** Canvas edges, canvas centre, and every frame boundary. */
    private static List<Integer> verticalLines(Document document) {
        List<Integer> lines = new ArrayList<>();
        lines.add(0);
        lines.add(document.width() / 2);
        lines.add(document.width());
        for (int column = 1; column < document.grid().columns(); column++) {
            lines.add(column * document.pixelsPerFrame());
        }
        return lines;
    }

    private static List<Integer> horizontalLines(Document document) {
        List<Integer> lines = new ArrayList<>();
        lines.add(0);
        lines.add(document.height() / 2);
        lines.add(document.height());
        for (int row = 1; row < document.grid().rows(); row++) {
            lines.add(row * document.pixelsPerFrame());
        }
        return lines;
    }

    /** Tolerance in document pixels for a fixed feel on screen, whatever the zoom. */
    public static int toleranceForZoom(double zoom, int screenPixels) {
        if (zoom <= 0.0) {
            return screenPixels;
        }
        return Math.max(1, (int) Math.round(screenPixels / zoom));
    }

}
