package dev.kierandrewett.mcmarkings.doc;

import dev.kierandrewett.mcmarkings.core.GridSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Snapping, alignment and document ordering.
 *
 * <p>All of this is judged by feel in the running editor, where being subtly wrong
 * is obvious but hard to describe and miserable to chase. Pinning the arithmetic
 * here is much cheaper than debugging it through a game.
 */
class EditorGeometryTest {

    /** 2x2 frames at 128px, so the canvas is 256x256 with a cell line down the middle. */
    private static Document canvas(Layer... layers) {
        return new Document("test", new GridSize(2, 2), 128, Document.TRANSPARENT, List.of(layers));
    }

    private static Layer.Image image(String id, int x, int y, int width, int height) {
        return new Layer.Image(id, id, new Layer.Bounds(x, y, width, height), true, false, 1.0,
                Insets.NONE, "a.png", Layer.Fit.CONTAIN);
    }

    private static Layer.Image locked(String id, int x, int y, int width, int height) {
        return new Layer.Image(id, id, new Layer.Bounds(x, y, width, height), true, true, 1.0,
                Insets.NONE, "a.png", Layer.Fit.CONTAIN);
    }

    @Test
    @DisplayName("a near miss snaps to the canvas edge")
    void snapsToCanvasEdge() {
        Snapping.Result result = Snapping.snap(new Layer.Bounds(3, 40, 50, 50), canvas(), "moving", 8, true);

        assertEquals(0, result.bounds().x());
        assertEquals(40, result.bounds().y(), "the other axis should be left alone");
        assertTrue(result.snapped());
    }

    @Test
    @DisplayName("a layer snaps to a frame boundary, which is what makes a wall line up")
    void snapsToFrameBoundary() {
        // The cell line for a 2x2 grid at 128px sits at x=128.
        Snapping.Result result = Snapping.snap(new Layer.Bounds(124, 10, 40, 40), canvas(), "moving", 8, true);

        assertEquals(128, result.bounds().x());
    }

    @Test
    @DisplayName("centres snap to centres, not just edges to edges")
    void snapsCentreToCentre() {
        // Deliberately away from x=128, where the canvas centre and the frame
        // boundary both sit. Putting the fixture there lets an edge-to-frame snap
        // win on distance and proves nothing about centres.
        Document document = canvas(image("other", 40, 0, 40, 40));

        // Moving layer is 20 wide, so its centre is at x+10. Aiming that at the
        // other layer's centre of 60 means an origin of 50.
        Snapping.Result result = Snapping.snap(new Layer.Bounds(47, 60, 20, 20), document, "moving", 8, true);

        assertEquals(50, result.bounds().x());
        assertEquals(Snapping.Edge.CENTRE, result.guides().getFirst().movingEdge());
    }

    @Test
    @DisplayName("a layer never snaps to itself")
    void doesNotSnapToItself() {
        Document document = canvas(image("moving", 50, 50, 20, 20));

        // 60 is well away from any canvas or frame line, so without the exclusion
        // the layer's own edge at 50 would drag it back.
        Snapping.Result result = Snapping.snap(new Layer.Bounds(53, 60, 20, 20), document, "moving", 8, true);

        assertEquals(53, result.bounds().x(), "its own previous edge should not attract it");
    }

    @Test
    @DisplayName("hidden layers do not attract a snap")
    void hiddenLayersAreIgnored() {
        Layer.Image hidden = new Layer.Image("hidden", "hidden", new Layer.Bounds(100, 0, 40, 40),
                false, false, 1.0, Insets.NONE, "a.png", Layer.Fit.CONTAIN);

        Snapping.Result result = Snapping.snap(new Layer.Bounds(97, 60, 20, 20), canvas(hidden), "moving", 8, true);

        assertEquals(97, result.bounds().x());
    }

    @Test
    @DisplayName("nothing within tolerance leaves the drag untouched")
    void farFromAnythingDoesNotSnap() {
        Snapping.Result result = Snapping.snap(new Layer.Bounds(60, 60, 20, 20), canvas(), "moving", 4, true);

        assertEquals(60, result.bounds().x());
        assertEquals(60, result.bounds().y());
        assertFalse(result.snapped());
    }

    @Test
    @DisplayName("snapping can be suspended, for the modifier key")
    void snappingCanBeDisabled() {
        Snapping.Result result = Snapping.snap(new Layer.Bounds(3, 3, 20, 20), canvas(), "moving", 8, false);

        assertEquals(3, result.bounds().x());
        assertEquals(3, result.bounds().y());
        assertFalse(result.snapped());
    }

    @Test
    @DisplayName("snapping never changes a layer's size")
    void sizeIsPreserved() {
        Snapping.Result result = Snapping.snap(new Layer.Bounds(3, 3, 37, 91), canvas(), "moving", 8, true);

        assertEquals(37, result.bounds().width());
        assertEquals(91, result.bounds().height());
    }

    @Test
    @DisplayName("tolerance follows the zoom so the pull feels the same throughout")
    void toleranceTracksZoom() {
        // Zoomed in, the same screen distance is fewer document pixels.
        assertEquals(4, Snapping.toleranceForZoom(2.0, 8));
        assertEquals(8, Snapping.toleranceForZoom(1.0, 8));
        assertEquals(16, Snapping.toleranceForZoom(0.5, 8));
        assertTrue(Snapping.toleranceForZoom(1000.0, 8) >= 1, "tolerance must never reach zero");
    }

