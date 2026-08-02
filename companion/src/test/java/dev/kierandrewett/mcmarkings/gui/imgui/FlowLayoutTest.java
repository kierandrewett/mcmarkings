package dev.kierandrewett.mcmarkings.gui.imgui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that decides whether a row of buttons wraps.
 *
 * <p>Two lint rules make sure every button is placed through the flow helper, and
 * neither of them says the helper works: taking the wrapping out of it entirely left
 * the whole suite green. This is the part of it that can be reached without a running
 * ImGui context.
 */
class FlowLayoutTest {

    @Test
    @DisplayName("a control past the edge wraps")
    void tooWideWraps() {
        assertTrue(ImGuiScreens.wraps(700.0f, 200.0f, 854.0f));
    }

    @Test
    @DisplayName("a control that fits does not")
    void fittingStaysPut() {
        assertFalse(ImGuiScreens.wraps(100.0f, 200.0f, 854.0f));
    }

    /**
     * The boundary, which is the case worth being deliberate about. A control ending
     * exactly on the edge has fitted, and wrapping it looks like the layout being
     * broken rather than the control being too wide.
     */
    @Test
    @DisplayName("a control ending exactly on the edge stays on the row")
    void exactlyFittingStaysPut() {
        assertFalse(ImGuiScreens.wraps(654.0f, 200.0f, 854.0f));
        assertTrue(ImGuiScreens.wraps(654.1f, 200.0f, 854.0f));
    }

    /**
     * A pane too narrow for even one control. It wraps every time, which puts each on
     * its own row and clipped rather than in one row and clipped, and the first is at
     * least reachable.
     */
    @Test
    @DisplayName("a pane narrower than the control still wraps rather than looping")
    void anImpossiblyNarrowPaneStillDecides() {
        assertTrue(ImGuiScreens.wraps(0.0f, 200.0f, 50.0f));
    }
}
