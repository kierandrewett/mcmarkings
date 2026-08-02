package dev.kierandrewett.mcmarkings.doc;

import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.js.CanvasApi;
import dev.kierandrewett.mcmarkings.render.FontRegistry;
import dev.kierandrewett.mcmarkings.render.ImageComposer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Geometry, ordering and failure handling, asserted on the pixels that come out. */
class DocumentRendererTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    /** Where the eyeball-it PNGs land, relative to the companion project directory. */
    private static final Path GOLDEN_DIR = Path.of("build", "document-renderer");

    private static final FontRegistry FONTS = new FontRegistry(List.of());

    private static final int WHITE = 0xFFFFFFFF;

    private static final int BLACK = 0xFF000000;

    private static final int RED = 0xFFFF0000;

    private static final int GREEN = 0xFF00FF00;

    private static final int BLUE = 0xFF0000FF;

    private static final DocumentRenderer.ImageResolver NO_IMAGES = path -> {
        throw new IOException("nothing at \"" + path + "\"");
    };

    @Test
    void canvasSizeComesFromTheGridAndResolution() {
        Document document = Document.blank("sign", new GridSize(3, 2), 64);

        BufferedImage rendered = new DocumentRenderer(FONTS).render(document, NO_IMAGES);

        assertEquals(192, rendered.getWidth());
        assertEquals(128, rendered.getHeight());
        assertEquals(BufferedImage.TYPE_INT_ARGB, rendered.getType());
    }

    @Test
    void backgroundIsFilledBeforeAnythingElse() {
        Document document = Document.blank("sign", new GridSize(1, 1), 32).withBackground(0xFF102030);

        BufferedImage rendered = new DocumentRenderer(FONTS).render(document, NO_IMAGES);

        assertEquals(0xFF102030, rendered.getRGB(0, 0));
        assertEquals(0xFF102030, rendered.getRGB(31, 31));
    }

    @Test
    void aTransparentBackgroundStaysTransparent() {
        Document document = Document.blank("sign", new GridSize(1, 1), 16);

        BufferedImage rendered = new DocumentRenderer(FONTS).render(document, NO_IMAGES);

        assertEquals(0, rendered.getRGB(8, 8));
    }

    @Test
    void laterLayersDrawOverEarlierOnes() {
        Document document = Document.blank("sign", new GridSize(1, 1), 64)
                .add(shape("under", bounds(0, 0, 64, 64), RED))
                .add(shape("over", bounds(16, 16, 32, 32), GREEN));

        BufferedImage rendered = new DocumentRenderer(FONTS).render(document, NO_IMAGES);

        assertEquals(GREEN, rendered.getRGB(32, 32), "index 1 should sit on top of index 0");
        assertEquals(RED, rendered.getRGB(4, 4));
    }

    @Test
    void invisibleLayersAreSkipped() {
        Layer.Shape hidden = new Layer.Shape("hidden", "hidden", bounds(0, 0, 64, 64), false, false, 1.0,
                Insets.NONE, Insets.NONE, RED, 0, 0, 0);
        Document document = Document.blank("sign", new GridSize(1, 1), 64).add(hidden);

        BufferedImage rendered = new DocumentRenderer(FONTS).render(document, NO_IMAGES);

        assertEquals(0, rendered.getRGB(32, 32));
    }

    @Test
    void opacityBlendsTheLayerIntoWhatIsBelow() {
        Layer.Shape half = new Layer.Shape("half", "half", bounds(0, 0, 32, 32), true, false, 0.5,
                Insets.NONE, Insets.NONE, BLACK, 0, 0, 0);
        Document document = Document.blank("sign", new GridSize(1, 1), 32)
                .withBackground(WHITE)
                .add(half);

        BufferedImage rendered = new DocumentRenderer(FONTS).render(document, NO_IMAGES);

        assertChannelNear(128, rendered.getRGB(16, 16) & 0xFF, 2, "black at half opacity over white");
    }

    @Test
    void containLetterboxesAndCentresTheWholeImage() {
        // 40x20 into 100x100: the scale is bound by width, so 100x50 sits with 25 rows
        // of nothing above and below it.
        Document document = Document.blank("sign", new GridSize(1, 1), 100)
                .add(image("photo", bounds(0, 0, 100, 100), "wide.png", Layer.Fit.CONTAIN));

        BufferedImage rendered = new DocumentRenderer(FONTS).render(document, resolver("wide.png", flat(40, 20, RED)));

        assertEquals(25, firstOpaqueRow(rendered, 50));
        assertEquals(74, lastOpaqueRow(rendered, 50));
        assertEquals(0, firstOpaqueColumn(rendered, 50));
        assertEquals(99, lastOpaqueColumn(rendered, 50));
        assertColourNear(RED, rendered.getRGB(50, 50), 2);
    }

    @Test
    void coverFillsTheBoundsAndCropsFromTheCentre() {
        // Bands left to right: green, red, blue. A centred 1:1 crop of the 40x20 source
        // is its middle 20 columns, which are entirely red.
        BufferedImage banded = flat(40, 20, RED);
        fillColumns(banded, 0, 10, GREEN);
        fillColumns(banded, 30, 40, BLUE);

        Document document = Document.blank("sign", new GridSize(1, 1), 100)
                .add(image("photo", bounds(0, 0, 100, 100), "banded.png", Layer.Fit.COVER));

        BufferedImage rendered = new DocumentRenderer(FONTS).render(document, resolver("banded.png", banded));

        assertEquals(0, firstOpaqueRow(rendered, 50), "cover should fill the bounds");
        assertEquals(99, lastOpaqueRow(rendered, 50));
        assertColourNear(RED, rendered.getRGB(2, 2), 2);
        assertColourNear(RED, rendered.getRGB(97, 97), 2);
        assertFalse(containsHue(rendered, GREEN), "the left band should have been cropped away");
        assertFalse(containsHue(rendered, BLUE), "the right band should have been cropped away");
    }

    @Test
    void stretchDistortsTheImageToFillTheBoundsExactly() {
        Document document = Document.blank("sign", new GridSize(1, 1), 100)
                .add(image("photo", bounds(20, 30, 60, 40), "wide.png", Layer.Fit.STRETCH));

        BufferedImage rendered = new DocumentRenderer(FONTS).render(document, resolver("wide.png", flat(40, 20, RED)));

        assertEquals(30, firstOpaqueRow(rendered, 50));
        assertEquals(69, lastOpaqueRow(rendered, 50));
        assertEquals(20, firstOpaqueColumn(rendered, 50));
        assertEquals(79, lastOpaqueColumn(rendered, 50));
    }

    @Test
    void downscalingAnImageKeepsItsAlpha() {
        // A single drawImage from 512px to 24px throws most of the source away; the
        // multi-step path is what stops fine detail turning into noise.
        Document document = Document.blank("sign", new GridSize(1, 1), 24)
                .add(image("photo", bounds(0, 0, 24, 24), "big.png", Layer.Fit.CONTAIN));

        BufferedImage rendered = new DocumentRenderer(FONTS).render(document, resolver("big.png", flat(512, 512, RED)));

        assertColourNear(RED, rendered.getRGB(12, 12), 2);
        assertEquals(0, firstOpaqueRow(rendered, 12));
        assertEquals(23, lastOpaqueRow(rendered, 12));
    }

    @Test
    void shapeBorderIsDrawnInsideTheBounds() {
        Layer.Shape plate = new Layer.Shape("plate", "plate", bounds(10, 10, 40, 40), true, false, 1.0,
                Insets.NONE, Insets.NONE, WHITE, 0, BLACK, 6);
        Document document = Document.blank("sign", new GridSize(1, 1), 64).add(plate);

        BufferedImage rendered = new DocumentRenderer(FONTS).render(document, NO_IMAGES);

        assertEquals(0, rendered.getRGB(9, 30), "a border must not bleed outside its layer");
        assertEquals(BLACK, rendered.getRGB(12, 30), "the border belongs inside the bounds");
        assertEquals(WHITE, rendered.getRGB(30, 30), "the fill should still show through the middle");
    }

    @Test
    void horizontalAlignmentMovesTheRunAcrossTheBounds() {
        int width = 200;
        BufferedImage left = renderText(Layer.HorizontalAlign.LEFT, Layer.VerticalAlign.TOP);
        BufferedImage centre = renderText(Layer.HorizontalAlign.CENTRE, Layer.VerticalAlign.TOP);
        BufferedImage right = renderText(Layer.HorizontalAlign.RIGHT, Layer.VerticalAlign.TOP);

        int leftStart = firstInkColumn(left);
        int centreStart = firstInkColumn(centre);
        int rightEnd = lastInkColumn(right);

        assertTrue(leftStart < centreStart, "centred text should start further right than left-aligned");
        assertTrue(centreStart < firstInkColumn(right), "right-aligned text should start further right again");
        // Loose to the width of a side bearing: which font the machine has is not the
        // point, where the run is anchored is.
        assertTrue(leftStart <= 8, "left-aligned text should start at the left edge, got " + leftStart);
        assertTrue(rightEnd >= width - 9, "right-aligned text should end at the right edge, got " + rightEnd);
        int centreMid = (firstInkColumn(centre) + lastInkColumn(centre)) / 2;
        assertChannelNear(width / 2, centreMid, 8, "centred text midpoint");
    }

    @Test
    void verticalAlignmentMovesTheBlockDownTheBounds() {
        int height = 120;
        BufferedImage top = renderText(Layer.HorizontalAlign.LEFT, Layer.VerticalAlign.TOP);
        BufferedImage middle = renderText(Layer.HorizontalAlign.LEFT, Layer.VerticalAlign.MIDDLE);
        BufferedImage bottom = renderText(Layer.HorizontalAlign.LEFT, Layer.VerticalAlign.BOTTOM);

        int topInk = firstInkRow(top);
        int middleInk = firstInkRow(middle);
        int bottomInk = firstInkRow(bottom);

        assertTrue(topInk < middleInk, "middle should sit below top");
        assertTrue(middleInk < bottomInk, "bottom should sit below middle");
        assertTrue(topInk < height / 3, "top-aligned text should be in the top third, got " + topInk);
        assertTrue(lastInkRow(bottom) > height * 2 / 3,
                "bottom-aligned text should be in the bottom third, got " + lastInkRow(bottom));
    }

    @Test
    void groupPaddingOffsetsItsChildren() {
        Layer.Shape child = shape("child", bounds(0, 0, 20, 20), RED);
        Layer.Group group = new Layer.Group("group", "group", bounds(10, 10, 100, 100), true, false, 1.0,
                Insets.NONE, Insets.all(20), List.of(child));
        Document document = Document.blank("sign", new GridSize(1, 1), 128).add(group);

        BufferedImage rendered = new DocumentRenderer(FONTS).render(document, NO_IMAGES);

        // Content box is (30, 30) to (89, 89), so a child at its own (0, 0) lands there.
        assertEquals(RED, rendered.getRGB(30, 30));
        assertEquals(RED, rendered.getRGB(49, 49));
        assertEquals(0, rendered.getRGB(29, 29), "the padding must stay empty");
        assertEquals(0, rendered.getRGB(50, 50), "the child should not have grown");
    }

    @Test
    void groupClipsChildrenToItsContentBox() {
        Layer.Shape overflowing = shape("child", bounds(0, 0, 500, 500), RED);
        Layer.Group group = new Layer.Group("group", "group", bounds(10, 10, 100, 100), true, false, 1.0,
                Insets.NONE, Insets.all(20), List.of(overflowing));
        Document document = Document.blank("sign", new GridSize(1, 1), 128).add(group);

        BufferedImage rendered = new DocumentRenderer(FONTS).render(document, NO_IMAGES);

        assertEquals(RED, rendered.getRGB(89, 89), "the content box runs to 89 inclusive");
        assertEquals(0, rendered.getRGB(90, 90), "anything past the content box must be clipped");
        assertEquals(0, rendered.getRGB(29, 29));
    }

    @Test
    void groupsNestAndCompoundTheirPadding() {
        Layer.Shape child = shape("child", bounds(0, 0, 10, 10), RED);
        Layer.Group inner = new Layer.Group("inner", "inner", bounds(0, 0, 60, 60), true, false, 1.0,
                Insets.NONE, Insets.all(5), List.of(child));
        Layer.Group outer = new Layer.Group("outer", "outer", bounds(10, 10, 100, 100), true, false, 1.0,
                Insets.NONE, Insets.all(20), List.of(inner));
        Document document = Document.blank("sign", new GridSize(1, 1), 128).add(outer);

        BufferedImage rendered = new DocumentRenderer(FONTS).render(document, NO_IMAGES);

        // 10 + 20 (outer padding) + 0 (inner bounds) + 5 (inner padding) = 35.
        assertEquals(RED, rendered.getRGB(35, 35));
        assertEquals(0, rendered.getRGB(34, 34));
    }

    @Test
    void groupOpacityCompositesTheGroupOnceRatherThanEachChild() {
        // Two children overlapping. If the group's alpha were applied per child the
        // overlap would come out darker than the rest.
        Layer.Shape first = shape("first", bounds(0, 0, 40, 40), BLACK);
        Layer.Shape second = shape("second", bounds(20, 0, 40, 40), BLACK);
        Layer.Group group = new Layer.Group("group", "group", bounds(0, 0, 64, 64), true, false, 0.5,
                Insets.NONE, Insets.NONE, List.of(first, second));
        Document document = Document.blank("sign", new GridSize(1, 1), 64)
                .withBackground(WHITE)
                .add(group);

        BufferedImage rendered = new DocumentRenderer(FONTS).render(document, NO_IMAGES);

        int outside = rendered.getRGB(5, 5) & 0xFF;
        int overlap = rendered.getRGB(30, 5) & 0xFF;
        assertChannelNear(outside, overlap, 2, "the overlap should match the rest of the group");
    }

    @Test
    void aMissingImageIsReportedWithoutSinkingTheDocument() {
        Document document = Document.blank("sign", new GridSize(1, 1), 64)
                .withBackground(WHITE)
                .add(image("missing", bounds(0, 0, 32, 32), "gone.png", Layer.Fit.CONTAIN))
                .add(shape("plate", bounds(40, 40, 20, 20), RED));

        DocumentRenderer renderer = new DocumentRenderer(FONTS);
        BufferedImage rendered = renderer.render(document, NO_IMAGES);

        assertEquals(WHITE, rendered.getRGB(16, 16), "the broken layer should have drawn nothing");
        assertEquals(RED, rendered.getRGB(50, 50), "the layers after it should still be drawn");
        assertEquals(1, renderer.problems().size(), renderer.problems().toString());
        assertTrue(renderer.problems().getFirst().contains("missing"), renderer.problems().getFirst());
        assertTrue(renderer.problems().getFirst().contains("gone.png"), renderer.problems().getFirst());
    }

    @Test
    void problemsBelongToTheMostRecentRenderOnly() {
        DocumentRenderer renderer = new DocumentRenderer(FONTS);
        renderer.render(Document.blank("bad", new GridSize(1, 1), 32)
                .add(image("missing", bounds(0, 0, 32, 32), "gone.png", Layer.Fit.CONTAIN)), NO_IMAGES);
        assertEquals(1, renderer.problems().size());

        renderer.render(Document.blank("good", new GridSize(1, 1), 32), NO_IMAGES);

        assertTrue(renderer.problems().isEmpty(), "a clean render should report nothing");
    }

    @Test
    void aBrokenChildDoesNotTakeItsSiblingsWithIt() {
        Layer.Group group = new Layer.Group("group", "group", bounds(0, 0, 64, 64), true, false, 1.0,
                Insets.NONE, Insets.NONE, List.of(
                        image("missing", bounds(0, 0, 20, 20), "gone.png", Layer.Fit.COVER),
                        shape("sibling", bounds(30, 30, 20, 20), RED)));
        Document document = Document.blank("sign", new GridSize(1, 1), 64).add(group);

        DocumentRenderer renderer = new DocumentRenderer(FONTS);
        BufferedImage rendered = renderer.render(document, NO_IMAGES);

        assertEquals(RED, rendered.getRGB(35, 35));
        assertEquals(1, renderer.problems().size(), renderer.problems().toString());
    }

    @Test
    void renderingDoesNotMutateTheDocument() {
        Document document = Document.blank("sign", new GridSize(2, 1), 32)
                .withBackground(WHITE)
                .add(shape("plate", bounds(0, 0, 10, 10), RED))
                .add(new Layer.Group("group", "group", bounds(0, 0, 32, 32), true, false, 1.0,
                        Insets.NONE, Insets.all(2), List.of(shape("child", bounds(0, 0, 4, 4), BLUE))));
        Document before = new Document(document.name(), document.grid(), document.pixelsPerFrame(),
                document.background(), document.layers());

        new DocumentRenderer(FONTS).render(document, NO_IMAGES);

        assertEquals(before, document);
    }

    @Test
    void resolvesImagesThroughWhateverTheCallerWiresUp(@TempDir Path directory) throws IOException {
        ImageComposer composer = new ImageComposer();
        Path file = directory.resolve("markings").resolve("stripe.png");
        composer.writePng(flat(8, 8, GREEN), file);

        Document document = Document.blank("sign", new GridSize(1, 1), 32)
                .add(image("stripe", bounds(0, 0, 32, 32), "markings/stripe.png", Layer.Fit.STRETCH));

        BufferedImage rendered = new DocumentRenderer(FONTS, composer)
                .render(document, path -> composer.load(directory.resolve(path)));

        assertColourNear(GREEN, rendered.getRGB(16, 16), 2);
    }

    /**
     * The anti-drift test. A document and a hand-composed canvas have to agree, and
     * they only will while both go through the one piece of text maths.
     */
    @Test
    void textThroughTheRendererMatchesTheSameTextThroughTheCanvasApi() {
        int width = 300;
        int height = 160;
        String content = "STOP\nAHEAD";
        double size = 44;
        double lineGap = 7;
        double tracking = 2.5;
        double verticalScale = 1.15;
        Layer.Bounds textBounds = bounds(20, 15, 260, 130);

        Layer.Text layer = new Layer.Text("legend", "legend", textBounds, true, false, 1.0, Insets.NONE,
                content, "sans-serif", size, WHITE, Layer.HorizontalAlign.CENTRE, Layer.VerticalAlign.MIDDLE,
                lineGap, tracking, verticalScale);
        Document document = new Document("sign", new GridSize(width / 20, height / 20), 20, 0, List.of(layer));
        assertEquals(width, document.width());
        assertEquals(height, document.height());

        BufferedImage viaRenderer = new DocumentRenderer(FONTS).render(document, NO_IMAGES);
        BufferedImage viaCanvas = drawTheSameLegendWithTheCanvasApi(
                width, height, content, textBounds, size, lineGap, tracking, verticalScale);

        assertEquals(0, differingPixels(viaRenderer, viaCanvas),
                "the document renderer and the canvas API have drifted apart");
        assertTrue(inkPixels(viaRenderer) > 0, "the test would pass on two blank images");
    }

    @Test
    void writesGoldensForEyeballing() throws IOException {
        Files.createDirectories(GOLDEN_DIR);
        ImageComposer composer = new ImageComposer();
        DocumentRenderer renderer = new DocumentRenderer(FONTS, composer);

        BufferedImage chevrons = flat(240, 80, 0xFF1E88E5);
        fillColumns(chevrons, 0, 60, 0xFFE53935);
        fillColumns(chevrons, 180, 240, 0xFF43A047);
        DocumentRenderer.ImageResolver resolver = resolver("chevrons.png", chevrons);

        Document fits = new Document("fits", new GridSize(3, 1), 128, 0xFF202024, List.of(
                image("contain", bounds(4, 4, 120, 120), "chevrons.png", Layer.Fit.CONTAIN),
                image("cover", bounds(132, 4, 120, 120), "chevrons.png", Layer.Fit.COVER),
                image("stretch", bounds(260, 4, 120, 120), "chevrons.png", Layer.Fit.STRETCH)));
        composer.writePng(renderer.render(fits, resolver), GOLDEN_DIR.resolve("fits.png"));

        List<Layer> cells = new ArrayList<>();
        int cell = 0;
        for (Layer.VerticalAlign vertical : Layer.VerticalAlign.values()) {
            for (Layer.HorizontalAlign horizontal : Layer.HorizontalAlign.values()) {
                int x = (cell % 3) * 128;
                int y = (cell / 3) * 128;
                cells.add(new Layer.Shape("cell" + cell, "cell" + cell, bounds(x + 2, y + 2, 124, 124), true,
                        false, 1.0, Insets.NONE, Insets.all(8), 0xFF2E2E36, 8, 0xFF5A5A66, 2));
                cells.add(new Layer.Text("text" + cell, horizontal + "/" + vertical,
                        bounds(x + 10, y + 10, 108, 108), true, false, 1.0, Insets.NONE, "Ag\nBh", "sans-serif",
                        30, WHITE, horizontal, vertical, 4, 0, 1.0));
                cell++;
            }
        }
        Document alignment = new Document("text", new GridSize(3, 3), 128, 0xFF101014, cells);
        composer.writePng(renderer.render(alignment, resolver), GOLDEN_DIR.resolve("text.png"));

        Layer.Group plate = new Layer.Group("plate", "plate", bounds(20, 20, 344, 216), true, false, 1.0,
                Insets.NONE, Insets.all(16), List.of(
                        new Layer.Shape("panel", "panel", bounds(0, 0, 312, 184), true, false, 1.0, Insets.NONE,
                                Insets.all(20), 0xFF0B4F9E, 24, WHITE, 8),
                        new Layer.Text("legend", "legend", bounds(20, 20, 272, 144), true, false, 1.0,
                                Insets.NONE, "SLOW\nCHILDREN\nCROSSING", "sans-serif", 38, WHITE,
                                Layer.HorizontalAlign.CENTRE, Layer.VerticalAlign.MIDDLE, 6, 1.5, 1.0)));
        Document showcase = new Document("showcase", new GridSize(3, 2), 128, 0xFF3C3C41, List.of(
                image("backdrop", bounds(0, 0, 384, 256), "chevrons.png", Layer.Fit.COVER),
                plate));
        composer.writePng(renderer.render(showcase, resolver), GOLDEN_DIR.resolve("showcase.png"));

        assertTrue(Files.isRegularFile(GOLDEN_DIR.resolve("fits.png")));
        assertTrue(Files.isRegularFile(GOLDEN_DIR.resolve("text.png")));
        assertTrue(Files.isRegularFile(GOLDEN_DIR.resolve("showcase.png")));
        assertTrue(renderer.problems().isEmpty(), renderer.problems().toString());
    }

    /**
     * Replays the same legend through the public canvas API, laying the lines out the
     * way a script author would: measure each line, stack them, and place the block.
     */
    private static BufferedImage drawTheSameLegendWithTheCanvasApi(
            int width,
            int height,
            String content,
            Layer.Bounds textBounds,
            double size,
            double lineGap,
            double tracking,
            double verticalScale) {
        BufferedImage image = CanvasApi.newCanvas(width, height);
        CanvasApi canvas = new CanvasApi(image, FONTS, path -> {
            throw new IllegalArgumentException("no images in this test");
        });
        CanvasApi.TextOptions options = new CanvasApi.TextOptions(
                "sans-serif", size, "#FFFFFF", "centre", "top", tracking, verticalScale);

        String[] lines = content.split("\n", -1);
        double blockHeight = lineGap * (lines.length - 1);
        for (String line : lines) {
            blockHeight += canvas.measureText(line, options).height();
        }

        double anchorX = textBounds.x() + textBounds.width() / 2.0;
        double cursor = textBounds.y() + (textBounds.height() - blockHeight) / 2.0;
        for (String line : lines) {
            cursor += canvas.text(line, anchorX, cursor, options).height() + lineGap;
        }
        canvas.dispose();
        return image;
    }

    private static BufferedImage renderText(Layer.HorizontalAlign horizontal, Layer.VerticalAlign vertical) {
        Layer.Text layer = new Layer.Text("text", "text", bounds(0, 0, 200, 120), true, false, 1.0, Insets.NONE,
                "ABC", "sans-serif", 30, WHITE, horizontal, vertical, 0, 0, 1.0);
        Document document = new Document("sign", new GridSize(200 / 40, 120 / 40), 40, 0, List.of(layer));
        return new DocumentRenderer(FONTS).render(document, NO_IMAGES);
    }

    private static Layer.Bounds bounds(int x, int y, int width, int height) {
        return new Layer.Bounds(x, y, width, height);
    }

    private static Layer.Shape shape(String id, Layer.Bounds bounds, int fill) {
        return new Layer.Shape(id, id, bounds, true, false, 1.0, Insets.NONE, Insets.NONE, fill, 0, 0, 0);
    }

    private static Layer.Image image(String id, Layer.Bounds bounds, String repoPath, Layer.Fit fit) {
        return new Layer.Image(id, id, bounds, true, false, 1.0, Insets.NONE, repoPath, fit);
    }

    private static DocumentRenderer.ImageResolver resolver(String repoPath, BufferedImage image) {
        return path -> {
            if (!repoPath.equals(path)) {
                throw new IOException("nothing at \"" + path + "\"");
            }
            return image;
        };
    }

    private static BufferedImage flat(int width, int height, int argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(argb, true));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static void fillColumns(BufferedImage image, int fromX, int toX, int argb) {
        for (int x = fromX; x < toX; x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                image.setRGB(x, y, argb);
            }
        }
    }

    private static boolean containsHue(BufferedImage image, int argb) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (near(image.getRGB(x, y), argb, 24)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int differingPixels(BufferedImage left, BufferedImage right) {
        assertEquals(left.getWidth(), right.getWidth());
        assertEquals(left.getHeight(), right.getHeight());
        int differing = 0;
        for (int y = 0; y < left.getHeight(); y++) {
            for (int x = 0; x < left.getWidth(); x++) {
                if (left.getRGB(x, y) != right.getRGB(x, y)) {
                    differing++;
                }
            }
        }
        return differing;
    }

    private static int inkPixels(BufferedImage image) {
        int ink = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) > 0) {
                    ink++;
                }
            }
        }
        return ink;
    }

    private static int firstOpaqueRow(BufferedImage image, int x) {
        for (int y = 0; y < image.getHeight(); y++) {
            if ((image.getRGB(x, y) >>> 24) > 200) {
                return y;
            }
        }
        return -1;
    }

    private static int lastOpaqueRow(BufferedImage image, int x) {
        for (int y = image.getHeight() - 1; y >= 0; y--) {
            if ((image.getRGB(x, y) >>> 24) > 200) {
                return y;
            }
        }
        return -1;
    }

    private static int firstOpaqueColumn(BufferedImage image, int y) {
        for (int x = 0; x < image.getWidth(); x++) {
            if ((image.getRGB(x, y) >>> 24) > 200) {
                return x;
            }
        }
        return -1;
    }

    private static int lastOpaqueColumn(BufferedImage image, int y) {
        for (int x = image.getWidth() - 1; x >= 0; x--) {
            if ((image.getRGB(x, y) >>> 24) > 200) {
                return x;
            }
        }
        return -1;
    }

    private static int firstInkRow(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) > 32) {
                    return y;
                }
            }
        }
        return -1;
    }

    private static int lastInkRow(BufferedImage image) {
        for (int y = image.getHeight() - 1; y >= 0; y--) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) > 32) {
                    return y;
                }
            }
        }
        return -1;
    }

    private static int firstInkColumn(BufferedImage image) {
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if ((image.getRGB(x, y) >>> 24) > 32) {
                    return x;
                }
            }
        }
        return -1;
    }

    private static int lastInkColumn(BufferedImage image) {
        for (int x = image.getWidth() - 1; x >= 0; x--) {
            for (int y = 0; y < image.getHeight(); y++) {
                if ((image.getRGB(x, y) >>> 24) > 32) {
                    return x;
                }
            }
        }
        return -1;
    }

    private static boolean near(int left, int right, int tolerance) {
        for (int shift : new int[] {24, 16, 8, 0}) {
            if (Math.abs(((left >> shift) & 0xFF) - ((right >> shift) & 0xFF)) > tolerance) {
                return false;
            }
        }
        return true;
    }

    private static void assertColourNear(int expected, int actual, int tolerance) {
        assertTrue(near(expected, actual, tolerance),
                () -> "expected about " + Integer.toHexString(expected) + " but got " + Integer.toHexString(actual));
    }

    private static void assertChannelNear(int expected, int actual, int tolerance, String what) {
        assertTrue(Math.abs(expected - actual) <= tolerance,
                () -> what + ": expected about " + expected + " but got " + actual);
    }
}
