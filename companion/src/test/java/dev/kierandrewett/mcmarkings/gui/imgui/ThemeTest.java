package dev.kierandrewett.mcmarkings.gui.imgui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether the interface is actually readable.
 *
 * <p>Contrast has a formula, so this is checkable rather than a matter of opinion.
 * A palette that looks fine to whoever picked it can still be unreadable on a
 * dimmer monitor or through the transparency of a game window, and the failure
 * mode is quiet: people give up rather than report it.
 *
 * <p>Every text colour is checked against the surface it actually sits on, with
 * translucency flattened first, because the contrast of a colour nobody sees is
 * not worth measuring.
 */
class ThemeTest {

    /** What the panel ends up looking like over a dark game scene. */
    private static final int OVER_GAME = 0xFF000000;

    private static int surface(int colour) {
        return Theme.over(colour, OVER_GAME);
    }

    private static void assertReadable(String what, int foreground, int background, double minimum) {
        int flatBackground = surface(background);
        double ratio = Theme.contrastRatio(surface(Theme.over(foreground, flatBackground)), flatBackground);
        assertTrue(ratio >= minimum,
                () -> String.format("%s is %.2f:1, needs %.1f:1", what, ratio, minimum));
    }

    @Test
    @DisplayName("body text is readable on every surface it appears on")
    void bodyTextIsReadable() {
        assertReadable("text on a panel", Theme.TEXT, Theme.WINDOW_BACKGROUND, Theme.MINIMUM_TEXT_CONTRAST);
        assertReadable("text on a popup", Theme.TEXT, Theme.POPUP_BACKGROUND, Theme.MINIMUM_TEXT_CONTRAST);
        assertReadable("text in a field", Theme.TEXT, Theme.FIELD, Theme.MINIMUM_TEXT_CONTRAST);
        assertReadable("text in a focused field", Theme.TEXT, Theme.FIELD_ACTIVE, Theme.MINIMUM_TEXT_CONTRAST);
    }

    @Test
    @DisplayName("button labels are readable, including while hovered")
    void buttonLabelsAreReadable() {
        // The obvious mid grey for a vanilla-looking button put this at about 4:1,
        // which fails. Hover states count too: a label that becomes hard to read the
        // moment you point at it is worse than one that never changes.
        assertReadable("button label", Theme.TEXT, Theme.BUTTON, Theme.MINIMUM_TEXT_CONTRAST);
        assertReadable("hovered button label", Theme.TEXT, Theme.BUTTON_HOVERED, Theme.MINIMUM_TEXT_CONTRAST);
        assertReadable("pressed button label", Theme.TEXT, Theme.BUTTON_ACTIVE, Theme.MINIMUM_TEXT_CONTRAST);
    }

    @Test
    @DisplayName("selected rows and tabs keep their labels readable")
    void selectionSurfacesAreReadable() {
        assertReadable("selected row", Theme.TEXT, Theme.HEADER, Theme.MINIMUM_TEXT_CONTRAST);
        assertReadable("hovered row", Theme.TEXT, Theme.HEADER_HOVERED, Theme.MINIMUM_TEXT_CONTRAST);
        assertReadable("active row", Theme.TEXT, Theme.HEADER_ACTIVE, Theme.MINIMUM_TEXT_CONTRAST);
        assertReadable("tab label", Theme.TEXT, Theme.TAB, Theme.MINIMUM_TEXT_CONTRAST);
        assertReadable("selected tab label", Theme.TEXT, Theme.TAB_ACTIVE, Theme.MINIMUM_TEXT_CONTRAST);
    }

    @Test
    @DisplayName("muted text is de-emphasised without becoming unreadable")
    void mutedTextStillClearsTheLowerBar() {
        // Deliberately below the body threshold, since that is the point of it, but
        // it still has to be readable rather than decorative.
        assertReadable("muted text", Theme.TEXT_MUTED, Theme.WINDOW_BACKGROUND, Theme.MINIMUM_MUTED_CONTRAST);
        assertReadable("muted text on a popup", Theme.TEXT_MUTED, Theme.POPUP_BACKGROUND,
                Theme.MINIMUM_MUTED_CONTRAST);
    }

    @Test
    @DisplayName("muted text is genuinely quieter than body text")
    void mutedTextIsActuallyMuted() {
        int panel = surface(Theme.WINDOW_BACKGROUND);
        double body = Theme.contrastRatio(surface(Theme.TEXT), panel);
        double muted = Theme.contrastRatio(surface(Theme.TEXT_MUTED), panel);

        assertTrue(muted < body, "muted text should read as secondary, not identical to body text");
    }

