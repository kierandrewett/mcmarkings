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

    public int distortionPercent() {
        return (int) Math.round(distortion * 100.0);
    }
}
