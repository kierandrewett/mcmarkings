package dev.kierandrewett.mcmarkings.doc;

import dev.kierandrewett.mcmarkings.core.GridSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pulling an edge snaps that edge, and leaves the others where they were.
 *
 * <p>Resizing was unsnapped, on the grounds that the existing snap moves an origin
 * and keeps a size, which is the wrong shape for an edge drag. That was true of the
 * function and the wrong conclusion: the answer is an edge snap rather than no snap.
 * Reported by someone using it, who had been lining plates up by eye.
 */
class ResizeSnapTest {

    private static Layer.Shape shape(String id, int x, int y, int w, int h) {
        return new Layer.Shape(id, id, new Layer.Bounds(x, y, w, h), true, false, 1.0,
                Insets.NONE, Insets.NONE, 0xFF334455, 0, 0, 0);
    }

    private static Document withNeighbour() {
        return new Document("resize", new GridSize(4, 2), 128, Document.TRANSPARENT,
                List.of(shape("other", 300, 40, 100, 60), shape("dragged", 20, 40, 100, 60)));
    }

    @Test
    @DisplayName("a right edge pulled near a neighbour lands on it")
    void theRightEdgeSnapsToANeighbour() {
        Document document = withNeighbour();
        // Pulled to 296, four short of the neighbour's left edge at 300.
        Layer.Bounds proposed = new Layer.Bounds(20, 40, 276, 60);

        Snapping.Result result = Snapping.snapResize(proposed, document, "dragged",
                8, true, false, true, false, false);

        assertEquals(300, result.bounds().right(), "the right edge should have landed on 300");
        assertEquals(20, result.bounds().x(), "the left edge must not move when pulling the right one");
        assertTrue(result.snapped(), "a snap with no guide leaves nothing on screen explaining it");
    }

    /**
     * The half that would make this feel wrong. A snap that moved the edge you were
     * not holding would slide the layer while you were sizing it.
     */
    @Test
    @DisplayName("the edge that is not being pulled never moves")
    void theOtherEdgeIsLeftAlone() {
        Document document = withNeighbour();
        // The left edge sits at 20 and the canvas edge at 0 is well within tolerance.
        Layer.Bounds proposed = new Layer.Bounds(20, 40, 276, 60);

        Snapping.Result result = Snapping.snapResize(proposed, document, "dragged",
                40, true, false, true, false, false);

        assertEquals(20, result.bounds().x(),
                "the left edge snapped to the canvas while the right handle was being dragged");
    }

    @Test
    @DisplayName("a corner snaps both of its edges")
    void aCornerSnapsBoth() {
        Document document = withNeighbour();
        Layer.Bounds proposed = new Layer.Bounds(20, 40, 278, 84);

        Snapping.Result result = Snapping.snapResize(proposed, document, "dragged",
                8, true, false, true, false, true);

        assertEquals(300, result.bounds().right());
        assertEquals(128, result.bounds().bottom(), "the bottom should have found the frame line at 128");
    }

    /**
     * Holding the modifier suspends it, the same as it does for a move. A snap you
     * cannot turn off is worse than none when you want a specific number.
     */
    @Test
    @DisplayName("snapping off means the edge goes exactly where it was put")
    void snappingCanBeSuspended() {
        Document document = withNeighbour();
        Layer.Bounds proposed = new Layer.Bounds(20, 40, 276, 60);

        Snapping.Result result = Snapping.snapResize(proposed, document, "dragged",
                8, false, false, true, false, false);

        assertEquals(proposed, result.bounds());
        assertEquals(List.of(), result.guides());
    }

    /**
     * A layer cannot be snapped out of existence. At zero width there is no handle
     * left to pull it back out by, so the layer is simply gone.
     */
    @Test
    @DisplayName("a snap never collapses a layer to nothing")
    void aLayerKeepsAtLeastOnePixel() {
        Document document = new Document("tiny", new GridSize(1, 1), 128, Document.TRANSPARENT,
                List.of(shape("dragged", 60, 60, 4, 4)));
        Layer.Bounds proposed = new Layer.Bounds(60, 60, 2, 2);

        Snapping.Result result = Snapping.snapResize(proposed, document, "dragged",
                80, true, true, true, true, true);

        assertTrue(result.bounds().width() >= 1, "width collapsed to " + result.bounds().width());
        assertTrue(result.bounds().height() >= 1, "height collapsed to " + result.bounds().height());
    }
}