    @Test
    @DisplayName("one selected layer aligns to the canvas, since aligning to itself is a no-op")
    void singleLayerAlignsToCanvas() {
        Document document = canvas(image("a", 10, 10, 50, 50));

        Document left = Alignment.alignHorizontally(document, List.of("a"), Alignment.Horizontal.LEFT);
        assertEquals(0, left.byId("a").orElseThrow().bounds().x());

        Document centre = Alignment.alignHorizontally(document, List.of("a"), Alignment.Horizontal.CENTRE);
        assertEquals(103, centre.byId("a").orElseThrow().bounds().x(), "256 wide canvas, 50 wide layer");

        Document right = Alignment.alignHorizontally(document, List.of("a"), Alignment.Horizontal.RIGHT);
        assertEquals(206, right.byId("a").orElseThrow().bounds().x());
    }

    @Test
    @DisplayName("several layers align to the box enclosing them, not to the canvas")
    void severalLayersAlignToTheirOwnBox() {
        Document document = canvas(image("a", 20, 0, 30, 10), image("b", 90, 40, 30, 10));

        Document aligned = Alignment.alignHorizontally(document, List.of("a", "b"), Alignment.Horizontal.LEFT);

        assertEquals(20, aligned.byId("a").orElseThrow().bounds().x());
        assertEquals(20, aligned.byId("b").orElseThrow().bounds().x(), "should meet the leftmost, not the canvas");
    }

    @Test
    @DisplayName("vertical alignment works the same way and leaves x alone")
    void verticalAlignmentLeavesTheOtherAxis() {
        Document document = canvas(image("a", 20, 5, 30, 10), image("b", 90, 40, 30, 20));

        Document aligned = Alignment.alignVertically(document, List.of("a", "b"), Alignment.Vertical.TOP);

        assertEquals(5, aligned.byId("a").orElseThrow().bounds().y());
        assertEquals(5, aligned.byId("b").orElseThrow().bounds().y());
        assertEquals(20, aligned.byId("a").orElseThrow().bounds().x(), "x must not move");
        assertEquals(90, aligned.byId("b").orElseThrow().bounds().x(), "x must not move");
    }

    @Test
    @DisplayName("a locked layer is not moved by an alignment")
    void lockedLayersAreLeftAlone() {
        Document document = canvas(image("a", 20, 0, 30, 10), locked("b", 90, 40, 30, 10));

        Document aligned = Alignment.alignHorizontally(document, List.of("a", "b"), Alignment.Horizontal.LEFT);

        assertEquals(90, aligned.byId("b").orElseThrow().bounds().x(), "locked means locked");
    }

    @Test
    @DisplayName("distributing equalises the gaps and leaves the outermost two put")
    void distributeEqualisesGaps() {
        // Deliberately different widths: equal gaps and equal centres are not the
        // same arrangement, and gaps are what reads as evenly spaced.
        Document document = canvas(
                image("a", 0, 0, 20, 10),
                image("b", 60, 0, 40, 10),
                image("c", 180, 0, 20, 10));

        Document spread = Alignment.distributeHorizontally(document, List.of("a", "b", "c"));

        Layer.Bounds a = spread.byId("a").orElseThrow().bounds();
        Layer.Bounds b = spread.byId("b").orElseThrow().bounds();
        Layer.Bounds c = spread.byId("c").orElseThrow().bounds();

        assertEquals(0, a.x(), "the first should not move");
        assertEquals(180, c.x(), "the last should not move");
        assertEquals(b.x() - a.right(), c.x() - b.right(), "gaps should match");
    }

    @Test
    @DisplayName("fewer than three layers cannot be distributed")
    void distributingTwoLayersDoesNothing() {
        Document document = canvas(image("a", 0, 0, 20, 10), image("b", 100, 0, 20, 10));

        Document spread = Alignment.distributeHorizontally(document, List.of("a", "b"));

        assertEquals(0, spread.byId("a").orElseThrow().bounds().x());
        assertEquals(100, spread.byId("b").orElseThrow().bounds().x());
    }

    @Test
    @DisplayName("layer order is z order, and reordering clamps rather than failing")
    void reorderingMovesThroughTheStack() {
        Document document = canvas(image("a", 0, 0, 10, 10), image("b", 0, 0, 10, 10),
                image("c", 0, 0, 10, 10));

        assertEquals(0, document.indexOf("a"), "index 0 is the bottom of the stack");

        Document raised = document.reorder("a", 1);
        assertEquals(1, raised.indexOf("a"));

        // Buttons are easier to leave enabled than to disable at the ends.
        Document clamped = document.reorder("a", -5);
        assertEquals(0, clamped.indexOf("a"));
        assertEquals(2, document.reorder("c", 5).indexOf("c"));
    }

    @Test
    @DisplayName("the canvas is sized from the frame grid, not from raw pixels")
    void canvasSizeComesFromTheGrid() {
        Document document = new Document("t", new GridSize(3, 2), 256, Document.TRANSPARENT, List.of());

        assertEquals(768, document.width());
        assertEquals(512, document.height());
        assertEquals(2.0, document.frameScale(), 1.0e-9);
    }

    @Test
    @DisplayName("changing the grid does not move the layers")
    void resizingTheCanvasLeavesLayersPut() {
        // Rescaling silently would ruin a composition the moment someone tried a
        // different grid, so it is deliberately a separate operation.
        Document document = canvas(image("a", 30, 40, 10, 10));

        Document resized = document.withGrid(new GridSize(4, 4), 128);

        assertEquals(30, resized.byId("a").orElseThrow().bounds().x());
        assertEquals(40, resized.byId("a").orElseThrow().bounds().y());
    }
}
