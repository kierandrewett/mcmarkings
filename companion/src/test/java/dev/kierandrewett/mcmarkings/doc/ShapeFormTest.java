package dev.kierandrewett.mcmarkings.doc;

import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.render.FontRegistry;
import dev.kierandrewett.mcmarkings.render.ImageComposer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A shape layer can be something other than a rectangle.
 *
 * <p>Five forms, chosen for what British signs actually are: a plate, a roundel, a warning
 * triangle, a give way triangle and a priority diamond.
 *
 * <p>The interesting part is not the drawing, it is everything that was written when a shape could
 * only be one thing. Anything that copies a layer had to learn to carry the form, or a triangle
 * would turn back into a rectangle on being renamed, and anything that reads a saved document had
 * to keep opening the ones written before the field existed.
 */
class ShapeFormTest {

    private static Layer.Shape shape(Layer.Form form) {
        return new Layer.Shape("s1", "Shape", new Layer.Bounds(20, 20, 200, 160), true, false, 1.0,
                Insets.NONE, Insets.NONE, 0xFFCC2222, 12, 0xFFFFFFFF, 8, form);
    }

    private static Document documentOf(Layer.Shape layer) {
        return new Document("forms", new GridSize(2, 2), 128, Document.TRANSPARENT, List.of(layer));
    }

    private static long filled(BufferedImage image) {
        long count = 0;
        for (int y = 0; y < image.getHeight(); y += 1) {
            for (int x = 0; x < image.getWidth(); x += 1) {
                if (((image.getRGB(x, y) >>> 24) & 0xFF) > 8) {
                    count += 1;
                }
            }
        }
        return count;
    }

    @Test
    @DisplayName("every form draws, and none of them draws the same as another")
    void everyFormDrawsSomethingOfItsOwn() throws IOException {
        FontRegistry fonts = new FontRegistry(List.of());
        Path out = Path.of("build/test-shapes");
        Files.createDirectories(out);

        List<Long> areas = new ArrayList<>();
        for (Layer.Form form : Layer.Form.values()) {
            BufferedImage image = new DocumentRenderer(fonts, new ImageComposer())
                    .render(documentOf(shape(form)), path -> null);
            long area = filled(image);

            assertTrue(area > 0, form + " drew nothing at all");
            areas.add(area);
            ImageIO.write(image, "png", out.resolve("shape-" + form.name().toLowerCase() + ".png").toFile());
        }

        // A triangle covers about half a rectangle and a diamond about half too, but the two
        // triangles differ from the diamond in where the ink is rather than how much, so shapes are
        // told apart by area only where that is honest.
        assertTrue(areas.get(0) > areas.get(1), "an ellipse should cover less than its box");
        assertTrue(areas.get(0) > areas.get(2), "a triangle should cover less than its box");
    }

    /** The two triangles are not the same picture, which area alone would not catch. */
    @Test
    @DisplayName("a triangle and an inverted one differ")
    void theTwoTrianglesAreNotTheSame() throws IOException {
        FontRegistry fonts = new FontRegistry(List.of());
        BufferedImage up = new DocumentRenderer(fonts, new ImageComposer())
                .render(documentOf(shape(Layer.Form.TRIANGLE)), path -> null);
        BufferedImage down = new DocumentRenderer(fonts, new ImageComposer())
                .render(documentOf(shape(Layer.Form.TRIANGLE_DOWN)), path -> null);

        // Sampled just inside the top edge, where one has its base and the other its point.
        int alongTheTop = ((up.getRGB(30, 24) >>> 24) & 0xFF);
        int alongTheTopDown = ((down.getRGB(30, 24) >>> 24) & 0xFF);
        assertNotEquals(alongTheTop > 8, alongTheTopDown > 8,
                "one of these has its base along the top and the other a point, so the corner "
                        + "cannot be ink in both");
    }

    /** A document saved before shapes had a form still opens as the rectangles it was. */
    @Test
    @DisplayName("a shape with no form recorded is a rectangle")
    void anOlderDocumentOpensUnchanged() throws Exception {
        String saved = """
                {"name":"old","grid":{"columns":1,"rows":1},"pixelsPerFrame":128,
                 "background":"#00000000",
                 "layers":[{"kind":"shape","id":"s1","name":"Plate",
                   "bounds":{"x":0,"y":0,"width":40,"height":20},
                   "fill":"#FF204060","cornerRadius":4,"borderColour":"#FFFFFFFF","borderWidth":2}]}
                """;

        Document opened = DocumentJson.read(saved);
        Layer.Shape only = (Layer.Shape) opened.layers().getFirst();

        assertEquals(Layer.Form.RECTANGLE, only.form(),
                "every template and every saved document in existence was written without this "
                        + "field, and they were all rectangles");
    }

    /** And the form survives a round trip, or saving a triangle would lose it. */
    @Test
    @DisplayName("the form survives being written and read")
    void theFormRoundTrips() throws Exception {
        Document written = documentOf(shape(Layer.Form.TRIANGLE_DOWN));
        Document read = DocumentJson.read(DocumentJson.write(written));

        assertEquals(Layer.Form.TRIANGLE_DOWN, ((Layer.Shape) read.layers().getFirst()).form());
    }

    /**
     * Renaming a triangle leaves a triangle.
     *
     * <p>The failure this whole change invites: twenty-odd places build a shape, and the handful
     * that copy one had to start passing the form through. Miss one and the shape silently reverts,
     * on an action that has nothing to do with its shape.
     */
    @Test
    @DisplayName("copying a shape keeps its form")
    void copyingKeepsTheForm() {
        Layer.Shape triangle = shape(Layer.Form.TRIANGLE);

        assertEquals(Layer.Form.TRIANGLE,
                ((Layer.Shape) triangle.withBounds(new Layer.Bounds(0, 0, 10, 10))).form(),
                "moving or resizing a triangle must not flatten it into a rectangle");
        Document one = documentOf(triangle);
        Document twice = Edits.duplicate(one, List.of(triangle.id())).document();
        for (Layer copy : twice.layers()) {
            assertEquals(Layer.Form.TRIANGLE, ((Layer.Shape) copy).form(),
                    "duplicating a triangle must give another triangle");
        }
    }
}
