package dev.kierandrewett.mcmarkings.js;

import dev.kierandrewett.mcmarkings.render.FontRegistry;
import dev.kierandrewett.mcmarkings.render.TextLayout;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The drawing surface handed to a generator script as {@code ctx}.
 *
 * <p>Deliberately free of any script-engine types: this is a plain Java canvas that
 * can be driven and asserted on from a test without Rhino in the picture, and
 * {@link CanvasBinding} is the only thing that knows it is exposed to JavaScript.
 *
 * <p>Coordinates are pixels, origin top-left, y down. Every failure is an
 * {@link IllegalArgumentException} carrying a message written for the script author,
 * because that message is what ends up in front of the user.
 */
public final class CanvasApi {

    /** A runaway script should not be able to eat the heap through save() alone. */
    private static final int MAX_SAVE_DEPTH = 1024;

    private final BufferedImage image;

    private final Graphics2D graphics;

    private final FontRegistry fonts;

    private final ImageSource images;

    private final Deque<State> savedStates = new ArrayDeque<>();

    /**
     * Decoded images are cached for the life of one render only. A sign that stamps
     * the same roundel forty times should decode it once, but holding full-size
     * decoded PNGs across renders would cost tens of megabytes for no real gain.
     */
    private final Map<String, BufferedImage> imageCache = new HashMap<>();

    public CanvasApi(BufferedImage image, FontRegistry fonts, ImageSource images) {
        this.image = image;
        this.fonts = fonts;
        this.images = images;
        this.graphics = image.createGraphics();
        applyQualityHints(this.graphics);
    }

    /** Supplies repo images to {@link #drawImage}, so tests can stub the filesystem out. */
    @FunctionalInterface
    public interface ImageSource {

        /** @throws IllegalArgumentException if the path is missing, unreadable or outside the repo */
        BufferedImage load(String repoPath);
    }

    public record TextMetrics(double width, double height, double ascent, double descent) {
    }

