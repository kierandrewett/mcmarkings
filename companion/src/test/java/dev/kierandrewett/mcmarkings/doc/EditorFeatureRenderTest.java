package dev.kierandrewett.mcmarkings.doc;

import dev.kierandrewett.mcmarkings.Pixels;
import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.render.FontRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything the editor can put on a canvas, drawn at once and written out to look at.
 *
 * <p>The other renderer tests check one thing each and assert it is not blank. That
 * catches a layer kind failing outright and nothing else: a border drawn inside its
 * shape instead of on it, padding applied twice, text sitting a few pixels off its
 * baseline, a group's children ignoring the group's offset. Every one of those
 * passes every assertion in this suite and is obvious in a second to anyone looking
 * at the picture.
 *
 * <p>So this renders one document using every feature the properties panel exposes
 * and leaves it in build/test-renders. The assertions here are a floor, not the
 * point. The point is that there is something to look at, and that looking has
 * already caught things in this project that a green suite did not.
 */
class EditorFeatureRenderTest {

    private static final int WIDTH = 512;

    private static Layer.Text text(String id, String content, int y, double tracking, double scale,
            Layer.HorizontalAlign align, int colour) {
        // Inset from the plate rather than flush to it. Text is a sibling of the
        // shape, not a child, so nothing clips it: flush bounds put the first and last
        // letters over the border and the reference image read like a bug.
        return new Layer.Text(id, id, new Layer.Bounds(44, y, WIDTH - 88, 44), true, false, 1.0,
                Insets.NONE, content, FontRegistry.DEFAULT_FONT, 26.0, colour,
                align, Layer.VerticalAlign.MIDDLE, 2.0, tracking, scale);
    }

    private static Document everyFeature() {
        Layer.Shape backdrop = new Layer.Shape("backdrop", "Backdrop",
                new Layer.Bounds(0, 0, WIDTH, 512), true, false, 1.0, Insets.NONE, Insets.NONE,
                0xFF1B5E20, 0, 0x00000000, 0);

        // A rounded, bordered panel: the plate primitive, and the one most likely to
        // draw its border a pixel inside or outside where it belongs.
        Layer.Shape plate = new Layer.Shape("plate", "Plate",
                new Layer.Bounds(24, 24, WIDTH - 48, 150), true, false, 1.0, Insets.NONE, Insets.all(12),
                0xFFFFFFFF, 18, 0xFF212121, 6);

        // Dark, because they sit on the white plate. The first version of this
        // fixture drew them white on white: every assertion passed, coverage was well
        // over the floor, and three of the nine layers were invisible in the picture.
        // Exactly the failure this test exists to make visible, produced by the test.
        Layer.Text heading = text("heading", "CENTRED", 44, 0.0, 1.0,
                Layer.HorizontalAlign.CENTRE, 0xFF212121);
        Layer.Text tracked = text("tracked", "TRACKING", 100, 6.0, 1.0,
                Layer.HorizontalAlign.LEFT, 0xFF1B5E20);
        Layer.Text tall = text("tall", "TALL", 100, 0.0, 1.6,
                Layer.HorizontalAlign.RIGHT, 0xFFD32F2F);

        // Two lines in one layer, which is a different code path from two layers.
        Layer.Text wrapped = new Layer.Text("wrapped", "wrapped",
                new Layer.Bounds(24, 190, WIDTH - 48, 90), true, false, 1.0, Insets.NONE,
                "TWO LINES\nIN ONE LAYER", FontRegistry.DEFAULT_FONT, 24.0, 0xFFFFFF00,
                Layer.HorizontalAlign.CENTRE, Layer.VerticalAlign.TOP, 8.0, 0.0, 1.0);

        // A group with padding, holding a shape that fills it. If the group's offset
        // or padding is applied twice this lands somewhere obviously wrong.
        Layer.Shape inner = new Layer.Shape("inner", "Inner",
                new Layer.Bounds(0, 0, 200, 60), true, false, 1.0, Insets.NONE, Insets.NONE,
                0xFFD32F2F, 8, 0xFFFFFFFF, 3);
        Layer.Group group = new Layer.Group("group", "Group",
                new Layer.Bounds(24, 300, 220, 80), true, false, 1.0, Insets.NONE, Insets.all(10),
                List.of(inner));

        // Half transparent, over the backdrop, so opacity is visible rather than assumed.
        Layer.Shape faded = new Layer.Shape("faded", "Faded",
                new Layer.Bounds(280, 300, 200, 80), true, false, 0.4, Insets.NONE, Insets.NONE,
                0xFF000000, 0, 0xFFFFFFFF, 3);

        Layer.Text footer = text("footer", "BOTTOM ALIGNED", 420, 0.0, 1.0,
                Layer.HorizontalAlign.CENTRE, 0xFFFFFFFF);

        return new Document("every feature", new GridSize(2, 2), 256, Document.TRANSPARENT,
                List.of(backdrop, plate, heading, tracked, tall, wrapped, group, faded, footer));
    }

    @Test
    @DisplayName("every layer feature renders, and the result is written out to look at")
    void rendersEveryFeature() throws IOException {
        Document document = everyFeature();
        DocumentRenderer renderer = new DocumentRenderer(new FontRegistry(List.of()));

        BufferedImage rendered = renderer.render(document, path -> {
            throw new IOException("this document should need no images: " + path);
        });

        Path directory = Path.of("build", "test-renders");
        Files.createDirectories(directory);
        ImageIO.write(rendered, "PNG", directory.resolve("editor-features.png").toFile());

        assertEquals(document.width(), rendered.getWidth());
        assertEquals(document.height(), rendered.getHeight());
        assertTrue(renderer.problems().isEmpty(), () -> "renderer complained: " + renderer.problems());

        // A floor, not the point: enough paint that a blank or nearly blank canvas
        // fails without anyone having to open the file.
        assertTrue(Pixels.coverage(rendered) > 0.5,
                "the document covers most of its canvas, so something did not draw");
    }

}
