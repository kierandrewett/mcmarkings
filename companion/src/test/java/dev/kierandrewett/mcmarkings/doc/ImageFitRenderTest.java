package dev.kierandrewett.mcmarkings.doc;

import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.render.FontRegistry;
import org.junit.jupiter.api.Assumptions;
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
 * The three ways an image can fill its layer, drawn side by side.
 *
 * <p>The feature render next door uses no images at all, so the one part of the
 * renderer that has to reason about two different aspect ratios was the part nothing
 * looked at. Fit modes are where that kind of bug lives: contain that crops, cover
 * that letterboxes, stretch that quietly preserves the ratio. Each of those renders
 * something, fills its box, and is wrong.
 *
 * <p>Deliberately a tall source in a wide box, so the three are obviously different
 * from each other. If two of them ever look the same, one of them is broken.
 */
class ImageFitRenderTest {

    /** Tall and narrow, so a wide box makes the difference between modes unmissable. */
    private static final String SOURCE = "give_way.png";

    private static Layer.Image at(String id, int y, Layer.Fit fit) {
        return new Layer.Image(id, fit.name().toLowerCase(java.util.Locale.ROOT),
                new Layer.Bounds(16, y, 480, 140), true, false, 1.0, Insets.NONE, SOURCE, fit);
    }

    private static Layer.Shape backing(String id, int y) {
        // Behind each image, so letterboxing and cropping are visible rather than
        // being the same transparent nothing.
        return new Layer.Shape(id, id, new Layer.Bounds(16, y, 480, 140), true, false, 1.0,
                Insets.NONE, Insets.NONE, 0xFF37474F, 0, 0xFF90A4AE, 2);
    }

    @Test
    @DisplayName("contain, cover and stretch each fill their box differently")
    void rendersEveryFitMode() throws IOException {
        Path source = Path.of("..", SOURCE);
        Assumptions.assumeTrue(Files.isRegularFile(source),
                SOURCE + " is not beside the mod, skipping");

        BufferedImage image = ImageIO.read(source.toFile());
        Document document = new Document("image fits", new GridSize(2, 2), 256,
                0xFF102027, List.of(
                        backing("bg-contain", 16), at("contain", 16, Layer.Fit.CONTAIN),
                        backing("bg-cover", 180), at("cover", 180, Layer.Fit.COVER),
                        backing("bg-stretch", 344), at("stretch", 344, Layer.Fit.STRETCH)));

        DocumentRenderer renderer = new DocumentRenderer(new FontRegistry(List.of()));
        BufferedImage rendered = renderer.render(document, path -> {
            if (!SOURCE.equals(path)) {
                throw new IOException("unexpected image: " + path);
            }
            return image;
        });

        Path directory = Path.of("build", "test-renders");
        Files.createDirectories(directory);
        ImageIO.write(rendered, "PNG", directory.resolve("image-fits.png").toFile());

        assertEquals(document.width(), rendered.getWidth());
        assertTrue(renderer.problems().isEmpty(), () -> "renderer complained: " + renderer.problems());

        // Measured on the image's own pixels, not on "anything that is not the
        // document background". The first version did the latter and every band came
        // back full, because the backing shape fills all three of them: the assertion
        // was measuring the thing behind the thing it cared about.
        double contain = inkOfBand(rendered, 16, 140);
        double cover = inkOfBand(rendered, 180, 140);
        assertTrue(cover > contain + 0.05,
                String.format("cover paints %.2f of its band and contain paints %.2f, which is "
                        + "too close for a tall image in a wide box", cover, contain));
    }

    /**
     * Fraction of a band covered by the source image's own paint.
     *
     * <p>The sign is white on transparent, and it sits on a slate backing, so near
     * white is the image and nothing else in the band is.
     */
    private static double inkOfBand(BufferedImage image, int top, int height) {
        long ink = 0;
        long total = 0;
        for (int y = top; y < Math.min(image.getHeight(), top + height); y++) {
            for (int x = 16; x < Math.min(image.getWidth(), 496); x++) {
                total++;
                int pixel = image.getRGB(x, y);
                int red = (pixel >> 16) & 0xFF;
                int green = (pixel >> 8) & 0xFF;
                int blue = pixel & 0xFF;
                if (red > 200 && green > 200 && blue > 200) {
                    ink++;
                }
            }
        }
        return total == 0 ? 0 : ink / (double) total;
    }
}
