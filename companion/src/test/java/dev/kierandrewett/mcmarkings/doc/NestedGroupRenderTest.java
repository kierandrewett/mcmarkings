package dev.kierandrewett.mcmarkings.doc;

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
 * Groups inside groups, and what opacity does on the way down.
 *
 * <p>Groups render through a scratch buffer, which is the right way to make a
 * group's opacity apply to the whole group rather than to each child separately, and
 * it is also where the arithmetic goes wrong quietly. Applying a group's opacity to
 * its children as well as to the buffer squares it; forgetting the buffer makes an
 * overlapping pair of children show their seam through a half-transparent group.
 * Both produce a picture that looks plausible.
 *
 * <p>Checked by sampling rather than by eye, because this one has an exact answer:
 * a half-transparent group holding a half-transparent group holding a solid red
 * square is red at a quarter strength, and nothing else.
 */
class NestedGroupRenderTest {

    private static final int RED = 0xFFD32F2F;

    private static final int WHITE_BACKDROP = 0xFFFFFFFF;

    private static Layer.Shape square(String id, int x, int y, int size, int fill, double opacity) {
        return new Layer.Shape(id, id, new Layer.Bounds(x, y, size, size), true, false, opacity,
                Insets.NONE, Insets.NONE, fill, 0, 0x00000000, 0);
    }

    private static Layer.Group group(String id, int x, int y, int size, double opacity, Layer child) {
        return new Layer.Group(id, id, new Layer.Bounds(x, y, size, size), true, false, opacity,
                Insets.NONE, Insets.NONE, List.of(child));
    }

    @Test
    @DisplayName("nested group opacity multiplies once per level, not twice")
    void nestedOpacityCompoundsExactlyOnce() throws IOException {
        // Solid red, inside a half group, inside a half group, on white.
        // 0.5 * 0.5 = 0.25 of red over white, and any other answer is a bug with a
        // name: 0.5 means a level was skipped, 0.0625 means each was applied twice.
        Layer.Shape backdrop = square("backdrop", 0, 0, 256, WHITE_BACKDROP, 1.0);
        Layer.Group nested = group("outer", 32, 32, 128, 0.5,
                group("inner", 0, 0, 128, 0.5,
                        square("core", 0, 0, 128, RED, 1.0)));

        Document document = new Document("nested groups", new GridSize(2, 2), 128,
                Document.TRANSPARENT, List.of(backdrop, nested));

        DocumentRenderer renderer = new DocumentRenderer(new FontRegistry(List.of()));
        BufferedImage rendered = renderer.render(document, path -> {
            throw new IOException("no images expected: " + path);
        });

        Path directory = Path.of("build", "test-renders");
        Files.createDirectories(directory);
        ImageIO.write(rendered, "PNG", directory.resolve("nested-groups.png").toFile());

        assertTrue(renderer.problems().isEmpty(), () -> "renderer complained: " + renderer.problems());

        // Well inside the square, so antialiasing at an edge cannot explain a miss.
        int sampled = rendered.getRGB(96, 96);
        int expectedRed = mix(0xD3, 0xFF, 0.25);
        int expectedGreen = mix(0x2F, 0xFF, 0.25);

        assertEquals(expectedRed, (sampled >> 16) & 0xFF, 2,
                () -> "red channel is " + ((sampled >> 16) & 0xFF) + ", expected about " + expectedRed
                        + ". Half of a half is a quarter; anything else means a level was "
                        + "skipped or applied twice");
        assertEquals(expectedGreen, (sampled >> 8) & 0xFF, 2);
    }

    @Test
    @DisplayName("a group's offset moves its children once")
    void groupOffsetAppliesOnce() throws IOException {
        // The child sits at the group's origin, so it should land exactly at the
        // group's position. Applying the offset twice puts it at double, which looks
        // like a layout mistake rather than a renderer one.
        Layer.Group offset = group("group", 64, 64, 64, 1.0, square("core", 0, 0, 64, RED, 1.0));

        Document document = new Document("group offset", new GridSize(2, 2), 128,
                Document.TRANSPARENT, List.of(offset));

        BufferedImage rendered = new DocumentRenderer(new FontRegistry(List.of()))
                .render(document, path -> {
                    throw new IOException("no images expected: " + path);
                });

        assertEquals(0xFF, (rendered.getRGB(80, 80) >>> 24), "nothing drawn where the group is");
        assertEquals(0, (rendered.getRGB(160, 160) >>> 24),
                "something drawn at twice the offset, so it was applied more than once");
    }

    private static int mix(int foreground, int background, double alpha) {
        return (int) Math.round(foreground * alpha + background * (1 - alpha));
    }
}
