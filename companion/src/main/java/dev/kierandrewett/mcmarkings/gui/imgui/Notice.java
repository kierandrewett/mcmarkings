package dev.kierandrewett.mcmarkings.gui.imgui;

import imgui.ImGui;

/**
 * Coloured text that means something.
 *
 * <p>These colours were written inline as raw floats in about two dozen places,
 * which made them impossible to check and easy to drift. They live in {@link Theme}
 * now and are drawn through here, so there is one definition of what a warning looks
 * like and a test can say whether it is readable.
 *
 * <p>Colour is deliberately not the whole signal. Red and amber are the classic pair
 * people cannot distinguish, and the game window sits over whatever scene is behind
 * it. So the wording at each site carries the meaning by itself: a warning reads as
 * a warning with the colour removed, and nothing here says "this is bad" only by
 * being red. That is the rule this class exists to make easy to follow, not
 * something it can enforce.
 */
public final class Notice {

    private Notice() {
    }

    /**
     * Something failed. The text must say what, without relying on being red.
     *
     * <p>Adds the note about a stale jar when the failure looks like one, the same as
     * the status line does, so it does not matter which of the two a given screen
     * happens to report through.
     */
    public static void error(String text) {
        coloured(Theme.ERROR, withAdvice(text));
    }

    private static String withAdvice(String text) {
        String advice = dev.kierandrewett.mcmarkings.core.Diagnosis.adviceFor(text);
        return advice.isEmpty() ? text : text + "  " + advice;
    }

    /** Worth knowing, not a failure. The text must read as a caution on its own. */
    public static void warning(String text) {
        coloured(Theme.WARNING, text);
    }

    public static void success(String text) {
        coloured(Theme.SUCCESS, text);
    }

    /** A heading, in the few places that are prose rather than controls. */
    public static void heading(String text) {
        coloured(Theme.HEADING, text);
    }

    /** Wrapping variants, for anything long enough to reach the edge of a panel. */
    public static void errorWrapped(String text) {
        wrapped(Theme.ERROR, withAdvice(text));
    }

    public static void warningWrapped(String text) {
        wrapped(Theme.WARNING, text);
    }

    private static void coloured(int argb, String text) {
        ImGui.textColored(Theme.red(argb), Theme.green(argb), Theme.blue(argb), Theme.alpha(argb), text);
    }

    private static void wrapped(int argb, String text) {
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, Theme.red(argb), Theme.green(argb),
                Theme.blue(argb), Theme.alpha(argb));
        ImGui.textWrapped(text);
        ImGui.popStyleColor();
    }
}
