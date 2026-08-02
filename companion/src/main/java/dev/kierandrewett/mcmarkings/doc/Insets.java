package dev.kierandrewett.mcmarkings.doc;

/**
 * Space around or inside a layer, in document pixels.
 *
 * <p>Padding and margins are the same shape but pull in opposite directions:
 * padding insets a layer's content from its own bounds, margins hold neighbours
 * away from it. Both are needed to lay out a plate, so they share a type.
 */
public record Insets(int top, int right, int bottom, int left) {

    public static final Insets NONE = new Insets(0, 0, 0, 0);

    public static Insets all(int amount) {
        return new Insets(amount, amount, amount, amount);
    }

    public static Insets of(int vertical, int horizontal) {
        return new Insets(vertical, horizontal, vertical, horizontal);
    }

    public int horizontal() {
        return left + right;
    }

    public int vertical() {
        return top + bottom;
    }

    public boolean isNone() {
        return top == 0 && right == 0 && bottom == 0 && left == 0;
    }
}
