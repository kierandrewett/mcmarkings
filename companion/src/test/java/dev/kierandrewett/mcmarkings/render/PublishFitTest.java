package dev.kierandrewett.mcmarkings.render;

import dev.kierandrewett.mcmarkings.core.GridSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What publishing writes has the grid's proportions and the image's shape.
 *
 * <p>Publishing used to write the image at whatever size it was rendered and leave
 * the fitting to the server, so the mod could predict what would happen to a sign and
 * not decide it. Now the PNG has exactly the grid's proportions, which means there is
 * nothing left to fit: the server splits it and that is all.
 *
 * <p>Checked by rendering a real sign through it and looking at the result as well.
 * A 1601x445 direction sign on the recommended 2x1 comes out 1602x801, aspect 2.000
 * against the grid's 2.000, with the sign across the middle and transparent above and
 * below it.
 */
class PublishFitTest {

    /** The same sizing publishing uses: enough per frame to hold the source. */
    private static int perFrameFor(BufferedImage image, GridSize grid) {
        return Math.max(GridSize.MAP_PIXELS, Math.max(
                ceilDiv(image.getWidth(), grid.columns()),
                ceilDiv(image.getHeight(), grid.rows())));
    }

    private static int ceilDiv(int value, int by) {
        return (value + by - 1) / by;
    }

    private static BufferedImage sign(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0x00, 0x66, 0x33));
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    @Test
    @DisplayName("the written image has the grid's proportions exactly")
    void theWrittenImageMatchesTheGrid() {
        ImageComposer composer = new ImageComposer();

        for (int[] size : new int[][] {{1601, 445}, {1024, 1024}, {450, 170}, {601, 1024}}) {
            BufferedImage image = sign(size[0], size[1]);
            GridSize grid = GridRecommender.best(size[0], size[1]);
            BufferedImage fitted = composer.fitToGrid(image, grid, perFrameFor(image, grid),
                    ImageComposer.FitMode.CONTAIN);

            double written = fitted.getWidth() / (double) fitted.getHeight();
            assertEquals(grid.aspect(), written, 0.01,
                    size[0] + "x" + size[1] + " on " + grid + " came out at " + written);
        }
    }

    /**
     * And that nothing was thrown away getting there. Fitting at map resolution would
     * be simpler and would hand the server a downsampled image to downsample again.
     */
    @Test
    @DisplayName("fitting does not shrink the sign below the size it was drawn")
    void detailSurvivesTheFit() {
        ImageComposer composer = new ImageComposer();
        BufferedImage image = sign(1601, 445);
        GridSize grid = GridRecommender.best(1601, 445);

        BufferedImage fitted = composer.fitToGrid(image, grid, perFrameFor(image, grid),
                ImageComposer.FitMode.CONTAIN);

        assertTrue(fitted.getWidth() >= image.getWidth(),
                "the sign was scaled down to " + fitted.getWidth() + " from " + image.getWidth());
    }

    /**
     * The margin is transparent rather than filled. On a wall the difference is
     * between a sign and a sign on a coloured slab.
     */
    @Test
    @DisplayName("what surrounds the sign is transparent")
    void theMarginIsTransparent() {
        ImageComposer composer = new ImageComposer();
        BufferedImage image = sign(1601, 445);
        GridSize grid = GridRecommender.best(1601, 445);
        BufferedImage fitted = composer.fitToGrid(image, grid, perFrameFor(image, grid),
                ImageComposer.FitMode.CONTAIN);

        int topCorner = fitted.getRGB(2, 2) >>> 24;
        assertEquals(0, topCorner, "the margin is filled rather than see-through");
    }
}
