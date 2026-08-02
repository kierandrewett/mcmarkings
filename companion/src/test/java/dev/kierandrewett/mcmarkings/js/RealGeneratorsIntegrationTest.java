package dev.kierandrewett.mcmarkings.js;

import dev.kierandrewett.mcmarkings.Pixels;
import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.doc.Document;
import dev.kierandrewett.mcmarkings.doc.DocumentRenderer;
import dev.kierandrewett.mcmarkings.doc.Layer;
import dev.kierandrewett.mcmarkings.render.FontRegistry;
import dev.kierandrewett.mcmarkings.render.GridRecommender;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real scripts in generators/ through the real runtime.
 *
 * <p>The host and the scripts were written independently against a written
 * contract, so nothing until this point actually proved the two agree. Unit
 * tests on either side use their own fixtures and would both stay green if, say,
 * a LINES param arrived as a string instead of an array.
 *
 * <p>Skips rather than fails when generators/ is absent, so the mod stays
 * buildable on its own.
 */
class RealGeneratorsIntegrationTest {

    private static Path repoRoot;
    private static RhinoGeneratorRuntime runtime;
    private static FontRegistry fonts;

    @BeforeAll
    static void loadRealGenerators() throws GeneratorException {
        // The Gradle working directory is companion/, so the clone is its parent.
        repoRoot = Path.of("").toAbsolutePath().getParent();
        Assumptions.assumeTrue(repoRoot != null && Files.isDirectory(repoRoot.resolve("generators")),
                "generators/ not present, skipping");

        fonts = new FontRegistry(List.of(
                System.getProperty("user.home") + "/.local/share/fonts",
                "/usr/share/fonts"));

        runtime = new RhinoGeneratorRuntime(repoRoot, "generators", fonts);
        runtime.reload();
    }

    @Test
    void everyRealScriptLoads() {
        assertTrue(runtime.loadErrors().isEmpty(),
                () -> "scripts failed to load: " + runtime.loadErrors());

        List<String> ids = runtime.generators().stream().map(GeneratorDef::id).toList();
        assertTrue(ids.contains("plate"), () -> "expected a plate generator, got " + ids);
        assertTrue(ids.contains("direction_sign"), () -> "expected a direction sign generator, got " + ids);
    }

    /** Every declared SELECT default must be one of its own options, or the form breaks. */
    @Test
    void declaredSelectDefaultsAreValid() {
        for (GeneratorDef generator : runtime.generators()) {
            for (ParamDef param : generator.params()) {
                if (param.type() != ParamDef.ParamType.SELECT) {
                    continue;
                }
                assertTrue(param.options() != null && !param.options().isEmpty(),
                        () -> generator.id() + "." + param.key() + " is a select with no options");
                if (param.defaultValue() != null && !param.defaultValue().isBlank()) {
                    assertTrue(param.options().contains(param.defaultValue()),
                            () -> generator.id() + "." + param.key() + " default '" + param.defaultValue()
                                    + "' is not among " + param.options());
                }
            }
        }
    }

    @Test
    void plateRendersTheRealSign() throws Exception {
        BufferedImage image = runtime.render("plate", Map.of(
                "lines", List.of("30 mph", "speed limit", "250 yards", "ahead"),
                "scheme", "blue"));

        assertUsable(image, "plate");
        writeGolden(image, "real-plate.png");

        // A blue plate should actually be mostly blue, not a blank canvas.
        assertTrue(dominantIsBlue(image), "plate does not look like a blue sign");
    }

    /**
     * The direction sign is the demanding case: it composites a roundel from
     * signs/ through ctx.drawImage, so this also proves repo image loading works
     * end to end.
     */
    @Test
    void directionSignRendersWithRoundel() throws Exception {
        BufferedImage image = runtime.render("direction_sign", Map.of(
                "destinations", List.of("Basingstoke|A339", "Wootton St Lawrence"),
                "scheme", "primary"));

        assertUsable(image, "direction_sign");
        writeGolden(image, "real-direction-sign.png");
    }

