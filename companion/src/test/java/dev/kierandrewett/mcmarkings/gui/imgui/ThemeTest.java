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
    @DisplayName("the keyboard focus ring is impossible to miss")
    void focusRingIsVisible() {
        // A focus ring nobody can see makes keyboard navigation useless: you can move
        // through the interface but never know where you are. ImGui's default is a
        // faint blue that disappears on a dark panel over a game scene.
        //
        // Held to the 3:1 non-text threshold against every surface it is drawn over,
        // and to a wider gap than an ordinary border, since telling "focused" from
        // "just a control" is the entire job.
        for (int surface : new int[] {Theme.WINDOW_BACKGROUND, Theme.POPUP_BACKGROUND, Theme.CHILD_BACKGROUND}) {
            assertReadable("focus ring", Theme.FOCUS_RING, surface, Theme.MINIMUM_MUTED_CONTRAST);
        }

        double ring = Theme.relativeLuminance(surface(Theme.FOCUS_RING));
        double border = Theme.relativeLuminance(surface(Theme.BORDER));
        assertTrue(ring - border > 0.3, "the ring has to stand out from an ordinary border");
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

    @Test
    @DisplayName("text is readable on every surface it can land on, hovered included")
    void hoveredSurfacesAreReadableToo() {
        // Field and tab both had their resting and selected states checked and their
        // hovered ones missed, though text sits on all of them. Buttons were done
        // properly, which is what made the omission easy to see once listed.
        assertReadable("text in a hovered field", Theme.TEXT, Theme.FIELD_HOVERED,
                Theme.MINIMUM_TEXT_CONTRAST);
        assertReadable("hovered tab label", Theme.TEXT, Theme.TAB_HOVERED,
                Theme.MINIMUM_TEXT_CONTRAST);
    }

    @Test
    @DisplayName("marks that carry meaning are visible without being text")
    void functionalMarksAreVisible() {
        // A checkbox tick and a slider grab are the only thing saying what a control
        // is set to. Held to the 3:1 non-text threshold against the surface each is
        // actually drawn on, which for both is a sunken field rather than the panel.
        assertReadable("checkbox tick", Theme.CHECK_MARK, Theme.FIELD, Theme.MINIMUM_MUTED_CONTRAST);
        assertReadable("slider grab", Theme.SLIDER_GRAB, Theme.FIELD, Theme.MINIMUM_MUTED_CONTRAST);
        assertReadable("held slider grab", Theme.SLIDER_GRAB_ACTIVE, Theme.FIELD,
                Theme.MINIMUM_MUTED_CONTRAST);
    }

    /**
     * Colours that carry no meaning on their own, and so need no contrast check.
     *
     * <p>Listed rather than inferred, so adding one is a decision someone made rather
     * than a check someone forgot.
     */
    private static final java.util.Set<String> DECORATIVE = java.util.Set.of(
            "WINDOW_BACKGROUND", "CHILD_BACKGROUND", "POPUP_BACKGROUND", "BORDER", "SEPARATOR",
            "FIELD", "FIELD_HOVERED", "FIELD_ACTIVE", "BUTTON", "BUTTON_HOVERED", "BUTTON_ACTIVE",
            "HEADER", "HEADER_HOVERED", "HEADER_ACTIVE", "TAB", "TAB_HOVERED", "TAB_ACTIVE",
            "SCROLLBAR_BACKGROUND", "SCROLLBAR_GRAB", "SCROLLBAR_GRAB_HOVERED");

    @Test
    @DisplayName("every colour in the palette has been thought about")
    void noColourEscapesTheChecks() throws Exception {
        // The checks above cover the colours I remembered to list, which is exactly
        // the guarantee that rots. This asks the class what colours it has, so a new
        // one forces a decision: either it is drawn on something and needs a contrast
        // assertion, or it is a surface and goes in the list above with the rest.
        java.util.List<String> unclassified = new java.util.ArrayList<>();
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/test/java/dev/kierandrewett/mcmarkings/gui/imgui/ThemeTest.java"));

        for (java.lang.reflect.Field field : Theme.class.getFields()) {
            if (field.getType() != int.class) {
                continue;
            }
            String name = field.getName();
            if (DECORATIVE.contains(name) || source.contains("Theme." + name + ",")) {
                continue;
            }
            unclassified.add(name);
        }

        assertTrue(unclassified.isEmpty(), () -> """
                A colour in the palette is neither checked for contrast nor listed as                 decorative. Add an assertion for what it is drawn on, or add it to                 DECORATIVE if it carries no meaning by itself.
                unclassified: """ + unclassified);
    }

    /** A colour faded the way ImGui fades everything inside beginDisabled. */
    private static int faded(int argb, float multiplier) {
        int alpha = Math.round(((argb >>> 24) & 0xFF) * multiplier);
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * Disabled controls, which nothing checked and this interface is full of.
     *
     * <p>beginDisabled fades the control and its label together, so both the text and
     * the surface under it move and the contrast between them is not the enabled one.
     * Publish, Get frames, and most of the editor toolbar spend much of their time
     * disabled, and each carries a tooltip saying why. A label too faint to read
     * makes that tooltip unreachable, because you cannot tell what you are hovering.
     */
    @Test
    @DisplayName("a disabled control is still readable, on every surface one appears on")
    void disabledControlsStayReadable() {
        for (int control : new int[] {Theme.BUTTON, Theme.HEADER, Theme.FIELD, Theme.TAB}) {
            int surface = surface(Theme.over(faded(control, Theme.DISABLED_ALPHA), surface(Theme.WINDOW_BACKGROUND)));
            assertReadable("a disabled label", faded(Theme.TEXT, Theme.DISABLED_ALPHA),
                    surface, Theme.MINIMUM_TEXT_CONTRAST);
        }
    }

    /**
     * The fade has almost no room beneath it.
     *
     * <p>Worth pinning as its own fact rather than leaving implied by the check
     * above. The obvious future edit is to fade disabled controls further so they
     * read as more clearly unavailable, and the margin for that is under a twentieth.
     */
    @Test
    void thereIsNoRoomToFadeDisabledControlsFurther() {
        int window = surface(Theme.WINDOW_BACKGROUND);
        int surface = Theme.over(faded(Theme.BUTTON, 0.55f), window);
        double ratio = Theme.contrastRatio(Theme.over(faded(Theme.TEXT, 0.55f), surface), surface);

        assertTrue(ratio < Theme.MINIMUM_TEXT_CONTRAST,
                () -> String.format("0.55 now passes at %.2f:1, so this note is stale", ratio));
        assertTrue(Theme.DISABLED_ALPHA >= 0.60f,
                "fading below 0.60 puts disabled labels under " + Theme.MINIMUM_TEXT_CONTRAST + ":1");
    }
}
