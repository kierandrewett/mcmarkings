package dev.kierandrewett.mcmarkings;

import java.awt.image.BufferedImage;

/**
 * Measuring what a render actually put on the canvas.
 *
 * <p>Shared because two suites had written the same method, byte for byte, in
 * different packages: one checking a generated sign is not blank, the other checking
 * a document covers its canvas. Both are asking "did anything draw", and one answer
 * to that is enough.
 *
 * <p>Deliberately crude. These are a floor under a golden image, not a measurement:
 * they catch a canvas that came back empty or nearly so, and everything subtler is
 * caught by looking at the picture, which is the point of writing it out.
 */
public final class Pixels {

    private Pixels() {
    }

    /** Fraction of pixels with meaningful alpha, from 0 to 1. */
    public static double coverage(BufferedImage image) {
        long painted = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) > 8) {
                    painted++;
                }
            }
        }
        return painted / (double) (image.getWidth() * (long) image.getHeight());
    }

    // No isBlank here on purpose. The generator suite has one and it asks a stricter
    // question, whether any pixel at all carries alpha, sampled every third row. A
    // threshold version beside it would look like the same check and quietly disagree
    // near zero, which is the only place either of them is ever consulted.
}