    /** Optional parts absent must still produce a sign rather than throwing. */
    @Test
    void directionSignSurvivesEveryOptionalPartBeingOff() throws Exception {
        BufferedImage image = runtime.render("direction_sign", Map.of(
                "destinations", List.of("Andover"),
                "roundel", "",
                "distancePanel", "",
                "junction", "none"));

        assertUsable(image, "direction_sign minimal");
        writeGolden(image, "real-direction-sign-minimal.png");
    }

    /** Empty input must not blow up; the form starts empty. */
    @Test
    void generatorsTolerateEmptyInput() throws Exception {
        for (GeneratorDef generator : runtime.generators()) {
            BufferedImage image = runtime.render(generator.id(), Map.of());
            assertUsable(image, generator.id() + " with no params");
        }
    }

    /**
     * Guards a whole class of bug rather than one instance of it.
     *
     * <p>Rhino does not re-initialise a {@code const} declared inside a loop body:
     * every iteration keeps the first iteration's value. A generator written that
     * way still produces a correctly sized, non-blank, plausible-looking sign, with
     * every line silently replaced by line one, so nothing else in this suite
     * notices. Two inputs that differ only in their later lines must therefore
     * render differently.
     */
    @Test
    void laterLinesActuallyReachTheCanvas() throws Exception {
        BufferedImage varied = runtime.render("plate", Map.of(
                "lines", List.of("AAAA", "BBBB"), "scheme", "white"));
        BufferedImage repeated = runtime.render("plate", Map.of(
                "lines", List.of("AAAA", "AAAA"), "scheme", "white"));

        assertFalse(pixelsEqual(varied, repeated),
                "a plate reading AAAA/BBBB rendered identically to one reading AAAA/AAAA, "
                        + "so lines after the first are being dropped");
    }

    /** The same trap, on the generator that stacks destinations. */
    @Test
    void laterDestinationsActuallyReachTheCanvas() throws Exception {
        BufferedImage varied = runtime.render("direction_sign", Map.of(
                "destinations", List.of("Alpha", "Bravo"), "roundel", "", "junction", "none"));
        BufferedImage repeated = runtime.render("direction_sign", Map.of(
                "destinations", List.of("Alpha", "Alpha"), "roundel", "", "junction", "none"));

        assertFalse(pixelsEqual(varied, repeated),
                "destinations after the first are being dropped");
    }

