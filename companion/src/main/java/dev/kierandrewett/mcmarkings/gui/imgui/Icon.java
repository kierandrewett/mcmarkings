package dev.kierandrewett.mcmarkings.gui.imgui;

import java.util.List;

/**
 * Toolbar icons, as geometry rather than as glyphs.
 *
 * <p>Drawn rather than typed because the font cannot do it. Monocraft has arrows and
 * a few shapes and no gear, tick, cross or folder, so an icon set built from glyphs
 * would be a handful of symbols and a lot of words. Words are what took the room: the
 * editor's toolbar is seventeen controls and the font is monospace, so the end of the
 * row ran off a narrow window.
 *
 * <p>Each icon is a list of strokes and boxes in a unit square, so the same geometry
 * draws through ImGui in the game and through Java2D in a test. That second part is
 * the point of doing it this way: an icon nobody has looked at is a guess, and these
 * were drawn, rendered to a PNG and corrected until they read at the size they are
 * actually used.
 *
 * <p>Deliberately plain. These are twelve or so pixels across at a normal GUI scale,
 * which is a handful of strokes, and anything more detailed turns to mush.
 */
public record Icon(List<float[]> strokes, List<float[]> boxes) {

    /** A stroke is {x1, y1, x2, y2}; a box is {x, y, width, height, filled}. */
    private static Icon of(List<float[]> strokes, List<float[]> boxes) {
        return new Icon(List.copyOf(strokes), List.copyOf(boxes));
    }

    private static float[] line(float x1, float y1, float x2, float y2) {
        return new float[] {x1, y1, x2, y2};
    }

    private static float[] box(float x, float y, float width, float height, boolean filled) {
        return new float[] {x, y, width, height, filled ? 1.0f : 0.0f};
    }

    /** A sheet with a turned corner. */
    public static final Icon NEW = of(
            List.of(line(0.25f, 0.1f, 0.65f, 0.1f), line(0.65f, 0.1f, 0.8f, 0.3f),
                    line(0.8f, 0.3f, 0.8f, 0.9f), line(0.8f, 0.9f, 0.25f, 0.9f),
                    line(0.25f, 0.9f, 0.25f, 0.1f), line(0.65f, 0.1f, 0.65f, 0.3f),
                    line(0.65f, 0.3f, 0.8f, 0.3f)),
            List.of());

    /** A folder with its lid lifted. */
    public static final Icon OPEN = of(
            List.of(line(0.15f, 0.8f, 0.15f, 0.25f), line(0.15f, 0.25f, 0.42f, 0.25f),
                    line(0.42f, 0.25f, 0.5f, 0.38f), line(0.5f, 0.38f, 0.85f, 0.38f),
                    line(0.85f, 0.38f, 0.85f, 0.8f), line(0.15f, 0.8f, 0.85f, 0.8f)),
            List.of());

    /** An arrow into a tray, which reads better at this size than a floppy disk. */
    public static final Icon SAVE = of(
            List.of(line(0.5f, 0.12f, 0.5f, 0.58f), line(0.5f, 0.58f, 0.32f, 0.4f),
                    line(0.5f, 0.58f, 0.68f, 0.4f), line(0.18f, 0.7f, 0.18f, 0.86f),
                    line(0.18f, 0.86f, 0.82f, 0.86f), line(0.82f, 0.86f, 0.82f, 0.7f)),
            List.of());

    /**
     * An arrow going into a wall.
     *
     * <p>Two attempts before this one, both found by rendering the set at the sizes
     * it is used at and looking. The first was the same four frames as
     * {@link #FRAMES} with one filled, which at thirteen pixels is the same picture.
     * The second was an arrow down into a bar, which is {@link #SAVE}, and those two
     * are neighbours on the toolbar. Sideways into a wall is neither.
     */
    public static final Icon PLACE = of(
            List.of(line(0.08f, 0.5f, 0.46f, 0.5f), line(0.46f, 0.5f, 0.3f, 0.34f),
                    line(0.46f, 0.5f, 0.3f, 0.66f)),
            List.of(box(0.6f, 0.14f, 0.28f, 0.72f, true)));

    /** Four empty frames. */
    public static final Icon FRAMES = of(
            List.of(),
            List.of(box(0.12f, 0.22f, 0.34f, 0.26f, false), box(0.54f, 0.22f, 0.34f, 0.26f, false),
                    box(0.12f, 0.56f, 0.34f, 0.26f, false), box(0.54f, 0.56f, 0.34f, 0.26f, false)));

