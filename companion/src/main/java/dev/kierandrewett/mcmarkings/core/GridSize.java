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

    @Override
    public String toString() {
        return columns + "x" + rows;
    }
}