    /**
     * Text styling. Nulls mean "use the default", so the binding can pass through
     * whatever the script actually set without pre-filling anything.
     */
    public record TextOptions(
            String font,
            double size,
            String colour,
            String align,
            String baseline,
            double tracking,
            double scaleY) {

        // Whatever a sign should be lettered in belongs to the generator, not to
        // the mod. This is only what to use when a script names nothing at all.
        public static final String DEFAULT_FONT = FontRegistry.DEFAULT_FONT;

        public static final double DEFAULT_SIZE = 100.0;

        public static final String DEFAULT_COLOUR = "#FFFFFF";

        public static final String DEFAULT_ALIGN = "left";

        public static final String DEFAULT_BASELINE = "alphabetic";

        public static final double DEFAULT_TRACKING = 0.0;

        public static final double DEFAULT_SCALE_Y = 1.0;

        private static final Set<String> ALIGNS = Set.of("left", "centre", "right");

        private static final Set<String> BASELINES = Set.of("top", "middle", "alphabetic");

        public TextOptions {
            font = blank(font) ? DEFAULT_FONT : font.trim().toLowerCase(Locale.ROOT);
            colour = blank(colour) ? DEFAULT_COLOUR : colour.trim();
            align = blank(align) ? DEFAULT_ALIGN : align.trim().toLowerCase(Locale.ROOT);
            // American spelling is accepted because half the world's canvas code says "center".
            align = align.equals("center") ? "centre" : align;
            baseline = blank(baseline) ? DEFAULT_BASELINE : baseline.trim().toLowerCase(Locale.ROOT);
            if (!ALIGNS.contains(align)) {
                throw new IllegalArgumentException("align must be one of " + ALIGNS + ", got \"" + align + "\"");
            }
            if (!BASELINES.contains(baseline)) {
                throw new IllegalArgumentException(
                        "baseline must be one of " + BASELINES + ", got \"" + baseline + "\"");
            }
            if (!(size > 0) || !Double.isFinite(size)) {
                throw new IllegalArgumentException("text size must be a positive number, got " + size);
            }
            if (!(scaleY > 0) || !Double.isFinite(scaleY)) {
                throw new IllegalArgumentException("scaleY must be a positive number, got " + scaleY);
            }
            if (!Double.isFinite(tracking)) {
                throw new IllegalArgumentException("tracking must be a finite number, got " + tracking);
            }
        }

        public static TextOptions defaults() {
            return new TextOptions(null, DEFAULT_SIZE, null, null, null, DEFAULT_TRACKING, DEFAULT_SCALE_Y);
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }

    public static BufferedImage newCanvas(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }

    /** Reads PNGs from a repository checkout, refusing anything that escapes the root. */
    public static ImageSource repoImages(Path repoRoot) {
        Path root = repoRoot.toAbsolutePath().normalize();
        return repoPath -> {
            if (repoPath == null || repoPath.isBlank()) {
                throw new IllegalArgumentException("image path is required");
            }
            Path resolved = root.resolve(repoPath).normalize();
            if (!resolved.startsWith(root)) {
                throw new IllegalArgumentException("image path \"" + repoPath + "\" escapes the repository root");
            }
            if (!Files.isRegularFile(resolved)) {
                throw new IllegalArgumentException("no image at \"" + repoPath + "\" (looked in " + resolved + ")");
            }
            try {
                BufferedImage loaded = ImageIO.read(resolved.toFile());
                if (loaded == null) {
                    throw new IllegalArgumentException("\"" + repoPath + "\" is not an image format we can read");
                }
                return loaded;
            } catch (IOException exception) {
                throw new IllegalArgumentException("could not read \"" + repoPath + "\": " + exception.getMessage());
            }
        };
    }

    public static Color parseColour(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("colour is required");
        }
        String text = value.trim();
        if (text.startsWith("#")) {
            return parseHexColour(text);
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("rgba(") || lower.startsWith("rgb(")) {
            return parseRgbaColour(text, lower.startsWith("rgba("));
        }
        throw badColour(text);
    }

    public int width() {
        return image.getWidth();
    }

    public int height() {
        return image.getHeight();
    }

    public void fillRect(double x, double y, double w, double h, String colour) {
        graphics.setColor(parseColour(colour));
        graphics.fill(new Rectangle2D.Double(x, y, w, h));
    }

    public void roundedRect(double x, double y, double w, double h, double radius, String colour) {
        graphics.setColor(parseColour(colour));
        graphics.fill(new RoundRectangle2D.Double(x, y, w, h, radius * 2, radius * 2));
    }

    /** Stroke is centred on the given rectangle, matching canvas 2D's strokeRect. */
    public void strokeRect(double x, double y, double w, double h, double thickness, String colour) {
        graphics.setColor(parseColour(colour));
        graphics.setStroke(stroke(thickness));
        graphics.draw(new Rectangle2D.Double(x, y, w, h));
    }

    public void strokeRoundedRect(
            double x, double y, double w, double h, double radius, double thickness, String colour) {
        graphics.setColor(parseColour(colour));
        graphics.setStroke(stroke(thickness));
        graphics.draw(new RoundRectangle2D.Double(x, y, w, h, radius * 2, radius * 2));
    }

    public void line(double x1, double y1, double x2, double y2, double thickness, String colour) {
        graphics.setColor(parseColour(colour));
        graphics.setStroke(stroke(thickness));
        graphics.draw(new Line2D.Double(x1, y1, x2, y2));
    }

    public void polygon(double[][] points, String colour) {
        if (points == null || points.length < 3) {
            throw new IllegalArgumentException("polygon needs at least 3 points, got "
                    + (points == null ? 0 : points.length));
        }
        Path2D.Double path = new Path2D.Double();
        for (int index = 0; index < points.length; index++) {
            double[] point = points[index];
            if (point == null || point.length < 2) {
                throw new IllegalArgumentException("polygon point " + index + " must be [x, y]");
            }
            if (index == 0) {
                path.moveTo(point[0], point[1]);
            } else {
                path.lineTo(point[0], point[1]);
            }
        }
        path.closePath();
        graphics.setColor(parseColour(colour));
        graphics.fill(path);
    }