    /** An arrow bending back on itself. */
    public static final Icon UNDO = of(
            List.of(line(0.8f, 0.72f, 0.42f, 0.72f), line(0.42f, 0.72f, 0.24f, 0.5f),
                    line(0.24f, 0.5f, 0.42f, 0.28f), line(0.24f, 0.5f, 0.44f, 0.5f),
                    line(0.44f, 0.5f, 0.44f, 0.62f)),
            List.of());

    /** The same, mirrored. */
    public static final Icon REDO = of(
            List.of(line(0.2f, 0.72f, 0.58f, 0.72f), line(0.58f, 0.72f, 0.76f, 0.5f),
                    line(0.76f, 0.5f, 0.58f, 0.28f), line(0.76f, 0.5f, 0.56f, 0.5f),
                    line(0.56f, 0.5f, 0.56f, 0.62f)),
            List.of());

    /** Two sheets, one behind the other. */
    public static final Icon DUPLICATE = of(
            List.of(),
            List.of(box(0.14f, 0.14f, 0.46f, 0.5f, false), box(0.38f, 0.36f, 0.46f, 0.5f, false)));

    /** A bin. */
    public static final Icon DELETE = of(
            List.of(line(0.2f, 0.26f, 0.8f, 0.26f), line(0.38f, 0.26f, 0.42f, 0.16f),
                    line(0.42f, 0.16f, 0.58f, 0.16f), line(0.58f, 0.16f, 0.62f, 0.26f),
                    line(0.28f, 0.26f, 0.34f, 0.86f), line(0.34f, 0.86f, 0.66f, 0.86f),
                    line(0.66f, 0.86f, 0.72f, 0.26f), line(0.5f, 0.38f, 0.5f, 0.74f)),
            List.of());

    /** A box with corner marks, the way a selection of several reads. */
    public static final Icon GROUP = of(
            List.of(line(0.14f, 0.14f, 0.34f, 0.14f), line(0.14f, 0.14f, 0.14f, 0.34f),
                    line(0.86f, 0.14f, 0.66f, 0.14f), line(0.86f, 0.14f, 0.86f, 0.34f),
                    line(0.14f, 0.86f, 0.34f, 0.86f), line(0.14f, 0.86f, 0.14f, 0.66f),
                    line(0.86f, 0.86f, 0.66f, 0.86f), line(0.86f, 0.86f, 0.86f, 0.66f)),
            List.of(box(0.34f, 0.34f, 0.32f, 0.32f, true)));

    /**
     * Two boxes with clear air between them.
     *
     * <p>Also a second attempt. The first kept the corner marks from {@link #GROUP}
     * and put two boxes inside them, which at this size was a smudge: five separate
     * things in thirteen pixels is four too many.
     */
    public static final Icon UNGROUP = of(
            List.of(),
            List.of(box(0.1f, 0.16f, 0.34f, 0.34f, false), box(0.56f, 0.5f, 0.34f, 0.34f, false)));

    /** A stack with the top one lifted clear. */
    public static final Icon FRONT = of(
            List.of(line(0.5f, 0.1f, 0.5f, 0.44f), line(0.5f, 0.1f, 0.34f, 0.26f),
                    line(0.5f, 0.1f, 0.66f, 0.26f)),
            List.of(box(0.2f, 0.54f, 0.6f, 0.3f, false)));

    /** The same, pushed down. */
    public static final Icon BACK = of(
            List.of(line(0.5f, 0.9f, 0.5f, 0.56f), line(0.5f, 0.9f, 0.34f, 0.74f),
                    line(0.5f, 0.9f, 0.66f, 0.74f)),
            List.of(box(0.2f, 0.16f, 0.6f, 0.3f, false)));

    /** Bars against a left edge. */
    public static final Icon ALIGN_LEFT = of(
            List.of(line(0.14f, 0.12f, 0.14f, 0.88f)),
            List.of(box(0.24f, 0.24f, 0.6f, 0.16f, true), box(0.24f, 0.58f, 0.38f, 0.16f, true)));

    /** Bars about a centre line. */
    public static final Icon ALIGN_CENTRE = of(
            List.of(line(0.5f, 0.12f, 0.5f, 0.88f)),
            List.of(box(0.2f, 0.24f, 0.6f, 0.16f, true), box(0.31f, 0.58f, 0.38f, 0.16f, true)));

    /** Bars against a right edge. */
    public static final Icon ALIGN_RIGHT = of(
            List.of(line(0.86f, 0.12f, 0.86f, 0.88f)),
            List.of(box(0.16f, 0.24f, 0.6f, 0.16f, true), box(0.38f, 0.58f, 0.38f, 0.16f, true)));
}
