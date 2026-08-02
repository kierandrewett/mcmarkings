package dev.kierandrewett.mcmarkings.render;

import dev.kierandrewett.mcmarkings.core.GridSize;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads, scales and composites the repository's PNGs.
 *
 * <p>Everything here works in {@code TYPE_INT_ARGB}. Most of the library is
 * transparent - road markings are white or yellow paint with nothing behind them -
 * so dropping alpha anywhere turns a marking into a white rectangle.
 *
 * <p>Scaling is done in steps rather than in one {@code drawImage}. Java2D's
 * interpolation only samples a small neighbourhood, so taking a 1024px sign to 96px
 * in one go throws away most of the source and the result crawls with aliasing. The
 * browser shows a wall of these thumbnails at once, where that is very obvious.
 *
 * <p>Threading: {@link #load(Path)} is safe to call from several threads and returns
 * a <em>shared</em> cached image, so callers must not draw into it. Every other
 * method returns a fresh image that the caller owns.
 */
public final class ImageComposer {

    /** How a source image is made to fill a frame grid. */
    public enum FitMode {
        /** Fill the grid exactly, distorting the image to do it. */
        STRETCH,
        /** Fit inside the grid, keeping the aspect ratio, padding the rest with transparency. */
        CONTAIN
    }

    private static final int DEFAULT_CACHE_CAPACITY = 64;

    private final LruCache cache;

    public ImageComposer() {
        this(DEFAULT_CACHE_CAPACITY);
    }

    public ImageComposer(int cacheCapacity) {
        if (cacheCapacity < 1) {
            throw new IllegalArgumentException("cache capacity must be at least 1, got " + cacheCapacity);
        }
        this.cache = new LruCache(cacheCapacity);
    }

    /**
     * Reads a PNG, cached by path and modification time so an edited file is picked
     * up without a restart.
     *
     * <p>The returned image is shared with every other caller. Treat it as read-only.
     */
    public BufferedImage load(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }

        Path absolute = path.toAbsolutePath().normalize();
        CacheKey key = keyFor(absolute);

        synchronized (cache) {
            BufferedImage hit = cache.get(key);
            if (hit != null) {
                return hit;
            }
        }

        // Read outside the lock: two threads racing on the same cold file each do the
        // work once, which is cheaper than serialising every read behind one monitor.
        BufferedImage read = ImageIO.read(absolute.toFile());
        if (read == null) {
            throw new IOException("not a readable image: " + absolute);
        }
        BufferedImage argb = convert(read, BufferedImage.TYPE_INT_ARGB);

        synchronized (cache) {
            cache.put(key, argb);
        }
        return argb;
    }

    /** Loads and scales so neither edge exceeds {@code maxEdge}, keeping the aspect ratio. */
    public BufferedImage thumbnail(Path path, int maxEdge) throws IOException {
        if (maxEdge < 1) {
            throw new IllegalArgumentException("maxEdge must be at least 1, got " + maxEdge);
        }

        BufferedImage source = load(path);
        double factor = Math.min(
                (double) maxEdge / source.getWidth(),
                (double) maxEdge / source.getHeight());
        if (factor >= 1.0) {
            return copy(source);
        }
        return scale(source, atLeastOne(source.getWidth() * factor), atLeastOne(source.getHeight() * factor));
    }

    /**
     * Resamples to an exact size.
     *
     * <p>Downscales halve repeatedly until they are within 2x of the target and then
     * take one bicubic step, which is what keeps fine detail like hatching and
     * lettering readable instead of turning it into noise.
     */
    public BufferedImage scale(BufferedImage source, int targetWidth, int targetHeight) {
        requireImage(source);
        if (targetWidth < 1 || targetHeight < 1) {
            throw new IllegalArgumentException("target must be at least 1x1, got " + targetWidth + "x" + targetHeight);
        }

        if (source.getWidth() == targetWidth && source.getHeight() == targetHeight) {
            return copy(source);
        }

        // Work premultiplied. Interpolating straight alpha mixes in whatever colour
        // sits under the fully transparent pixels, which for these PNGs is black, and
        // that shows up as a dark fringe around white paint.
        BufferedImage current = convert(source, BufferedImage.TYPE_INT_ARGB_PRE);
        int width = current.getWidth();
        int height = current.getHeight();

        while (width > targetWidth * 2 || height > targetHeight * 2) {
            width = Math.max(targetWidth, width / 2);
            height = Math.max(targetHeight, height / 2);
            current = drawScaled(current, width, height, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }

        BufferedImage finished = drawScaled(current, targetWidth, targetHeight,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        return convert(finished, BufferedImage.TYPE_INT_ARGB);
    }

    /**
     * Renders a source to fill a frame grid exactly.
     *
     * <p>Output is always {@code grid.columns() * pixelsPerFrame} by
     * {@code grid.rows() * pixelsPerFrame}, because ImageFrame slices the export on
     * that boundary and a stray pixel shifts every tile after it.
     */
    public BufferedImage fitToGrid(BufferedImage source, GridSize grid, int pixelsPerFrame, FitMode mode) {
        requireImage(source);
        if (grid == null || mode == null) {
            throw new IllegalArgumentException("grid and mode must not be null");
        }
        if (pixelsPerFrame < 1) {
            throw new IllegalArgumentException("pixelsPerFrame must be at least 1, got " + pixelsPerFrame);
        }

        int targetWidth = grid.columns() * pixelsPerFrame;
        int targetHeight = grid.rows() * pixelsPerFrame;

        if (mode == FitMode.STRETCH) {
            return scale(source, targetWidth, targetHeight);
        }

        double factor = Math.min(
                (double) targetWidth / source.getWidth(),
                (double) targetHeight / source.getHeight());
        int innerWidth = Math.min(targetWidth, atLeastOne(source.getWidth() * factor));
        int innerHeight = Math.min(targetHeight, atLeastOne(source.getHeight() * factor));

        BufferedImage inner = scale(source, innerWidth, innerHeight);
        BufferedImage canvas = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.drawImage(inner, (targetWidth - innerWidth) / 2, (targetHeight - innerHeight) / 2, null);
        } finally {
            graphics.dispose();
        }
        return canvas;
    }

    /** Writes a PNG, creating parent directories. */
    public void writePng(BufferedImage image, Path path) throws IOException {
        requireImage(image);
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }

        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        if (!ImageIO.write(image, "png", absolute.toFile())) {
            throw new IOException("no PNG writer available for " + absolute);
        }
    }

    /** Drops every cached image. Useful after a repo pull rewrites a lot of files. */
    public void clearCache() {
        synchronized (cache) {
            cache.clear();
        }
    }

    public int cacheSize() {
        synchronized (cache) {
            return cache.size();
        }
    }

    /** One resampling step. Intermediates stay premultiplied; only the last hop converts back. */
    private static BufferedImage drawScaled(BufferedImage source, int width, int height, Object interpolation) {
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                    RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    /** Reuses the source when it is already the right type, so a no-op costs nothing. */
    private static BufferedImage convert(BufferedImage source, int type) {
        return source.getType() == type ? source : redraw(source, type);
    }

    /** Always a new image, so a caller can draw into it without touching a cached original. */
    private static BufferedImage copy(BufferedImage source) {
        return redraw(source, BufferedImage.TYPE_INT_ARGB);
    }

    private static BufferedImage redraw(BufferedImage source, int type) {
        BufferedImage output = new BufferedImage(source.getWidth(), source.getHeight(), type);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static int atLeastOne(double value) {
        return Math.max(1, (int) Math.round(value));
    }

    private static void requireImage(BufferedImage image) {
        if (image == null) {
            throw new IllegalArgumentException("image must not be null");
        }
    }

    private static CacheKey keyFor(Path path) throws IOException {
        return new CacheKey(path.toString(), Files.getLastModifiedTime(path).toMillis(), Files.size(path));
    }

    /** Size is in the key as well as mtime, because a fast rewrite can land in the same millisecond. */
    private record CacheKey(String path, long modifiedMillis, long size) {
    }

    private static final class LruCache extends LinkedHashMap<CacheKey, BufferedImage> {

        private final int capacity;

        private LruCache(int capacity) {
            super(16, 0.75f, true);
            this.capacity = capacity;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, BufferedImage> eldest) {
            return size() > capacity;
        }
    }
}
