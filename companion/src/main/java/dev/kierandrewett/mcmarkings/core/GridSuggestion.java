package dev.kierandrewett.mcmarkings.core;

/**
 * A candidate frame grid for an image, with how much the image has to be
 * stretched to fill it.
 *
 * <p>{@code distortion} is the relative aspect error: 0.0 is a perfect fit, 0.05
 * means the image is 5% off the grid's shape. The browser shows the best fit and
 * a couple of runners-up so the wall layout can win over pixel fidelity.
 */
public record GridSuggestion(GridSize grid, double distortion) {

    /** Distortion at or below this reads as "no visible stretch". */
    public static final double COMFORTABLE = 0.05;

    public boolean isComfortable() {
        return distortion <= COMFORTABLE;
    }

    /**
     * How much of the frames this grid asks for the image actually covers.
     *
     * <p>The image is fitted to the grid keeping its shape and centred, so a grid
     * whose proportions differ from the image's does not distort it, it leaves
     * transparent space. That space is invisible on a wall and it is not free: it is
     * item frames placed and not used.
     *
     * <p>The smaller of the two proportions over the larger, whichever way round they
     * are. My first attempt derived it from {@code distortion}, which cannot be done:
     * that is measured against the grid's proportions rather than the image's, so it
     * reads 100% for a 4:1 image on a 2x1 grid, where the image covers half.
     */
    public static int coveragePercent(GridSize grid, double imageAspect) {
        if (imageAspect <= 0.0) {
            return 0;
        }
        double gridAspect = grid.aspect();
        double ratio = Math.min(gridAspect, imageAspect) / Math.max(gridAspect, imageAspect);
        return (int) Math.round(ratio * 100.0);
    }

    public int distortionPercent() {
        return (int) Math.round(distortion * 100.0);
    }
}
