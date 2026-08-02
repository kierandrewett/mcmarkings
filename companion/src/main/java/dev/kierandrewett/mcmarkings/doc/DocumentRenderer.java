package dev.kierandrewett.mcmarkings.doc;

import dev.kierandrewett.mcmarkings.render.FontRegistry;
import dev.kierandrewett.mcmarkings.render.ImageComposer;
import dev.kierandrewett.mcmarkings.render.TextLayout;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link Document} into pixels.
 *
 * <p>The document is never touched. Everything the renderer needs from outside it
 * arrives through the constructor or through {@link ImageResolver}, so this class
 * knows nothing about repositories, checkouts or the filesystem and a test can hand
 * it synthetic images.
 *
 * <p>One broken layer must not cost the whole sign. A missing image or an unusable
 * font draws nothing and lands in {@link #problems()}, so the caller can show the
 * user what went wrong next to an image that is otherwise correct.
 *
 * <p>Text is laid out by {@link TextLayout}, the same code the JavaScript drawing
 * API uses. A generated document and a hand-composed one have to look identical, and
 * that only holds if there is one copy of the maths.
 *
 * <p>Not thread-safe: {@link #problems()} belongs to the most recent render, so one
 * renderer serves one render at a time.
 */
public final class DocumentRenderer {

    /** Deep enough for any real composition, shallow enough not to blow the stack. */
    private static final int MAX_GROUP_DEPTH = 64;

    /**
     * Resolves a repo-relative path to a decoded image.
     *
     * <p>Kept as an interface so the renderer never learns where a repository lives,
     * and so a test can serve images it built in memory.
     */
    @FunctionalInterface
    public interface ImageResolver {

        /**
         * @param repoPath path as written in the layer, relative to the repository root
         * @return the decoded image; never null
         * @throws IOException if the path is missing, unreadable or not an image
         */
        BufferedImage resolve(String repoPath) throws IOException;
    }

    private final FontRegistry fonts;

    private final ImageComposer images;

    private final List<String> problems = new ArrayList<>();

    public DocumentRenderer(FontRegistry fonts) {
        this(fonts, new ImageComposer());
    }

    public DocumentRenderer(FontRegistry fonts, ImageComposer images) {
        if (fonts == null || images == null) {
            throw new IllegalArgumentException("fonts and images must not be null");
        }
        this.fonts = fonts;
        this.images = images;
    }

    /**
     * Composites the document top to bottom of the layer list, index 0 first.
     *
     * @return a fresh {@code TYPE_INT_ARGB} image the caller owns
     */
    public BufferedImage render(Document document, ImageResolver resolver) {
        if (document == null || resolver == null) {
            throw new IllegalArgumentException("document and resolver must not be null");
        }
        problems.clear();

        BufferedImage canvas =
                new BufferedImage(document.width(), document.height(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = canvas.createGraphics();
        try {
            applyQualityHints(graphics);
            fillBackground(graphics, document.background(), canvas.getWidth(), canvas.getHeight());
            drawLayers(graphics, document.layers(), resolver, 0);
        } finally {
            graphics.dispose();
        }
        return canvas;
    }

    /** What went wrong during the most recent render, in the order it was hit. */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    /**
     * The background replaces whatever is there rather than compositing over it, so a
     * half-transparent background stays half-transparent instead of being flattened
     * against the black the canvas starts as.
     */
    private static void fillBackground(Graphics2D graphics, int argb, int width, int height) {
        Composite previous = graphics.getComposite();
        graphics.setComposite(AlphaComposite.Src);
        graphics.setColor(new Color(argb, true));
        graphics.fill(new Rectangle2D.Double(0, 0, width, height));
        graphics.setComposite(previous);
    }

    private void drawLayers(Graphics2D graphics, List<Layer> layers, ImageResolver resolver, int depth) {
        for (Layer layer : layers) {
            if (!layer.visible()) {
                continue;
            }
            // A layer at zero opacity is not a failure, it is just not drawn.
            float alpha = alphaOf(layer.opacity());
            if (alpha <= 0f) {
                continue;
            }
            try {
                drawLayer(graphics, layer, resolver, alpha, depth);
            } catch (IOException | RuntimeException problem) {
                note(layer, problem);
            }
        }
    }

    private void drawLayer(Graphics2D graphics, Layer layer, ImageResolver resolver, float alpha, int depth)
            throws IOException {
        Composite previous = graphics.getComposite();
        graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        try {
            switch (layer) {
                case Layer.Image image -> drawImage(graphics, image, resolver);
                case Layer.Text text -> drawText(graphics, text);
                case Layer.Shape shape -> drawShape(graphics, shape);
                case Layer.Group group -> drawGroup(graphics, group, resolver, depth);
            }
        } finally {
            graphics.setComposite(previous);
        }
    }

    private void drawImage(Graphics2D graphics, Layer.Image layer, ImageResolver resolver) throws IOException {
        Layer.Bounds bounds = layer.bounds();
        if (bounds.width() < 1 || bounds.height() < 1) {
            return;
        }

        BufferedImage source = resolver.resolve(layer.repoPath());
        if (source == null) {
            throw new IOException("no image at \"" + layer.repoPath() + "\"");
        }
        if (source.getWidth() < 1 || source.getHeight() < 1) {
            throw new IOException("\"" + layer.repoPath() + "\" has no pixels");
        }

        switch (layer.fit()) {
            case STRETCH -> graphics.drawImage(
                    images.scale(source, bounds.width(), bounds.height()), bounds.x(), bounds.y(), null);
            case CONTAIN -> drawContained(graphics, source, bounds);
            case COVER -> drawCovered(graphics, source, bounds);
        }
    }

    /** Whole image, aspect kept, centred in the bounds with transparency either side. */
    private void drawContained(Graphics2D graphics, BufferedImage source, Layer.Bounds bounds) {
        double factor = Math.min(
                (double) bounds.width() / source.getWidth(),
                (double) bounds.height() / source.getHeight());
        int width = Math.min(bounds.width(), atLeastOne(source.getWidth() * factor));
        int height = Math.min(bounds.height(), atLeastOne(source.getHeight() * factor));

        graphics.drawImage(
                images.scale(source, width, height),
                bounds.x() + (bounds.width() - width) / 2,
                bounds.y() + (bounds.height() - height) / 2,
                null);
    }

    /**
     * Bounds filled, aspect kept, overflow cropped.
     *
     * <p>The crop happens on the source rather than by drawing oversized under a clip.
     * Scaling only what will be seen keeps the resampling honest and means nothing is
     * ever drawn outside the layer's own bounds.
     */
    private void drawCovered(Graphics2D graphics, BufferedImage source, Layer.Bounds bounds) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        double sourceAspect = (double) sourceWidth / sourceHeight;
        double targetAspect = (double) bounds.width() / bounds.height();

        int cropWidth = sourceWidth;
        int cropHeight = sourceHeight;
        if (sourceAspect > targetAspect) {
            cropWidth = Math.clamp(Math.round(sourceHeight * targetAspect), 1, sourceWidth);
        } else if (sourceAspect < targetAspect) {
            cropHeight = Math.clamp(Math.round(sourceWidth / targetAspect), 1, sourceHeight);
        }

        BufferedImage crop = source.getSubimage(
                (sourceWidth - cropWidth) / 2, (sourceHeight - cropHeight) / 2, cropWidth, cropHeight);
        graphics.drawImage(
                images.scale(crop, bounds.width(), bounds.height()), bounds.x(), bounds.y(), null);
    }

    /**
     * Lays the lines out inside the bounds as written.
     *
     * <p>Text has no padding of its own; a padded legend is a text layer inside a
     * shape or group, which is where the padding lives.
     */
    private void drawText(Graphics2D graphics, Layer.Text layer) {
        List<String> lines = layer.lines();
        if (lines.isEmpty()) {
            return;
        }
        requireFinitePositive(layer.size(), "text size");
        requireFinitePositive(layer.verticalScale(), "verticalScale");
        requireFinite(layer.tracking(), "tracking");
        requireFinite(layer.lineGap(), "lineGap");

        Font font = fonts.get(layer.font()).deriveFont((float) layer.size());
        FontRenderContext frc = graphics.getFontRenderContext();

        List<LaidLine> laid = new ArrayList<>(lines.size());
        double blockHeight = layer.lineGap() * (lines.size() - 1);
        for (String line : lines) {
            GlyphVector glyphs = TextLayout.glyphs(font, frc, line, layer.tracking());
            TextLayout.Metrics metrics = TextLayout.measure(font, frc, glyphs, line, layer.verticalScale());
            laid.add(new LaidLine(glyphs, metrics));
            blockHeight += metrics.height();
        }

        Layer.Bounds bounds = layer.bounds();
        double anchorX = switch (layer.horizontalAlign()) {
            case LEFT -> bounds.x();
            case CENTRE -> bounds.x() + bounds.width() / 2.0;
            case RIGHT -> bounds.right();
        };
        double top = switch (layer.verticalAlign()) {
            case TOP -> bounds.y();
            case MIDDLE -> bounds.y() + (bounds.height() - blockHeight) / 2.0;
            case BOTTOM -> bounds.y() + bounds.height() - blockHeight;
        };

        TextLayout.Align align = alignOf(layer.horizontalAlign());
        Color colour = new Color(layer.colour(), true);
        double cursor = top;
        for (LaidLine line : laid) {
            TextLayout.draw(graphics, line.glyphs(), line.metrics(), anchorX, cursor, align,
                    TextLayout.Baseline.TOP, layer.verticalScale(), colour);
            cursor += line.metrics().height() + layer.lineGap();
        }
    }

    private static void drawShape(Graphics2D graphics, Layer.Shape layer) {
        Layer.Bounds bounds = layer.bounds();
        if (bounds.width() < 1 || bounds.height() < 1) {
            return;
        }

        double radius = Math.max(0, layer.cornerRadius());
        graphics.setColor(new Color(layer.fill(), true));
        graphics.fill(rounded(bounds.x(), bounds.y(), bounds.width(), bounds.height(), radius));

        if (layer.borderWidth() <= 0) {
            return;
        }
        // A stroke is centred on its path, so the path is inset by half the border
        // width. Drawn on the bounds themselves, a thick border would spill over the
        // layers either side of it and the plate would no longer fit its own box.
        double width = Math.min(layer.borderWidth(), Math.min(bounds.width(), bounds.height()));
        double half = width / 2.0;
        graphics.setColor(new Color(layer.borderColour(), true));
        graphics.setStroke(new BasicStroke((float) width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        graphics.draw(rounded(bounds.x() + half, bounds.y() + half,
                bounds.width() - width, bounds.height() - width, Math.max(0, radius - half)));
    }

    /**
     * Draws the children into the group's content box, which is its bounds shrunk by
     * its padding, and clips them to it.
     *
     * <p>Children go onto a scratch image the size of that box rather than straight
     * onto the canvas under a clip. It costs one allocation per group, and it buys
     * two things: the clip is exact by construction, and group opacity composites the
     * finished group once instead of once per child, so overlapping children do not
     * show through each other.
     */
    private void drawGroup(Graphics2D graphics, Layer.Group layer, ImageResolver resolver, int depth) {
        if (depth >= MAX_GROUP_DEPTH) {
            throw new IllegalStateException("groups nested more than " + MAX_GROUP_DEPTH + " deep");
        }

        Layer.Bounds content = layer.bounds().shrunkBy(layer.padding());
        if (content.width() < 1 || content.height() < 1 || layer.children().isEmpty()) {
            return;
        }

        BufferedImage scratch =
                new BufferedImage(content.width(), content.height(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D scratchGraphics = scratch.createGraphics();
        try {
            applyQualityHints(scratchGraphics);
            drawLayers(scratchGraphics, layer.children(), resolver, depth + 1);
        } finally {
            scratchGraphics.dispose();
        }
        graphics.drawImage(scratch, content.x(), content.y(), null);
    }

    private void note(Layer layer, Throwable problem) {
        String name = layer.name() == null || layer.name().isBlank() ? layer.id() : layer.name();
        String detail = problem.getMessage() == null ? problem.toString() : problem.getMessage();
        problems.add("layer \"" + name + "\": " + detail);
    }

    private static java.awt.Shape rounded(double x, double y, double width, double height, double radius) {
        if (radius <= 0) {
            return new Rectangle2D.Double(x, y, width, height);
        }
        return new RoundRectangle2D.Double(x, y, width, height, radius * 2, radius * 2);
    }

    private static TextLayout.Align alignOf(Layer.HorizontalAlign align) {
        return switch (align) {
            case LEFT -> TextLayout.Align.LEFT;
            case CENTRE -> TextLayout.Align.CENTRE;
            case RIGHT -> TextLayout.Align.RIGHT;
        };
    }

    private static float alphaOf(double opacity) {
        if (Double.isNaN(opacity)) {
            return 0f;
        }
        return (float) Math.clamp(opacity, 0.0, 1.0);
    }

    private static int atLeastOne(double value) {
        return Math.max(1, (int) Math.round(value));
    }

    private static void requireFinite(double value, String what) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(what + " must be a finite number, got " + value);
        }
    }

    private static void requireFinitePositive(double value, String what) {
        if (!(value > 0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(what + " must be a positive number, got " + value);
        }
    }

    /** Matches the hints the JavaScript canvas sets, so the same drawing lands the same way. */
    private static void applyQualityHints(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        graphics.setComposite(AlphaComposite.SrcOver);
    }

    private record LaidLine(GlyphVector glyphs, TextLayout.Metrics metrics) {
    }
}
