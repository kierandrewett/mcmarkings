package dev.kierandrewett.mcmarkings.gui.imgui.panel;

import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.doc.Document;
import dev.kierandrewett.mcmarkings.doc.Insets;
import dev.kierandrewett.mcmarkings.doc.Layer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The parts of the editor that are arithmetic rather than interface.
 *
 * <p>Most of the panel can only be judged with a mouse in a running game, but resize
 * handles, hit testing and colour conversion are not that: they are small sums that
 * are obvious when they are wrong on screen and horrible to chase from there. Those
 * are package-private so they can be pinned here instead.
 *
 * <p>Nothing here constructs the panel. It holds Minecraft's texture cache and an
 * ImGui context, neither of which exists in a unit test, so only the static helpers
 * are exercised.
 */
class EditorPanelTest {

    private static Document canvas(Layer... layers) {
        return new Document("test", new GridSize(2, 2), 128, Document.TRANSPARENT, List.of(layers));
    }

    private static Layer.Image image(String id, int x, int y, int width, int height, boolean visible) {
        return new Layer.Image(id, id, new Layer.Bounds(x, y, width, height), visible, false, 1.0,
                Insets.NONE, "a.png", Layer.Fit.CONTAIN);
    }

    @Test
    @DisplayName("dragging a corner moves two edges and leaves the opposite corner put")
    void cornerResizeMovesTwoEdges() {
        Layer.Bounds start = new Layer.Bounds(100, 100, 50, 40);

        Layer.Bounds pulled = EditorPanel.resized(start, EditorPanel.Handle.TOP_LEFT, -10, -20);

        assertEquals(90, pulled.x());
        assertEquals(80, pulled.y());
        assertEquals(60, pulled.width(), "the right edge should not have moved");
        assertEquals(60, pulled.height(), "the bottom edge should not have moved");
        assertEquals(150, pulled.right());
        assertEquals(140, pulled.bottom());
    }

    @Test
    @DisplayName("an edge handle only touches its own axis")
    void edgeResizeLeavesTheOtherAxis() {
        Layer.Bounds start = new Layer.Bounds(10, 20, 30, 40);

        Layer.Bounds wider = EditorPanel.resized(start, EditorPanel.Handle.RIGHT, 15, 999);

        assertEquals(45, wider.width());
        assertEquals(40, wider.height(), "a right handle must not change the height");
        assertEquals(20, wider.y());
    }

    @Test
    @DisplayName("dragging an edge past the far one stops at a pixel rather than inverting")
    void resizeNeverInverts() {
        Layer.Bounds start = new Layer.Bounds(100, 100, 50, 40);

        // Far more than the layer's own size, which is what happens the moment
        // someone flicks the mouse across the canvas.
        Layer.Bounds collapsed = EditorPanel.resized(start, EditorPanel.Handle.TOP_LEFT, 500, 500);

        assertEquals(1, collapsed.width());
        assertEquals(1, collapsed.height());
        assertEquals(149, collapsed.x(), "the right edge should still be where it was");
        assertEquals(139, collapsed.y());

        Layer.Bounds squashed = EditorPanel.resized(start, EditorPanel.Handle.BOTTOM_RIGHT, -500, -500);
        assertEquals(1, squashed.width());
        assertEquals(1, squashed.height());
        assertEquals(100, squashed.x(), "the left edge should not have moved");
    }

    @Test
    @DisplayName("a click picks the front-most layer under it")
    void hitTestPrefersTheFrontMost() {
        // Index 0 is the bottom of the stack, so "front" is the later one.
        Document document = canvas(
                image("back", 0, 0, 100, 100, true),
                image("front", 50, 50, 100, 100, true));

        assertEquals("front", EditorPanel.topmostAt(document, 60, 60).id());
        assertEquals("back", EditorPanel.topmostAt(document, 10, 10).id(), "only the back layer is here");
        assertNull(EditorPanel.topmostAt(document, 400, 400), "nothing is out there");
    }

    @Test
    @DisplayName("a hidden layer cannot be clicked, or it would block what is under it")
    void hitTestSkipsHiddenLayers() {
        Document document = canvas(
                image("back", 0, 0, 100, 100, true),
                image("hidden", 0, 0, 100, 100, false));

        assertEquals("back", EditorPanel.topmostAt(document, 10, 10).id());
    }

    @Test
    @DisplayName("a new layer is centred, and scaled down when it would not fit")
    void newLayersAreCentredAndFitted() {
        Document document = canvas();

        Layer.Bounds small = EditorPanel.centred(document, 100, 50);
        assertEquals(78, small.x(), "256 wide canvas, 100 wide layer");
        assertEquals(103, small.y());
        assertEquals(100, small.width(), "something that already fits is not scaled");

        // Twice the canvas in both directions, so it comes back at half size.
        Layer.Bounds large = EditorPanel.centred(document, 512, 512);
        assertEquals(256, large.width());
        assertEquals(256, large.height());
        assertEquals(0, large.x());
    }

    @Test
    @DisplayName("a very wide layer keeps its aspect ratio when it is fitted")
    void fittingKeepsTheAspectRatio() {
        Layer.Bounds fitted = EditorPanel.centred(canvas(), 1024, 128);

        assertEquals(256, fitted.width());
        assertEquals(32, fitted.height(), "eight to one going in, eight to one coming out");
    }

    @Test
    @DisplayName("a colour survives the trip through the picker's floats, alpha included")
    void coloursRoundTrip() {
        float[] channels = new float[4];

        for (int argb : List.of(0xFFFFFFFF, 0x00000000, 0x80336699, 0xFF0A0B0C)) {
            EditorPanel.toRgba(argb, channels);
            assertEquals(argb, EditorPanel.toArgb(channels),
                    () -> "lost precision on " + Integer.toHexString(argb));
        }
    }

    @Test
    @DisplayName("colour channels land in the order the picker expects")
    void coloursAreNotSwapped() {
        float[] channels = new float[4];

        // Pure red at half alpha. Getting this wrong swaps red and blue, which is
        // invisible in a round trip and very visible on the canvas.
        EditorPanel.toRgba(0x80FF0000, channels);

        assertEquals(1.0f, channels[0], 0.001f, "red");
        assertEquals(0.0f, channels[1], 0.001f, "green");
        assertEquals(0.0f, channels[2], 0.001f, "blue");
        assertEquals(0.502f, channels[3], 0.005f, "alpha");
    }

    @Test
    @DisplayName("a toolbar label is measured without its ImGui id")
    void labelsAreMeasuredWithoutTheirId() {
        assertEquals("L", EditorPanel.visibleLabel("L##align-left"));
        assertSame("Undo", EditorPanel.visibleLabel("Undo"), "an id-free label should not be copied");
    }
}
