package dev.kierandrewett.mcmarkings.imageframe;

import dev.kierandrewett.mcmarkings.core.GridSize;

import java.util.Locale;

/**
 * Builds ImageFrame command strings, without the leading slash.
 *
 * <p>The alias is configurable because servers commonly rebind /imageframe to
 * /frame. Names are sanitised to what ImageFrame accepts as a map name.
 */
public final class ImageFrameCommands {

    private ImageFrameCommands() {
    }

    /**
     * A 1x1 grid yields a plain map item; anything larger needs the "combined"
     * keyword to come back as one placeable item rather than N loose maps.
     */
    public static String create(String alias, String name, String url, GridSize grid) {
        String base = alias + " create " + sanitiseName(name) + " " + url
                + " " + grid.columns() + " " + grid.rows();
        return grid.isSingle() ? base : base + " combined";
    }

    public static String refresh(String alias, String name, String url) {
        return alias + " refresh " + sanitiseName(name) + " " + url;
    }

    public static String get(String alias, String name, GridSize grid) {
        String base = alias + " get " + sanitiseName(name);
        return grid.isSingle() ? base : base + " combined";
    }

    public static String delete(String alias, String name) {
        return alias + " delete " + sanitiseName(name);
    }

    public static String giveInvisibleFrames(String alias, boolean glowing, int amount) {
        return alias + " giveinvisibleframe " + (glowing ? "glowing" : "regular")
                + " " + Math.max(1, amount);
    }

    public static String select(String alias) {
        return alias + " select";
    }

    /** ImageFrame map names are identifier-ish; keep to lowercase word characters. */
    public static String sanitiseName(String name) {
        String cleaned = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return cleaned.isBlank() ? "untitled" : cleaned;
    }
}
