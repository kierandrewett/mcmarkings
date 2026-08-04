package dev.kierandrewett.mcmarkings.doc;

import java.util.List;

/**
 * One element of a document.
 *
 * <p>Sealed rather than open because the renderer, the properties panel and the
 * JSON codec all switch on the layer kind, and the compiler catching a missed case
 * in three places is worth more here than the ability to add a kind from outside.
 *
 * <p>Every layer carries the same frame: where it sits, whether it is visible or
 * locked, how opaque it is, and the margins that hold other layers off it. Bounds
 * are in document pixels with the origin at the top left, matching the drawing API
 * the generator scripts already use.
 */
public sealed interface Layer permits Layer.Image, Layer.Text, Layer.Shape, Layer.Group {

    /** Stable across edits and saves, so selection and undo can refer to a layer. */
    String id();

    /** What the layers panel shows. Never blank; falls back to the kind. */
    String name();

    Bounds bounds();

    boolean visible();

    boolean locked();

    /** 0 to 1. */
    double opacity();

    Insets margins();

    Layer withBounds(Bounds bounds);

    /** Position and size in document pixels. */
    record Bounds(int x, int y, int width, int height) {

        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public int centreX() {
            return x + width / 2;
        }

        public int centreY() {
            return y + height / 2;
        }

        public Bounds movedTo(int newX, int newY) {
            return new Bounds(newX, newY, width, height);
        }

        /** Shrinks by insets, never past zero, for laying out padded content. */
        public Bounds shrunkBy(Insets insets) {
            return new Bounds(
                    x + insets.left(),
                    y + insets.top(),
                    Math.max(0, width - insets.horizontal()),
                    Math.max(0, height - insets.vertical()));
        }

        public boolean contains(int pointX, int pointY) {
            return pointX >= x && pointX < right() && pointY >= y && pointY < bottom();
        }
    }

    /** How an image fills bounds that are not its own shape. */
    enum Fit {
        /** Whole image visible, letterboxed. */
        CONTAIN,
        /** Bounds filled, overflow cropped. */
        COVER,
        /** Distorted to fit exactly. */
        STRETCH
    }

    enum HorizontalAlign {
        LEFT, CENTRE, RIGHT
    }

    enum VerticalAlign {
        TOP, MIDDLE, BOTTOM
    }

    /** A picture from the repository. */
    record Image(
            String id,
            String name,
            Bounds bounds,
            boolean visible,
            boolean locked,
            double opacity,
            Insets margins,
            String repoPath,
            Fit fit) implements Layer {

        @Override
        public Layer withBounds(Bounds newBounds) {
            return new Image(id, name, newBounds, visible, locked, opacity, margins, repoPath, fit);
        }
    }

    /**
     * A run of text.
     *
     * <p>The typographic options deliberately mirror the ones the JS drawing API
     * already exposes, so one renderer can serve both a generated document and a
     * hand-composed one without two sets of text maths.
     */
    record Text(
            String id,
            String name,
            Bounds bounds,
            boolean visible,
            boolean locked,
            double opacity,
            Insets margins,
            String text,
            String font,
            double size,
            int colour,
            HorizontalAlign horizontalAlign,
            VerticalAlign verticalAlign,
            double lineGap,
            double tracking,
            double verticalScale) implements Layer {

        @Override
        public Layer withBounds(Bounds newBounds) {
            return new Text(id, name, newBounds, visible, locked, opacity, margins, text, font, size,
                    colour, horizontalAlign, verticalAlign, lineGap, tracking, verticalScale);
        }

        public List<String> lines() {
            return text == null || text.isEmpty() ? List.of() : List.of(text.split("\n", -1));
        }
    }

    /**
     * A filled rectangle, optionally rounded and bordered.
     *
     * <p>This is the plate primitive. A sign panel is a shape with padding, and the
     * legend laid inside that padding, which is why padding lives here rather than
     * on every layer.
     */
    /**
     * What a shape layer is the shape of.
     *
     * <p>Five, and not chosen for variety. A rectangle is a plate, an ellipse is a roundel, a
     * triangle is a warning sign, an inverted triangle is give way and a diamond is a priority
     * road. That is most of the vocabulary of British signing, and anything past it is better drawn
     * as an image than approximated with a polygon.
     *
     * <p>The corner radius belongs to the rectangle and is ignored by the rest. Rounding the corners
     * of a triangle properly means offsetting three lines and joining them with arcs, which is real
     * work for something no sign in this repository needs.
     */
    enum Form {
        RECTANGLE,
        ELLIPSE,
        TRIANGLE,
        TRIANGLE_DOWN,
        DIAMOND,
    }

    record Shape(
            String id,
            String name,
            Bounds bounds,
            boolean visible,
            boolean locked,
            double opacity,
            Insets margins,
            Insets padding,
            int fill,
            int cornerRadius,
            int borderColour,
            int borderWidth,
            Form form) implements Layer {

        /**
         * A rectangle, which is what every shape was before there was a choice.
         *
         * <p>Here so that the twenty-odd places that build a plain rectangle did not all have to
         * grow an argument saying so. The places that copy an existing shape do not use it: they
         * pass the form through, because a triangle that turned back into a rectangle on being
         * renamed would be a strange thing to explain.
         */
        public Shape(String id, String name, Bounds bounds, boolean visible, boolean locked,
                double opacity, Insets margins, Insets padding, int fill, int cornerRadius,
                int borderColour, int borderWidth) {
            this(id, name, bounds, visible, locked, opacity, margins, padding, fill, cornerRadius,
                    borderColour, borderWidth, Form.RECTANGLE);
        }

        public Shape {
            form = form == null ? Form.RECTANGLE : form;
        }

        @Override
        public Layer withBounds(Bounds newBounds) {
            return new Shape(id, name, newBounds, visible, locked, opacity, margins, padding, fill,
                    cornerRadius, borderColour, borderWidth, form);
        }
    }

    /** Layers that move and are styled together. Children are in z order. */
    record Group(
            String id,
            String name,
            Bounds bounds,
            boolean visible,
            boolean locked,
            double opacity,
            Insets margins,
            Insets padding,
            List<Layer> children) implements Layer {

        public Group {
            children = children == null ? List.of() : List.copyOf(children);
        }

        @Override
        public Layer withBounds(Bounds newBounds) {
            return new Group(id, name, newBounds, visible, locked, opacity, margins, padding, children);
        }
    }
}
