package dev.kierandrewett.mcmarkings.js;

import dev.kierandrewett.mcmarkings.render.FontRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the canvas directly, without a script engine in the way. */
class CanvasApiTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private static final FontRegistry FONTS = new FontRegistry(List.of());

    private static final CanvasApi.ImageSource NO_IMAGES = path -> {
        throw new IllegalArgumentException("no images in this test");
    };

    @Test
    void parsesShortHexColours() {
        assertEquals(new Color(255, 0, 0, 255), CanvasApi.parseColour("#f00"));
    }

    @Test
    void parsesSixDigitHexColours() {
        assertEquals(new Color(0, 255, 0, 255), CanvasApi.parseColour("#00FF00"));
    }

    @Test
    void parsesEightDigitHexColoursWithAlpha() {
        assertEquals(new Color(0, 0, 255, 128), CanvasApi.parseColour("#0000FF80"));
    }

    @Test
    void parsesRgbaWithFractionalAlpha() {
        assertEquals(new Color(255, 0, 0, 128), CanvasApi.parseColour("rgba(255, 0, 0, 0.5)"));
    }

    @Test
    void rejectsUnknownColours() {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> CanvasApi.parseColour("burgundy"));
        assertTrue(thrown.getMessage().contains("#RRGGBBAA"), thrown.getMessage());
    }

    @Test
    void rejectsAlphaOutsideZeroToOne() {
        assertThrows(IllegalArgumentException.class, () -> CanvasApi.parseColour("rgba(255, 0, 0, 255)"));
    }

    @Test
    void fillsRectanglesInTheGivenColour() {
        BufferedImage image = CanvasApi.newCanvas(20, 20);
        CanvasApi canvas = new CanvasApi(image, FONTS, NO_IMAGES);
        canvas.fillRect(5, 5, 10, 10, "#FF0000");
        canvas.dispose();

        assertEquals(0xFFFF0000, image.getRGB(10, 10));
        assertEquals(0, image.getRGB(1, 1), "canvas should start fully transparent");
    }

    @Test
    void clipConfinesDrawingAndRestoreUndoesIt() {
        BufferedImage image = CanvasApi.newCanvas(40, 40);
        CanvasApi canvas = new CanvasApi(image, FONTS, NO_IMAGES);
        canvas.save();
        canvas.clip(0, 0, 20, 40);
        canvas.fillRect(0, 0, 40, 40, "#FF0000");
        canvas.restore();
        canvas.fillRect(30, 0, 10, 10, "#00FF00");
        canvas.dispose();

        assertEquals(0xFFFF0000, image.getRGB(10, 10));
        assertEquals(0, image.getRGB(30, 30), "clip should have kept the red out of the right half");
        assertEquals(0xFF00FF00, image.getRGB(32, 5), "restore should have dropped the clip");
    }

    @Test
    void restoreWithoutSaveIsAnError() {
        CanvasApi canvas = new CanvasApi(CanvasApi.newCanvas(4, 4), FONTS, NO_IMAGES);
        assertThrows(IllegalStateException.class, canvas::restore);
    }

    @Test
    void trackingWidensTextByOneGapPerJoin() {
        CanvasApi canvas = new CanvasApi(CanvasApi.newCanvas(10, 10), FONTS, NO_IMAGES);
        double plain = canvas.measureText("ABCDE", options(0, 1)).width();
        double tracked = canvas.measureText("ABCDE", options(10, 1)).width();

        // Five glyphs means four gaps, and nothing after the last one.
        assertEquals(plain + 40, tracked, 0.001);
    }

    @Test
    void scaleYStretchesHeightAndLeavesWidthAlone() {
        CanvasApi canvas = new CanvasApi(CanvasApi.newCanvas(10, 10), FONTS, NO_IMAGES);
        CanvasApi.TextMetrics plain = canvas.measureText("ABCDE", options(0, 1));
        CanvasApi.TextMetrics stretched = canvas.measureText("ABCDE", options(0, 3));

        assertEquals(plain.width(), stretched.width(), 0.001);
        assertEquals(plain.height() * 3, stretched.height(), 0.001);
        assertEquals(plain.ascent() * 3, stretched.ascent(), 0.001);
    }

    @Test
    void stretchedAndTrackedTextDrawsWhereItSaysItDoes() {
        BufferedImage image = CanvasApi.newCanvas(1200, 600);
        CanvasApi canvas = new CanvasApi(image, FONTS, NO_IMAGES);
        CanvasApi.TextOptions centred = new CanvasApi.TextOptions("transport-heavy", 120, "#FFFFFF",
                "centre", "middle", 20, 3);
        CanvasApi.TextMetrics measured = canvas.text("SLOW", 600, 300, centred);
        canvas.dispose();

        int[] bounds = inkBounds(image);
        double inkCentre = (bounds[0] + bounds[2]) / 2.0;
        assertEquals(600, inkCentre, 12, "centred text should sit on the given x");
        assertEquals(measured.width(), bounds[2] - bounds[0], measured.width() * 0.1,
                "reported width should match the ink actually drawn");
        double inkHeight = bounds[3] - bounds[1];
        assertTrue(inkHeight > 120 * 2, "3x stretched 120px text should be well over 240px tall, was " + inkHeight);
    }

    @Test
    void alignmentMovesTextRelativeToTheAnchor() {
        CanvasApi.TextOptions left = new CanvasApi.TextOptions(null, 100, "#FFFFFF", "left", "top", 0, 1);
        CanvasApi.TextOptions right = new CanvasApi.TextOptions(null, 100, "#FFFFFF", "right", "top", 0, 1);

        BufferedImage leftImage = CanvasApi.newCanvas(600, 200);
        CanvasApi leftCanvas = new CanvasApi(leftImage, FONTS, NO_IMAGES);
        double width = leftCanvas.text("HALT", 300, 10, left).width();
        leftCanvas.dispose();

        BufferedImage rightImage = CanvasApi.newCanvas(600, 200);
        CanvasApi rightCanvas = new CanvasApi(rightImage, FONTS, NO_IMAGES);
        rightCanvas.text("HALT", 300, 10, right);
        rightCanvas.dispose();

        int leftStart = inkBounds(leftImage)[0];
        int rightStart = inkBounds(rightImage)[0];
        assertEquals(width, leftStart - rightStart, width * 0.1);
    }

    @Test
    void rejectsUnknownAlignment() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new CanvasApi.TextOptions(null, 100, null, "middleish", null, 0, 1));
        assertTrue(thrown.getMessage().contains("align"), thrown.getMessage());
    }

    @Test
    void acceptsAmericanCenter() {
        assertEquals("centre", new CanvasApi.TextOptions(null, 100, null, "center", null, 0, 1).align());
    }

    @Test
    void drawsRepoImagesScaled(@TempDir Path repo) throws IOException {
        BufferedImage source = CanvasApi.newCanvas(2, 2);
        source.setRGB(0, 0, 0xFF00FF00);
        source.setRGB(1, 0, 0xFF00FF00);
        source.setRGB(0, 1, 0xFF00FF00);
        source.setRGB(1, 1, 0xFF00FF00);
        Files.createDirectories(repo.resolve("signs"));
        ImageIO.write(source, "png", repo.resolve("signs/green.png").toFile());

        BufferedImage image = CanvasApi.newCanvas(40, 40);
        CanvasApi canvas = new CanvasApi(image, FONTS, CanvasApi.repoImages(repo));
        assertEquals(2, canvas.imageSize("signs/green.png")[0]);
        canvas.drawImage("signs/green.png", 10, 10, 20, 20);
        canvas.dispose();

        assertEquals(0xFF00FF00, image.getRGB(20, 20));
        assertEquals(0, image.getRGB(2, 2));
    }

    @Test
    void namesTheMissingImagePath(@TempDir Path repo) {
        CanvasApi canvas = new CanvasApi(CanvasApi.newCanvas(4, 4), FONTS, CanvasApi.repoImages(repo));
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> canvas.imageSize("signs/nope.png"));
        assertTrue(thrown.getMessage().contains("signs/nope.png"), thrown.getMessage());
    }

    @Test
    void refusesImagePathsOutsideTheRepository(@TempDir Path repo) {
        CanvasApi canvas = new CanvasApi(CanvasApi.newCanvas(4, 4), FONTS, CanvasApi.repoImages(repo));
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> canvas.imageSize("../../etc/passwd"));
        assertTrue(thrown.getMessage().contains("escapes"), thrown.getMessage());
    }

    private static CanvasApi.TextOptions options(double tracking, double scaleY) {
        return new CanvasApi.TextOptions(null, 100, "#FFFFFF", "left", "alphabetic", tracking, scaleY);
    }

    /** @return {@code [minX, minY, maxX, maxY]} of every pixel with any alpha at all */
    private static int[] inkBounds(BufferedImage image) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        assertTrue(maxX >= minX, "expected something to have been drawn");
        return new int[] {minX, minY, maxX, maxY};
    }
}
