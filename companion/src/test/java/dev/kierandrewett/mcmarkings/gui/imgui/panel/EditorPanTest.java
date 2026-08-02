package dev.kierandrewett.mcmarkings.gui.imgui.panel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Panning cannot lose the canvas.
 *
 * <p>Right-dragging added the mouse delta straight to the pan with nothing bounding
 * it. At a low zoom a few flicks put the document thousands of pixels away, and what
 * you are left with is an empty pane and no hint which direction it went.
 *
 * <p>There is a Fit button on the toolbar and it does recover this. A state you have
 * to know a particular button to escape is still worth not having: losing your place
 * is the one thing an editor exists to prevent.
 */
class EditorPanTest {

    private static final float REGION = 800.0f;

    /** Anything at all of the canvas still inside the pane. */
    private static boolean visible(float pan, double content) {
        return pan + content > 0 && pan < REGION;
    }

    @Test
    @DisplayName("dragging a long way in any direction leaves the canvas reachable")
    void aLongDragCannotPushTheCanvasOut() {
        double content = 400.0;

        for (float attempt : new float[] {-100_000f, -5_000f, -900f, 0f, 900f, 5_000f, 100_000f}) {
            float pan = EditorPanel.clampPan(attempt, content, REGION);
            assertTrue(visible(pan, content),
                    () -> "panning to " + attempt + " left nothing on screen, clamped to " + pan);
        }
    }

    @Test
    @DisplayName("a pan that was already fine is left alone")
    void anOrdinaryPanIsUntouched() {
        assertEquals(120.0f, EditorPanel.clampPan(120.0f, 400.0, REGION));
        assertEquals(-50.0f, EditorPanel.clampPan(-50.0f, 400.0, REGION));
    }

    /**
     * Panning most of the canvas away is the point of panning, so the clamp has to
     * allow it. Only the last strip is held back.
     */
    @Test
    @DisplayName("working on one corner of a large canvas still works")
    void mostOfALargeCanvasMayGoOffScreen() {
        double content = 6000.0;
        float pan = EditorPanel.clampPan(-5_800f, content, REGION);

        assertTrue(pan < -5_000f,
                () -> "the clamp will not let you reach the far corner, stopped at " + pan);
        assertTrue(visible(pan, content), "and it went too far anyway");
    }

    /**
     * A pane barely larger than the margin makes the two bounds cross.
     *
     * <p>Not a hypothetical. The editor's canvas pane shrinks with the window and
     * with the side panels, and at a small GUI scale on an 854 pixel window it gets
     * genuinely tiny. Math.clamp throws when its low bound is above its high one, so
     * the failure here is not a jittering canvas, it is an exception thrown mid-frame
     * from a right-drag.
     *
     * <p>My first version of this used an eight hundred pixel pane, where the bounds
     * cannot cross at all, so it passed with the guard deleted.
     */
    @Test
    @DisplayName("a pane barely larger than the margin does not throw")
    void aTinyPaneDoesNotThrow() {
        for (float pane : new float[] {10.0f, 40.0f, 60.0f, 96.0f}) {
            for (double content : new double[] {4.0, 30.0, 200.0}) {
                float first = assertDoesNotThrow(() -> EditorPanel.clampPan(500.0f, content, pane),
                        "clamping threw for a " + pane + "px pane holding " + content + "px of canvas");
                float second = EditorPanel.clampPan(first, content, pane);
                assertEquals(first, second, "clamping twice moved it again, so it will never sit still");
            }
        }
    }
}