    @Test
    @DisplayName("a selected tab is distinguishable from an unselected one")
    void selectedTabIsDistinguishable() {
        // Selection cannot rest on colour alone, but the colours still have to
        // differ enough to be seen at all.
        double difference = Math.abs(Theme.relativeLuminance(surface(Theme.TAB_ACTIVE))
                - Theme.relativeLuminance(surface(Theme.TAB)));

        assertTrue(difference > 0.01, "the selected tab is nearly the same shade as the rest: " + difference);
    }

    @Test
    @DisplayName("hover states are visible without being jarring")
    void hoverStatesAreVisible() {
        for (int[] pair : new int[][] {
                {Theme.BUTTON, Theme.BUTTON_HOVERED},
                {Theme.HEADER, Theme.HEADER_HOVERED},
                {Theme.SCROLLBAR_GRAB, Theme.SCROLLBAR_GRAB_HOVERED}}) {
            double resting = Theme.relativeLuminance(surface(pair[0]));
            double hovered = Theme.relativeLuminance(surface(pair[1]));

            assertTrue(hovered > resting, "hovering should brighten, not darken");
            assertTrue(hovered - resting > 0.005, "the hover change is too small to notice");
        }
    }

    @Test
    @DisplayName("warnings and errors are readable, on panels and in popups")
    void noticeColoursAreReadable() {
        // These four were written inline as raw floats in two dozen places and had
        // never been checked against anything at all. They carry the messages people
        // most need to read and least want to squint at.
        for (int surface : new int[] {Theme.WINDOW_BACKGROUND, Theme.POPUP_BACKGROUND, Theme.CHILD_BACKGROUND}) {
            assertReadable("error text", Theme.ERROR, surface, Theme.MINIMUM_TEXT_CONTRAST);
            assertReadable("warning text", Theme.WARNING, surface, Theme.MINIMUM_TEXT_CONTRAST);
            assertReadable("success text", Theme.SUCCESS, surface, Theme.MINIMUM_TEXT_CONTRAST);
            assertReadable("heading text", Theme.HEADING, surface, Theme.MINIMUM_TEXT_CONTRAST);
        }
    }

    @Test
    @DisplayName("a warning and an error do not rely on being told apart by colour")
    void warningAndErrorAreNotDistinguishedByColourAlone() {
        // Red and amber are the pair most people with a colour vision deficiency
        // cannot separate, and this palette makes no attempt to solve that: the two
        // are genuinely close in luminance as well.
        //
        // The test exists to record that, so nobody later reads the colours as doing
        // work they do not do. What actually carries the difference is the wording at
        // each site, which is why Notice says so and why no message in this interface
        // means "something is wrong" only by being red.
        double error = Theme.relativeLuminance(surface(Theme.ERROR));
        double warning = Theme.relativeLuminance(surface(Theme.WARNING));

        assertTrue(Math.abs(error - warning) < 0.5,
                "if these ever became far apart in brightness, the wording rule could be relaxed");
    }

    @Test
    @DisplayName("the contrast maths matches the published examples")
    void contrastMathsIsCorrect() {
        // Black on white is the defined maximum, and a colour against itself is 1.
        assertEquals(21.0, Theme.contrastRatio(0xFF000000, 0xFFFFFFFF), 0.05);
        assertEquals(1.0, Theme.contrastRatio(0xFF808080, 0xFF808080), 0.001);

        // #767676 is the well-known lightest grey that still passes on white, which
        // makes it a good check: it should land just over the 4.5 threshold.
        assertEquals(4.54, Theme.contrastRatio(0xFF767676, 0xFFFFFFFF), 0.05);
        assertTrue(Theme.contrastRatio(0xFF767676, 0xFFFFFFFF) >= Theme.MINIMUM_TEXT_CONTRAST);
    }

    @Test
    @DisplayName("flattening a translucent colour behaves at the extremes")
    void compositingIsCorrect() {
        assertEquals(0xFFFFFFFF, Theme.over(0xFFFFFFFF, 0xFF000000), "opaque white covers black");
        assertEquals(0xFF000000, Theme.over(0x00FFFFFF, 0xFF000000), "fully transparent shows the backdrop");

        int half = Theme.over(0x80FFFFFF, 0xFF000000);
        assertEquals(128, half & 0xFF, 1, "half-transparent white should land mid grey");
    }
}
