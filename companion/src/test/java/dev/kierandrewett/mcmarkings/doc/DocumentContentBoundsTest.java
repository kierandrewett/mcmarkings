package dev.kierandrewett.mcmarkings.doc;

import dev.kierandrewett.mcmarkings.core.GridSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The box the visible layers sit inside.
 *
 * <p>Used to suggest a frame size that suits what someone has actually made, so
 * getting it wrong means a sign that comes out stretched on a wall. That is the sort
 * of mistake nobody notices in the editor and everybody notices afterwards.
 */
class DocumentContentBoundsTest {

    private static Layer.Image image(String id, int x, int y, int width, int height, boolean visible) {
        return new Layer.Image(id, id, new Layer.Bounds(x, y, width, height), visible, false,
                1.0, Insets.NONE, id + ".png", Layer.Fit.CONTAIN);
    }

    private static Document of(Layer... layers) {
        return new Document("doc", new GridSize(2, 1), 128, Document.TRANSPARENT, List.of(layers));
    }

    @Test
    @DisplayName("one layer is its own bounds")
    void singleLayer() {
        assertEquals(new Layer.Bounds(10, 20, 30, 40),
                of(image("a", 10, 20, 30, 40, true)).contentBounds().orElseThrow());
    }

    @Test
    @DisplayName("several layers give the box around all of them")
    void severalLayers() {
        Document document = of(
                image("a", 10, 10, 20, 20, true),
                image("b", 50, 5, 10, 60, true));

        assertEquals(new Layer.Bounds(10, 5, 50, 60), document.contentBounds().orElseThrow());
    }

    @Test
    @DisplayName("a hidden layer does not count")
    void hiddenLayersAreIgnored() {
        // Suggesting a frame size that suits something nobody can see would be an
        // answer to a question nobody asked.
        Document document = of(
                image("a", 0, 0, 10, 10, true),
                image("b", 0, 0, 500, 500, false));

        assertEquals(new Layer.Bounds(0, 0, 10, 10), document.contentBounds().orElseThrow());
    }

    @Test
    @DisplayName("a layer dragged off the edge still counts")
    void negativePositionsAreKept() {
        // Deliberately not clamped to the canvas. The usual reason to ask is to work
        // out what the canvas ought to be, so clamping to what it currently is would
        // answer with the thing being changed.
        Document document = of(image("a", -30, -10, 40, 20, true));

        Layer.Bounds bounds = document.contentBounds().orElseThrow();
        assertEquals(-30, bounds.x());
        assertEquals(-10, bounds.y());
        assertEquals(40, bounds.width());
    }

    @Test
    @DisplayName("nothing visible means no answer rather than a wrong one")
    void nothingVisible() {
        assertTrue(of().contentBounds().isEmpty(), "an empty document");
        assertTrue(of(image("a", 0, 0, 10, 10, false)).contentBounds().isEmpty(),
                "everything hidden");
    }

    @Test
    @DisplayName("a zero-sized layer does not produce a zero-sized box")
    void degenerateLayersDoNotProduceAnEmptyBox() {
        // A box of no width would be divided by when working out an aspect ratio.
        assertTrue(of(image("a", 5, 5, 0, 0, true)).contentBounds().isEmpty());
    }

    @Test
    @DisplayName("overlapping layers do not double count")
    void overlappingLayers() {
        Document document = of(
                image("a", 0, 0, 100, 100, true),
                image("b", 20, 20, 30, 30, true));

        assertEquals(new Layer.Bounds(0, 0, 100, 100), document.contentBounds().orElseThrow());
    }
}
