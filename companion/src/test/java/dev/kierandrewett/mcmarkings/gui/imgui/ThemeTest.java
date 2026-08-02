package dev.kierandrewett.mcmarkings.gui.imgui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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
            // Any mention of the constant in this file counts. The original test asked
            // for the name followed by a comma, which was the shape of one assertion
            // helper and nothing else, so a colour checked any other way read as
            // unchecked. I widened that to allow a closing bracket, then hit the same
            // wall again on the last element of an array. A word boundary is the thing
            // actually meant: is this colour referred to here at all.
            boolean used = java.util.regex.Pattern.compile("Theme\\." + name + "\\b").matcher(source).find();
            if (DECORATIVE.contains(name) || used) {
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

    /**
     * The chequerboard has to be seen through the artwork on top of it.
     *
     * <p>Both tones were near-black, a shade apart, which sat quietly in a dark
     * interface and made a tenth of this repository invisible. Measured over the
     * images actually present: 29 of 361 sampled came in under 3:1 against both
     * tones and the worst was 1.01:1. They are the ones drawn dark on transparency,
     * so a black sign and a see-through one looked identical, which is the one thing
     * a chequerboard exists to tell apart.
     *
     * <p>Artwork here runs the whole range, so both ends are checked. A pair that
     * serves only dark art fails white arrows, and the obvious fix of raising the
     * dark tone into a mid grey is worse than either: it collides with the many
     * mid-toned signs, and measured three times the failures.
     */
    @Test
    @DisplayName("artwork at either end of the range is visible against the chequerboard")
    void artworkIsVisibleOnTheChequerboard() {
        for (int artwork : new int[] {0xFF000000, 0xFF1A1A1A, 0xFFFFFFFF, 0xFFEEEEEE}) {
            double best = Math.max(
                    Theme.contrastRatio(artwork, Theme.CHEQUER_DARK),
                    Theme.contrastRatio(artwork, Theme.CHEQUER_LIGHT));
            assertTrue(best >= Theme.MINIMUM_MUTED_CONTRAST,
                    () -> String.format("artwork %06X is %.2f:1 against the nearer tone, needs %.1f:1",
                            artwork & 0xFFFFFF, best, Theme.MINIMUM_MUTED_CONTRAST));
        }
    }

    @Test
    @DisplayName("the board reads as a board rather than as a flat colour")
    void theTwoTonesAreTellableApart() {
        double between = Theme.contrastRatio(Theme.CHEQUER_DARK, Theme.CHEQUER_LIGHT);
        assertTrue(between >= Theme.MINIMUM_MUTED_CONTRAST,
                () -> String.format("the two tones are %.2f:1 apart, which reads as one colour", between));
    }

    /**
     * Overlays are drawn over the chequerboard, so they meet both of its tones.
     *
     * <p>All four were single pale lines chosen when the board was uniformly
     * near-black. Giving it a light tone, so dark artwork could be seen at all,
     * dropped them to between 1.01:1 and 1.35:1 against half the cells: the canvas
     * edge went, and so did the guides that say where a dragged layer will land.
     * That was a regression introduced by the fix before it, which is the argument
     * for checking what a colour change does to everything drawn on top.
     *
     * <p>A mark carries on a backdrop if either its own colour or the halo under it
     * stands out against that backdrop. Darkening the colours instead would only
     * move the failure onto the dark cells.
     */
    @Test
    @DisplayName("every canvas overlay survives both tones of the chequerboard")
    void overlaysSurviveTheChequerboard() {
        int halo = Theme.over(Theme.OVERLAY_HALO, Theme.CHEQUER_LIGHT);
        int haloOnDark = Theme.over(Theme.OVERLAY_HALO, Theme.CHEQUER_DARK);

        // The frame grid is not here on purpose. It is a quarter opaque and meant to
        // be, because it marks where item frames divide and a bold line across the
        // artwork someone is composing would be worse than a faint one. It is held to
        // its own floor below rather than to a reading threshold it should not meet.
        for (int overlay : new int[] {Theme.CANVAS_EDGE, Theme.SELECTION, Theme.SNAP_GUIDE}) {
            for (int tone : new int[] {Theme.CHEQUER_DARK, Theme.CHEQUER_LIGHT}) {
                int drawn = Theme.over(overlay, tone == Theme.CHEQUER_LIGHT ? halo : haloOnDark);
                double best = Math.max(Theme.contrastRatio(drawn, tone),
                        Theme.contrastRatio(tone == Theme.CHEQUER_LIGHT ? halo : haloOnDark, tone));
                assertTrue(best >= Theme.MINIMUM_MUTED_CONTRAST,
                        () -> String.format("overlay %08X on tone %06X is %.2f:1, needs %.1f:1",
                                overlay, tone & 0xFFFFFF, best, Theme.MINIMUM_MUTED_CONTRAST));
            }
        }
    }

    /**
     * The halo only works if the line still reads against it. A dark overlay over a
     * dark halo is one shape, not a line with an outline.
     */
    @Test
    @DisplayName("an overlay stands out from its own halo")
    void overlaysStandOutFromTheirHalo() {
        int halo = Theme.over(Theme.OVERLAY_HALO, Theme.CHEQUER_LIGHT);

        for (int overlay : new int[] {Theme.CANVAS_EDGE, Theme.SELECTION, Theme.SNAP_GUIDE}) {
            double ratio = Theme.contrastRatio(Theme.over(overlay, halo), halo);
            assertTrue(ratio >= Theme.MINIMUM_MUTED_CONTRAST,
                    () -> String.format("overlay %08X is %.2f:1 against its own halo", overlay, ratio));
        }
    }

    /**
     * The frame grid is faint on purpose, which is not the same as absent.
     *
     * <p>Held to a floor rather than to the reading threshold. Something has to stop
     * it drifting to invisible, and something has to stop the next person "fixing"
     * it up to a bold line across the artwork.
     */
    @Test
    @DisplayName("the frame grid stays faint, and stays there")
    void theFrameGridIsFaintButPresent() {
        double onDark = Theme.contrastRatio(
                Theme.over(Theme.FRAME_GRID, Theme.CHEQUER_DARK), Theme.CHEQUER_DARK);
        double onLight = Theme.contrastRatio(
                Theme.over(Theme.FRAME_GRID, Theme.CHEQUER_LIGHT), Theme.CHEQUER_LIGHT);

        assertTrue(Math.max(onDark, onLight) > 1.25,
                () -> String.format("the grid is invisible on both tones: %.2f:1 and %.2f:1", onDark, onLight));
        assertTrue(Math.max(onDark, onLight) < Theme.MINIMUM_TEXT_CONTRAST,
                () -> "the grid is now loud enough to compete with the artwork under it");
    }

    /**
     * The colours above are only right if the halo is actually drawn.
     *
     * <p>Written after removing the halo from the line helper and watching every
     * assertion above still pass. They check the palette, which is maths, and the
     * halo is a draw call, which needs a running ImGui context and cannot be called
     * here. So this reads the helper instead.
     *
     * <p>Structural and slightly crude, and better than the alternative, which was a
     * set of green tests describing a guard that was no longer there.
     */
    @Test
    @DisplayName("the overlay helpers draw the halo as well as the line")
    void theHaloIsActuallyDrawn() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/dev/kierandrewett/mcmarkings/gui/imgui/ImGuiScreens.java"));

        // Every helper whose name says overlay, found rather than listed. I wrote this
        // check with two names in it, added a third helper the next day, and the
        // mutation that deleted its halo passed. A list of names is a thing to forget
        // to update; asking the file which helpers exist is not.
        java.util.regex.Matcher helpers = java.util.regex.Pattern
                .compile("public static void (overlay\\w+)\\(").matcher(source);

        int found = 0;
        while (helpers.find()) {
            String helper = helpers.group(1);
            found++;

            String body = source.substring(helpers.end(), source.indexOf("\n    }", helpers.end()));

            // Statements, not lines. This check has now been wrong twice in the same
            // way: first it counted lines mentioning a halo, which over-counted the
            // helper that names the colour once and uses it four times, and then it
            // counted per line, which missed a halo argument that had wrapped onto a
            // continuation line. A draw call is a statement, so split on those.
            List<String> draws = java.util.Arrays.stream(body.split(";"))
                    .map(statement -> statement.replaceAll("\\s+", " "))
                    .filter(statement -> statement.contains("drawList.add"))
                    .toList();
            long haloDraws = draws.stream().filter(statement -> statement.contains("halo")).count();
            long markDraws = draws.size() - haloDraws;

            assertTrue(haloDraws >= 1, helper + " draws no halo, so nothing carries it on a light cell");
            assertTrue(markDraws >= 1, helper + " draws only a halo and never the mark itself");
        }
        assertTrue(found >= 3, "expected the overlay helpers to still be here, found " + found);
    }

    /**
     * Labels sit on the chequerboard too, and that is the surface people read.
     *
     * <p>The chequerboard change took the browser caption from 8.69:1 to 1.37:1 on a
     * light cell. Every image name in the grid, which is the main thing anyone looks
     * at in this mod, wherever it crossed a light square. I found it by asking what
     * else draws over that backdrop rather than by noticing it, which is the check
     * the change itself should have carried, twice now.
     *
     * <p>The caption gets a band rather than an outline because the cell already
     * reserves the strip, and one filled rect beats five text draws per visible cell.
     */
    @Test
    @DisplayName("a caption is readable over either tone of the chequerboard")
    void captionsAreReadableOnTheChequerboard() {
        for (int tone : new int[] {Theme.CHEQUER_DARK, Theme.CHEQUER_LIGHT}) {
            int band = Theme.over(Theme.CAPTION_BACKING, tone);
            assertReadable("a caption on the chequerboard", Theme.TEXT, band, Theme.MINIMUM_TEXT_CONTRAST);
        }
    }

    /**
     * The band has to stay see-through enough to be worth the compromise. It sits
     * over the image it names, and a solid bar hides part of what someone is trying
     * to look at.
     */
    @Test
    @DisplayName("the caption band does not become an opaque bar")
    void theCaptionBandStaysPartlyTransparent() {
        double opacity = Theme.alpha(Theme.CAPTION_BACKING);
        assertTrue(opacity < 0.95, () -> "the band is effectively solid at " + opacity);
        assertTrue(opacity > 0.6, () -> "the band is too faint to carry the text at " + opacity);
    }

    /**
     * The focus ring, which is the whole of navigating without a mouse.
     *
     * <p>It is drawn by ImGui at the edge of whatever has focus, so unlike the
     * canvas overlays it cannot be given a halo. Against a light chequer square the
     * amber measures 1.20:1 and is simply absent, and no single colour fixes that:
     * meeting 3:1 against both a 0xBF square and a 0x2E one at the same time is
     * arithmetically impossible, which is what the halo exists to get around
     * everywhere it can be used.
     *
     * <p>So the browser leaves a gutter of panel at the edge of each cell and the
     * ring lands there instead. This pins the surface it is guaranteed against.
     */
    @Test
    @DisplayName("the focus ring is unmissable on the surface it is drawn against")
    void theFocusRingIsVisibleWhereItLands() {
        assertReadable("the focus ring on a panel", Theme.FOCUS_RING, Theme.WINDOW_BACKGROUND,
                Theme.MINIMUM_MUTED_CONTRAST);
        assertReadable("the focus ring on a control", Theme.FOCUS_RING, Theme.BUTTON,
                Theme.MINIMUM_MUTED_CONTRAST);

        // Stated rather than implied. If this ever passes, a single colour has become
        // possible and the gutter in the browser is no longer earning its keep.
        double onLightChequer = Theme.contrastRatio(Theme.FOCUS_RING, Theme.CHEQUER_LIGHT);
        assertTrue(onLightChequer < Theme.MINIMUM_MUTED_CONTRAST,
                () -> String.format("the ring now reads on a light chequer square at %.2f:1, "
                        + "so the browser gutter can go", onLightChequer));
    }

    /**
     * That the gutter is there, not merely that it would help.
     *
     * <p>The same trap as the overlay halos and the caption: the maths above is a
     * fact about two colours and says nothing about what the browser draws.
     */
    @Test
    @DisplayName("the browser insets its chequerboard so the ring has panel to sit on")
    void theBrowserLeavesTheGutter() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/dev/kierandrewett/mcmarkings/gui/imgui/panel/ImageBrowserPanel.java"));

        int at = source.indexOf("ImGuiScreens.chequerboard(drawList, x");
        assertTrue(at > 0, "the grid cell no longer draws a chequerboard");

        String call = source.substring(at, source.indexOf(";", at));
        assertTrue(call.contains("FOCUS_GUTTER"),
                "the cell chequerboard fills the whole cell again, so the focus ring is back on it");
    }
}
