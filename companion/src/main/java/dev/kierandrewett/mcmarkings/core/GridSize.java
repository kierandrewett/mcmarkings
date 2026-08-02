package dev.kierandrewett.mcmarkings.core;

/**
 * A block of item frames, measured in maps. One map is 128x128 pixels.
 *
 * <p>A 1x1 grid gets a plain map item from ImageFrame; anything larger has to be
 * requested with the {@code combined} keyword to come back as a single item.
 */
public record GridSize(int columns, int rows) {

    public static final int MAP_PIXELS = 128;

    public GridSize {
        if (columns < 1 || rows < 1) {
            throw new IllegalArgumentException("grid must be at least 1x1, got " + columns + "x" + rows);
        }
    }

    public int frameCount() {
        return columns * rows;
    }

    public boolean isSingle() {
        return columns == 1 && rows == 1;
    }

    public double aspect() {
        return (double) columns / (double) rows;
    }

    public int pixelWidth() {
        return columns * MAP_PIXELS;
    }

    public int pixelHeight() {
        return rows * MAP_PIXELS;
    }

    /**
     * How much of an image drawn this wide survives being placed on this grid.
     *
     * <p>A grid is chosen on aspect ratio and frame count, and resolution never
     * entered into it. That is defensible as far as it goes, and it hides the thing
     * that decides whether a sign can be read: the realistic signs the generators
     * here produce are drawn between two and four times wider than the grid picked
     * for them, so an x-height set to 50 arrives on the wall at about 14.
     *
     * <p>Distinct from the stretch figure shown beside it, which measures the sign
     * being the wrong shape and says nothing about it being small.
     *
     * <p>Capped at 100. Placing a small image on a large grid scales it up, which
     * costs frames without adding anything, and reporting 300% would read as a gain.
     */
    public int detailPercentFor(int drawnWidth) {
        if (drawnWidth <= 0) {
            return 0;
        }
        return Math.min(100, (int) Math.round(100.0 * pixelWidth() / drawnWidth));
    }

    @Override
    public String toString() {
        return columns + "x" + rows;
    }
}
