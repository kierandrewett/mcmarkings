package dev.kierandrewett.mcmarkings.render;

import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.render.ImageComposer.FitMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageComposerTest {

    private final ImageComposer composer = new ImageComposer();

    @Test
    @DisplayName("fitToGrid output is exactly the grid's pixel size")
    void fitToGridMatchesGridExactly() {
        BufferedImage source = solid(300, 137, Color.RED);

        for (GridSize grid : new GridSize[] {
                new GridSize(1, 1), new GridSize(4, 3), new GridSize(1, 5), new GridSize(8, 2) }) {
            for (int pixelsPerFrame : new int[] { 128, 256 }) {
                for (FitMode mode : FitMode.values()) {
                    BufferedImage fitted = composer.fitToGrid(source, grid, pixelsPerFrame, mode);
                    assertEquals(grid.columns() * pixelsPerFrame, fitted.getWidth(),
                            grid + " @" + pixelsPerFrame + " " + mode);
                    assertEquals(grid.rows() * pixelsPerFrame, fitted.getHeight(),
                            grid + " @" + pixelsPerFrame + " " + mode);
                    assertEquals(BufferedImage.TYPE_INT_ARGB, fitted.getType());
                }
            }
        }
    }

    @Test
    @DisplayName("CONTAIN keeps the aspect ratio and pads with transparency")
    void containLetterboxes() {
        // 2:1 source into a 1:1 grid, so there should be transparent bands top and bottom.
        BufferedImage source = solid(400, 200, Color.RED);
        BufferedImage fitted = composer.fitToGrid(source, new GridSize(1, 1), 256, FitMode.CONTAIN);

        assertEquals(256, fitted.getWidth());
        assertEquals(256, fitted.getHeight());

        assertEquals(0, alphaAt(fitted, 128, 2), "top band should be transparent");
        assertEquals(0, alphaAt(fitted, 128, 253), "bottom band should be transparent");
        assertEquals(0, alphaAt(fitted, 2, 2), "corner should be transparent");
        assertEquals(255, alphaAt(fitted, 128, 128), "centre should be opaque");

        Rect opaque = opaqueBounds(fitted);
        assertEquals(256, opaque.width(), "a 2:1 source should span the full width");
        assertEquals(128.0, opaque.height(), 2.0, "a 2:1 source should fill half the height");

        double sourceAspect = 400.0 / 200.0;
        double fittedAspect = (double) opaque.width() / opaque.height();
        assertEquals(sourceAspect, fittedAspect, 0.05, "CONTAIN must not distort");

        // Centred, not shoved into a corner.
        assertEquals(256.0 - opaque.bottom(), opaque.top(), 2.0, "letterbox bands should be even");
    }

    @Test
    @DisplayName("STRETCH fills the grid, distorting the source")
    void stretchFillsTheGrid() {
        BufferedImage source = solid(400, 200, Color.RED);
        BufferedImage fitted = composer.fitToGrid(source, new GridSize(1, 1), 128, FitMode.STRETCH);

        Rect opaque = opaqueBounds(fitted);
        assertEquals(0, opaque.left());
        assertEquals(0, opaque.top());
        assertEquals(128, opaque.width());
        assertEquals(128, opaque.height());
    }

    @Test
    @DisplayName("alpha survives a scale in both directions")
    void alphaSurvivesScaling() {
        // Left half opaque white paint, right half nothing - the shape of a marking PNG.
        BufferedImage source = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = source.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 256, 512);
        graphics.dispose();

        BufferedImage down = composer.scale(source, 64, 64);
        assertEquals(BufferedImage.TYPE_INT_ARGB, down.getType());
        assertEquals(255, alphaAt(down, 8, 32), "paint side must stay opaque");
        assertEquals(0, alphaAt(down, 56, 32), "empty side must stay fully transparent");
        assertEquals(0xFFFFFF, rgbAt(down, 8, 32), "white paint must not pick up a dark fringe");

        BufferedImage up = composer.scale(source, 1024, 1024);
        assertEquals(255, alphaAt(up, 100, 512));
        assertEquals(0, alphaAt(up, 900, 512));
    }

    @Test
    @DisplayName("a hard-edged downscale averages instead of aliasing")
    void downscaleIsNotAliased() {
        // One-pixel black and white stripes. A single drawImage step point-samples this
        // and comes back as stripes or moire; a proper multi-step downscale averages it
        // to flat mid grey.
        BufferedImage stripes = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 512; x++) {
            int colour = x % 2 == 0 ? 0xFF000000 : 0xFFFFFFFF;
            for (int y = 0; y < 512; y++) {
                stripes.setRGB(x, y, colour);
            }
        }

        BufferedImage small = composer.scale(stripes, 64, 64);

        int minimum = 255;
        int maximum = 0;
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 64; y++) {
                int luminance = rgbAt(small, x, y) & 0xFF;
                minimum = Math.min(minimum, luminance);
                maximum = Math.max(maximum, luminance);
            }
        }

        assertTrue(minimum > 90, "downscale kept a black stripe, so it is aliasing: min=" + minimum);
        assertTrue(maximum < 165, "downscale kept a white stripe, so it is aliasing: max=" + maximum);
        assertTrue(maximum - minimum < 60, "downscale left visible banding: spread=" + (maximum - minimum));
    }

    @Test
    @DisplayName("scaling to the same size returns a copy, not the original")
    void sameSizeScaleReturnsACopy() {
        BufferedImage source = solid(64, 64, Color.RED);
        BufferedImage scaled = composer.scale(source, 64, 64);

        assertNotSame(source, scaled);
        assertEquals(source.getRGB(10, 10), scaled.getRGB(10, 10));
    }

    @Test
    @DisplayName("onTarmac makes transparent white paint visible")
    void onTarmacFillsTheBackground() {
        BufferedImage marking = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = marking.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(8, 8, 16, 16);
        graphics.dispose();

        BufferedImage preview = composer.onTarmac(marking);

        assertEquals(255, alphaAt(preview, 0, 0), "background must be opaque");
        assertEquals(ImageComposer.TARMAC.getRGB() & 0xFFFFFF, rgbAt(preview, 0, 0));
        assertEquals(0xFFFFFF, rgbAt(preview, 16, 16), "the paint must survive on top");
    }

    @Test
    @DisplayName("load caches by path and mtime, and notices a rewrite")
    void loadCachesAndInvalidates(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("marking.png");
        composer.writePng(solid(16, 16, Color.RED), file);

        BufferedImage first = composer.load(file);
        BufferedImage second = composer.load(file);
        assertSame(first, second, "a second load of an unchanged file should be free");
        assertEquals(1, composer.cacheSize());

        // Same path, different content, same byte count. A stale thumbnail after an
        // edit is the whole reason mtime is in the key, so move it forward explicitly
        // rather than hoping the filesystem clock ticked between the two writes.
        long firstModified = Files.getLastModifiedTime(file).toMillis();
        composer.writePng(solid(16, 16, Color.BLUE), file);
        Files.setLastModifiedTime(file, FileTime.fromMillis(firstModified + 60_000));

        BufferedImage reloaded = composer.load(file);
        assertNotSame(first, reloaded);
        assertEquals(Color.BLUE.getRGB() & 0xFFFFFF, rgbAt(reloaded, 8, 8));

        composer.clearCache();
        assertEquals(0, composer.cacheSize());
    }

    @Test
    @DisplayName("the cache evicts the least recently used entry")
    void cacheEvicts(@TempDir Path directory) throws IOException {
        ImageComposer small = new ImageComposer(2);
        for (int index = 0; index < 5; index++) {
            Path file = directory.resolve("image" + index + ".png");
            small.writePng(solid(8, 8, Color.RED), file);
            small.load(file);
        }
        assertEquals(2, small.cacheSize());
    }

    @Test
    @DisplayName("writePng creates parent directories")
    void writePngCreatesParents(@TempDir Path directory) throws IOException {
        Path target = directory.resolve("generated").resolve("nested").resolve("sign.png");
        composer.writePng(solid(8, 8, Color.GREEN), target);

        assertTrue(Files.exists(target));
        assertEquals(8, composer.load(target).getWidth());
    }

    @Test
    @DisplayName("bad arguments are rejected up front")
    void badArgumentsThrow(@TempDir Path directory) {
        BufferedImage source = solid(8, 8, Color.RED);

        assertThrows(IllegalArgumentException.class, () -> composer.scale(source, 0, 8));
        assertThrows(IllegalArgumentException.class, () -> composer.scale(null, 8, 8));
        assertThrows(IllegalArgumentException.class,
                () -> composer.fitToGrid(source, new GridSize(1, 1), 0, FitMode.STRETCH));
        assertThrows(IllegalArgumentException.class, () -> new ImageComposer(0));
        assertThrows(IOException.class, () -> composer.load(directory.resolve("missing.png")));
    }

    @Test
    @DisplayName("golden PNGs for a human to eyeball")
    void writeGoldenImages() throws IOException {
        Path output = Path.of("build", "test-golden");

        BufferedImage sign = syntheticSign();
        composer.writePng(sign, output.resolve("source-sign.png"));
        composer.writePng(composer.fitToGrid(sign, new GridSize(2, 1), 256, FitMode.CONTAIN),
                output.resolve("sign-2x1-contain.png"));
        composer.writePng(composer.fitToGrid(sign, new GridSize(2, 1), 256, FitMode.STRETCH),
                output.resolve("sign-2x1-stretch.png"));
        composer.writePng(composer.scale(sign, 96, 96), output.resolve("sign-thumbnail-96.png"));

        BufferedImage marking = syntheticMarking();
        composer.writePng(marking, output.resolve("marking-source.png"));
        composer.writePng(composer.onTarmac(marking), output.resolve("marking-on-tarmac.png"));
        composer.writePng(composer.onTarmac(composer.scale(marking, 96, 96)),
                output.resolve("marking-thumbnail-96-on-tarmac.png"));

        // If the real library is next to the mod, render one of its PNGs too. Skipped
        // silently when it is not, so the test still passes from a bare checkout.
        Path repoImage = Path.of("..", "give_way.png");
        if (Files.isRegularFile(repoImage)) {
            BufferedImage real = composer.load(repoImage);
            composer.writePng(composer.onTarmac(composer.scale(real, 128, 128)),
                    output.resolve("repo-give-way-128-on-tarmac.png"));
        }

        assertTrue(Files.isDirectory(output));
    }

    private static BufferedImage syntheticSign() {
        // Concentric high-contrast rings; anything that aliases turns these into moire.
        BufferedImage image = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 1024, 1024);
        for (int radius = 512; radius > 0; radius -= 24) {
            graphics.setColor(radius / 24 % 2 == 0 ? new Color(200, 16, 46) : Color.WHITE);
            graphics.fillOval(512 - radius, 512 - radius, radius * 2, radius * 2);
        }
        graphics.dispose();
        return image;
    }

    private static BufferedImage syntheticMarking() {
        // Transparent background with thin white bars, like a give-way line.
        BufferedImage image = new BufferedImage(1024, 256, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        for (int x = 0; x < 1024; x += 96) {
            graphics.fillRect(x, 32, 48, 192);
        }
        graphics.dispose();
        return image;
    }

    private static BufferedImage solid(int width, int height, Color colour) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(colour);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return image;
    }

    private static int alphaAt(BufferedImage image, int x, int y) {
        return (image.getRGB(x, y) >>> 24) & 0xFF;
    }

    private static int rgbAt(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) & 0xFFFFFF;
    }

    private static Rect opaqueBounds(BufferedImage image) {
        int left = image.getWidth();
        int top = image.getHeight();
        int right = -1;
        int bottom = -1;

        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (alphaAt(image, x, y) < 128) {
                    continue;
                }
                left = Math.min(left, x);
                top = Math.min(top, y);
                right = Math.max(right, x);
                bottom = Math.max(bottom, y);
            }
        }

        if (right < 0) {
            throw new AssertionError("image has no opaque pixels at all");
        }
        return new Rect(left, top, right + 1, bottom + 1);
    }

    private record Rect(int left, int top, int right, int bottom) {

        int width() {
            return right - left;
        }

        int height() {
            return bottom - top;
        }
    }
}