    private static boolean pixelsEqual(BufferedImage left, BufferedImage right) {
        if (left.getWidth() != right.getWidth() || left.getHeight() != right.getHeight()) {
            return false;
        }
        for (int y = 0; y < left.getHeight(); y++) {
            for (int x = 0; x < left.getWidth(); x++) {
                if (left.getRGB(x, y) != right.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The repository's own plate, opened as layers rather than as a flat image.
     *
     * <p>The point of the whole path: a generated image stops being a dead end. If
     * this produces something that will not render, the editor would open an empty
     * canvas and nobody would know why, so it is rendered here rather than merely
     * parsed.
     */
    @Test
    void theRealPlateOpensAsAnEditableDocument() throws Exception {
        Document document = runtime.document("plate", Map.of(
                "lines", List.of("30 mph", "speed limit"),
                "scheme", "blue")).orElseThrow(() -> new AssertionError("plate should describe itself"));

        assertTrue(document.width() > 0 && document.height() > 0);
        assertTrue(document.layers().size() >= 3, "a panel plus one layer per line, got "
                + document.layers().size());

        // The legend has to survive as editable text, not be baked into a picture.
        List<Layer.Text> lines = document.layers().stream()
                .filter(Layer.Text.class::isInstance)
                .map(Layer.Text.class::cast)
                .toList();
        assertEquals(2, lines.size());
        assertEquals("30 mph", lines.getFirst().text());
        assertEquals("speed limit", lines.getLast().text());

        // Every line must sit inside the canvas, or the layout put it off the plate.
        for (Layer.Text line : lines) {
            assertTrue(line.bounds().x() >= 0 && line.bounds().y() >= 0,
                    line.text() + " starts outside the canvas at " + line.bounds());
            assertTrue(line.bounds().bottom() <= document.height(),
                    line.text() + " runs off the bottom: " + line.bounds());
        }

        BufferedImage rendered = new DocumentRenderer(fonts).render(document, path -> {
            throw new IOException("this document should need no images: " + path);
        });
        assertFalse(isBlank(rendered), "the document rendered to nothing");
        writeGolden(rendered, "real-plate-document.png");
    }

    /**
     * The direction sign as layers, which is the case this was built for.
     *
     * <p>It is the demanding one: stacked destinations, route numbers in a second
     * colour, a stroked junction diagram, a composited roundel and an inset panel.
     * If any of those cannot be expressed as layers then a generated sign is still
     * a dead end for exactly the thing people most want to adjust.
     */
    @Test
    void theRealDirectionSignOpensAsAnEditableDocument() throws Exception {
        Document document = runtime.document("direction_sign", Map.of(
                "destinations", List.of("Basingstoke|A339", "Wootton St Lawrence"),
                "scheme", "primary")).orElseThrow(() -> new AssertionError("it should describe itself"));

        // The roundel has to arrive as an image layer pointing at a real file, not
        // as pixels baked into a background.
        List<Layer.Image> images = document.layers().stream()
                .filter(Layer.Image.class::isInstance).map(Layer.Image.class::cast).toList();
        assertEquals(1, images.size(), "the roundel should be its own layer");
        assertTrue(Files.isRegularFile(repoRoot.resolve(images.getFirst().repoPath())),
                "the roundel layer points at " + images.getFirst().repoPath() + ", which is not there");

        // Destinations and route numbers survive as editable text.
        List<String> text = document.layers().stream()
                .filter(Layer.Text.class::isInstance).map(Layer.Text.class::cast)
                .map(Layer.Text::text).toList();
        assertTrue(text.contains("Basingstoke"), () -> "expected the destination, got " + text);
        assertTrue(text.contains("A339"), () -> "expected the route number, got " + text);
        assertTrue(text.contains("Wootton St Lawrence"), () -> "expected the second destination, got " + text);

        // The diagram is strokes, so it can be moved rather than only redrawn.
        assertTrue(document.layers().stream().anyMatch(Layer.Shape.class::isInstance),
                "the panel and junction should be shapes");

        BufferedImage rendered = new DocumentRenderer(fonts)
                .render(document, path -> ImageIO.read(repoRoot.resolve(path).toFile()));
        assertFalse(isBlank(rendered), "the document rendered to nothing");
        writeGolden(rendered, "real-direction-sign-document.png");
    }

    /** Whatever comes out has to map onto a placeable block of item frames. */
    @Test
    void outputMapsOntoAReasonableFrameGrid() throws Exception {
        BufferedImage image = runtime.render("plate", Map.of(
                "lines", List.of("30 mph", "speed limit", "250 yards", "ahead")));

        GridSize grid = GridRecommender.best(image.getWidth(), image.getHeight());
        assertTrue(grid.frameCount() <= 16,
                () -> "plate wants " + grid + ", which is too many frames to place by hand");
    }

    private static void assertUsable(BufferedImage image, String what) {
        assertTrue(image.getWidth() > 0 && image.getHeight() > 0, what + " produced an empty canvas");
        assertTrue(image.getWidth() <= 8192 && image.getHeight() <= 8192, what + " produced an absurd canvas");
        assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType(), what + " lost its alpha channel");
        assertFalse(isBlank(image), what + " produced a blank image");
    }

    private static boolean isBlank(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y += 3) {
            for (int x = 0; x < image.getWidth(); x += 3) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean dominantIsBlue(BufferedImage image) {
        long blue = 0;
        long counted = 0;
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                int argb = image.getRGB(x, y);
                if ((argb >>> 24) < 128) {
                    continue;
                }
                counted++;
                int red = (argb >> 16) & 0xFF;
                int green = (argb >> 8) & 0xFF;
                int blueChannel = argb & 0xFF;
                if (blueChannel > red + 30 && blueChannel > green + 20) {
                    blue++;
                }
            }
        }
        return counted > 0 && blue * 2 > counted;
    }

    private static void writeGolden(BufferedImage image, String name) throws IOException {
        Path directory = Path.of("build", "test-generators");
        Files.createDirectories(directory);
        ImageIO.write(image, "PNG", directory.resolve(name).toFile());
    }

    /**
     * The two routes have to agree.
     *
     * <p>render is what gets published straight to a wall and document is what the
     * editor opens, so a generator that describes itself differently from how it
     * draws itself produces a sign that changes depending on which button was
     * pressed. That is written down in the generators guide as a rule and was not
     * checked anywhere, which is the state a rule is least useful in.
     *
     * <p>Not the same size, and they cannot be. render draws at the natural size the
     * layout came out as; a document's canvas is always columns times pixelsPerFrame,
     * so it is the smallest frame-aligned canvas that covers that layout. Writing
     * this test expecting them to match is how I found that out, and the equality it
     * originally asserted was the wrong rule rather than a failing one.
     *
     * <p>What has to hold is that the document covers the drawing and does not
     * overshoot it by more than a frame. Too small clips the sign, and nothing in the
     * editor recovers content that was never on the canvas. Too large leaves it
     * floating in space with the legend pushed off centre, which is exactly what a
     * shape-scored grid used to do before it was scored by wasted area.
     */
    @Test
    void theDocumentCanvasCoversTheDrawing() throws Exception {
        Map<String, Object> params = Map.of(
                "lines", List.of("30 mph", "speed limit"),
                "scheme", "blue");

        assertCoversWithoutOvershooting(runtime.render("plate", params),
                runtime.document("plate", params).orElseThrow(), "plate");
    }

    @Test
    void theDirectionSignDocumentCanvasCoversTheDrawing() throws Exception {
        // The demanding one: composited images, a stroked diagram and an inset panel,
        // so far more places for the two descriptions to drift apart.
        Map<String, Object> params = Map.of(
                "destinations", List.of("Basingstoke|A339", "Wootton St Lawrence"),
                "distance", "500 yards");

        assertCoversWithoutOvershooting(runtime.render("direction_sign", params),
                runtime.document("direction_sign", params).orElseThrow(), "direction sign");
    }

    private static void assertCoversWithoutOvershooting(BufferedImage drawn, Document described, String what) {
        int frame = described.pixelsPerFrame();

        assertTrue(described.width() >= drawn.getWidth(),
                what + " is drawn " + drawn.getWidth() + " wide but described as only "
                        + described.width() + ", so it would be clipped");
        assertTrue(described.height() >= drawn.getHeight(),
                what + " is drawn " + drawn.getHeight() + " tall but described as only "
                        + described.height() + ", so it would be clipped");

        assertTrue(described.width() - drawn.getWidth() < frame,
                what + " wastes " + (described.width() - drawn.getWidth())
                        + " pixels of width, which is a whole frame of nothing");
        assertTrue(described.height() - drawn.getHeight() < frame,
                what + " wastes " + (described.height() - drawn.getHeight())
                        + " pixels of height, which is a whole frame of nothing");
    }

    /**
     * And both fill roughly the same part of the canvas.
     *
     * <p>Matching sizes with nothing in the middle would still pass, so this checks
     * coverage: what fraction of the canvas is not transparent. Deliberately a coarse
     * measure with a wide tolerance, because it is looking for a missing line or a
     * panel that did not make it across, not for a pixel of antialiasing.
     */
    @Test
    void bothRoutesFillRoughlyTheSameCanvas() throws Exception {
        Map<String, Object> params = Map.of(
                "lines", List.of("30 mph", "speed limit"),
                "scheme", "blue");

        BufferedImage drawn = runtime.render("plate", params);
        BufferedImage described = new DocumentRenderer(fonts)
                .render(runtime.document("plate", params).orElseThrow(), path -> {
                    throw new IOException("this document should need no images: " + path);
                });

        double drawnCoverage = Pixels.coverage(drawn);
        double describedCoverage = Pixels.coverage(described);

        assertTrue(Math.abs(drawnCoverage - describedCoverage) < 0.15,
                String.format("one route covers %.2f of the canvas and the other %.2f, "
                        + "so something is missing from one of them", drawnCoverage, describedCoverage));
    }

    /** Fraction of pixels that are not fully transparent. */
}