    public void circle(double cx, double cy, double radius, String colour) {
        graphics.setColor(parseColour(colour));
        graphics.fill(new Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2));
    }

    /**
     * Draws an annulus whose outer edge lands exactly on {@code outerRadius}, which is
     * how a roundel is specified on a real sign: outside diameter and band width.
     */
    public void ring(double cx, double cy, double outerRadius, double thickness, String colour) {
        if (thickness <= 0) {
            throw new IllegalArgumentException("ring thickness must be greater than 0, got " + thickness);
        }
        double centreRadius = outerRadius - thickness / 2;
        graphics.setColor(parseColour(colour));
        graphics.setStroke(stroke(thickness));
        graphics.draw(new Ellipse2D.Double(cx - centreRadius, cy - centreRadius, centreRadius * 2, centreRadius * 2));
    }

    public TextMetrics measureText(String text, TextOptions options) {
        String safe = text == null ? "" : text;
        Font font = fontFor(options);
        FontRenderContext frc = graphics.getFontRenderContext();
        return toMetrics(TextLayout.measure(font, frc, safe, options.tracking(), options.scaleY()));
    }

    public TextMetrics text(String text, double x, double y, TextOptions options) {
        String safe = text == null ? "" : text;
        Font font = fontFor(options);
        FontRenderContext frc = graphics.getFontRenderContext();
        GlyphVector glyphs = TextLayout.glyphs(font, frc, safe, options.tracking());
        TextLayout.Metrics measured = TextLayout.measure(font, frc, glyphs, safe, options.scaleY());

        TextLayout.draw(graphics, glyphs, measured, x, y, alignOf(options.align()), baselineOf(options.baseline()),
                options.scaleY(), parseColour(options.colour()));
        return toMetrics(measured);
    }

    public void drawImage(String repoPath, double x, double y, double w, double h) {
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("drawImage needs a positive width and height, got " + w + "x" + h);
        }
        BufferedImage source = cachedImage(repoPath);
        AffineTransform previous = graphics.getTransform();
        graphics.translate(x, y);
        graphics.scale(w / source.getWidth(), h / source.getHeight());
        graphics.drawImage(source, 0, 0, null);
        graphics.setTransform(previous);
    }

    /** @return {@code [width, height]} of the repo image, without drawing it */
    public int[] imageSize(String repoPath) {
        BufferedImage source = cachedImage(repoPath);
        return new int[] {source.getWidth(), source.getHeight()};
    }

    public void save() {
        if (savedStates.size() >= MAX_SAVE_DEPTH) {
            throw new IllegalStateException("too many nested save() calls (limit " + MAX_SAVE_DEPTH + ")");
        }
        savedStates.push(new State(graphics.getTransform(), graphics.getClip()));
    }

    public void restore() {
        if (savedStates.isEmpty()) {
            throw new IllegalStateException("restore() called without a matching save()");
        }
        State state = savedStates.pop();
        graphics.setTransform(state.transform());
        graphics.setClip(state.clip());
    }

    public void translate(double x, double y) {
        graphics.translate(x, y);
    }

    public void scale(double sx, double sy) {
        graphics.scale(sx, sy);
    }

    public void rotate(double radians) {
        graphics.rotate(radians);
    }

    /** Intersects with any existing clip, like canvas 2D. */
    public void clip(double x, double y, double w, double h) {
        graphics.clip(new Rectangle2D.Double(x, y, w, h));
    }

    public void clearClip() {
        graphics.setClip(null);
    }

    public BufferedImage image() {
        return image;
    }

    public void dispose() {
        graphics.dispose();
        imageCache.clear();
    }

    private BufferedImage cachedImage(String repoPath) {
        BufferedImage cached = imageCache.get(repoPath);
        if (cached != null) {
            return cached;
        }
        BufferedImage loaded = images.load(repoPath);
        imageCache.put(repoPath, loaded);
        return loaded;
    }

    private Font fontFor(TextOptions options) {
        return fonts.get(options.font()).deriveFont((float) options.size());
    }

    /** The script-facing spellings, mapped onto the shared layout's vocabulary. */
    private static TextLayout.Align alignOf(String align) {
        return switch (align) {
            case "centre" -> TextLayout.Align.CENTRE;
            case "right" -> TextLayout.Align.RIGHT;
            default -> TextLayout.Align.LEFT;
        };
    }

    private static TextLayout.Baseline baselineOf(String baseline) {
        return switch (baseline) {
            case "top" -> TextLayout.Baseline.TOP;
            case "middle" -> TextLayout.Baseline.MIDDLE;
            default -> TextLayout.Baseline.ALPHABETIC;
        };
    }

    private static TextMetrics toMetrics(TextLayout.Metrics metrics) {
        return new TextMetrics(metrics.width(), metrics.height(), metrics.ascent(), metrics.descent());
    }

    private static BasicStroke stroke(double thickness) {
        if (!(thickness > 0) || !Double.isFinite(thickness)) {
            throw new IllegalArgumentException("thickness must be a positive number, got " + thickness);
        }
        return new BasicStroke((float) thickness, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER);
    }

    private static Color parseHexColour(String text) {
        String digits = text.substring(1);
        boolean sized = digits.length() == 3 || digits.length() == 6 || digits.length() == 8;
        if (!sized || !digits.matches("(?i)[0-9a-f]+")) {
            throw badColour(text);
        }
        if (digits.length() == 3) {
            StringBuilder expanded = new StringBuilder(6);
            for (char nibble : digits.toCharArray()) {
                expanded.append(nibble).append(nibble);
            }
            digits = expanded.toString();
        }
        int red = Integer.parseInt(digits.substring(0, 2), 16);
        int green = Integer.parseInt(digits.substring(2, 4), 16);
        int blue = Integer.parseInt(digits.substring(4, 6), 16);
        int alpha = digits.length() == 8 ? Integer.parseInt(digits.substring(6, 8), 16) : 255;
        return new Color(red, green, blue, alpha);
    }

    private static Color parseRgbaColour(String text, boolean withAlpha) {
        int open = text.indexOf('(');
        int close = text.lastIndexOf(')');
        if (close != text.length() - 1) {
            throw badColour(text);
        }
        String[] parts = text.substring(open + 1, close).split(",");
        if (parts.length != (withAlpha ? 4 : 3)) {
            throw badColour(text);
        }
        try {
            int red = channel(parts[0], text);
            int green = channel(parts[1], text);
            int blue = channel(parts[2], text);
            // Alpha is 0..1 here, unlike the channels, because that is what CSS does.
            double alpha = withAlpha ? Double.parseDouble(parts[3].trim()) : 1.0;
            if (!Double.isFinite(alpha) || alpha < 0 || alpha > 1) {
                throw new IllegalArgumentException("alpha in \"" + text + "\" must be between 0 and 1");
            }
            return new Color(red, green, blue, (int) Math.round(alpha * 255));
        } catch (NumberFormatException exception) {
            throw badColour(text);
        }
    }

    private static int channel(String raw, String text) {
        int value = (int) Math.round(Double.parseDouble(raw.trim()));
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException("colour channel in \"" + text + "\" must be between 0 and 255");
        }
        return value;
    }

    private static IllegalArgumentException badColour(String text) {
        return new IllegalArgumentException("unrecognised colour \"" + text
                + "\", expected #RGB, #RRGGBB, #RRGGBBAA or rgba(r, g, b, a)");
    }

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

    private record State(AffineTransform transform, Shape clip) {
    }
}
