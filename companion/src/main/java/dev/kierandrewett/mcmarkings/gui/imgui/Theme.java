package dev.kierandrewett.mcmarkings.gui.imgui;

/**
 * The interface palette, and the arithmetic to check it is readable.
 *
 * <p>Colours live here rather than inline in the styling call so they can be
 * tested. Contrast is not a matter of taste: it has a formula, and a palette that
 * looks fine to whoever chose it can still be unreadable to someone else, on a
 * dimmer monitor, or through the transparency of a game window. Guessing is what
 * produces interfaces people quietly give up on.
 *
 * <p>Values follow Minecraft's own look, which is near-black panels and mid grey
 * raised controls, but only within the range where the text on top still passes.
 */
public final class Theme {

    /**
     * Minimum contrast for ordinary text, from WCAG 2.1 AA.
     *
     * <p>Chosen because it is the widely agreed threshold rather than because
     * anything here is legally bound by it. Below this, text is legible to some
     * people and not others, which is the worst case: nobody reports it.
     */
    public static final double MINIMUM_TEXT_CONTRAST = 4.5;

    /** The relaxed threshold, for text that is deliberately de-emphasised. */
    public static final double MINIMUM_MUTED_CONTRAST = 3.0;

    /** Panels sit over the game, so they are dark and mostly opaque. */
    public static final int WINDOW_BACKGROUND = 0xF00F0F0F;

    public static final int CHILD_BACKGROUND = 0x4D000000;

    public static final int POPUP_BACKGROUND = 0xF50F0F0F;

    public static final int BORDER = 0xE6000000;

    /** Slightly warm off-white, as the game uses, rather than pure white. */
    public static final int TEXT = 0xFFDEDEDE;

    /** De-emphasised, but still required to clear the muted threshold. */
    public static final int TEXT_MUTED = 0xFF8C8C8C;

    /** Sunken fields, matching a vanilla text box. */
    public static final int FIELD = 0xC7000000;

    public static final int FIELD_HOVERED = 0xE61A1A1A;

    public static final int FIELD_ACTIVE = 0xF2242424;

    /**
     * Raised controls.
     *
     * <p>Darker than a vanilla button looks, deliberately. The obvious mid grey put
     * the label at about 4:1 against its own background, which fails, and nothing
     * about a button is worth making its text harder to read.
     */
    public static final int BUTTON = 0xFF424242;

    public static final int BUTTON_HOVERED = 0xFF5C5C5C;

    public static final int BUTTON_ACTIVE = 0xFF333333;

    public static final int HEADER = 0xFF3D3D3D;

    public static final int HEADER_HOVERED = 0xFF525252;

    public static final int HEADER_ACTIVE = 0xFF5C5C5C;

    public static final int SCROLLBAR_BACKGROUND = 0x99000000;

    public static final int SCROLLBAR_GRAB = 0xFF5C5C5C;

    public static final int SCROLLBAR_GRAB_HOVERED = 0xFF7A7A7A;

    /** Green enough to read as confirmation without being a different language. */
    public static final int CHECK_MARK = 0xFF5FBF5F;

    public static final int SLIDER_GRAB = 0xFF8C8C8C;

    public static final int SLIDER_GRAB_ACTIVE = 0xFFADADAD;

    public static final int SEPARATOR = 0xCC000000;

    public static final int TAB = 0xFF2B2B2B;

    public static final int TAB_HOVERED = 0xFF525252;

    /** The selected tab, which has to be obvious without relying on colour alone. */
    public static final int TAB_ACTIVE = 0xFF474747;

    /**
     * Something failed.
     *
     * <p>These four were written inline as raw floats in two dozen places and had
     * never been checked against anything. Colour is also the weakest signal there
     * is: red and amber are the classic pair people cannot tell apart, so the
     * wording at each site has to carry the meaning on its own and the colour is
     * only there to help whoever can see it.
     */
    public static final int ERROR = 0xFFF27373;

    /** Something worth knowing about that is not a failure. */
    public static final int WARNING = 0xFFF2C759;

    /** Something worked. */
    public static final int SUCCESS = 0xFF73DA73;

    /** A section heading, for the few places that are prose rather than controls. */
    public static final int HEADING = 0xFFFAD96B;

    /**
     * The ring around whatever the keyboard is on.
     *
     * <p>Bright and fully opaque on purpose. ImGui's default is a faint blue that
     * disappears against a dark panel over a game scene, and a focus ring nobody can
     * see makes keyboard navigation useless: you can move, but not know where you
     * are. It is the one part of navigating by keyboard that has to be unmissable.
     */
    public static final int FOCUS_RING = 0xFFFFC94D;

    private Theme() {
    }

    public static float red(int argb) {
        return ((argb >> 16) & 0xFF) / 255.0f;
    }

    public static float green(int argb) {
        return ((argb >> 8) & 0xFF) / 255.0f;
    }

    public static float blue(int argb) {
        return (argb & 0xFF) / 255.0f;
    }

    public static float alpha(int argb) {
        return ((argb >>> 24) & 0xFF) / 255.0f;
    }

    /**
     * WCAG contrast ratio between two opaque colours, from 1 to 21.
     *
     * <p>Alpha is ignored, so callers must pass the colour as it ends up on screen.
     * Compositing a translucent panel first is the caller's job, because only the
     * caller knows what is behind it.
     */
    public static double contrastRatio(int foreground, int background) {
        double lighter = Math.max(relativeLuminance(foreground), relativeLuminance(background));
        double darker = Math.min(relativeLuminance(foreground), relativeLuminance(background));
        return (lighter + 0.05) / (darker + 0.05);
    }

    /** Perceived brightness, as WCAG defines it. */
    public static double relativeLuminance(int argb) {
        return 0.2126 * linear(red(argb)) + 0.7152 * linear(green(argb)) + 0.0722 * linear(blue(argb));
    }

    private static double linear(double channel) {
        return channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    /**
     * Flattens a translucent colour onto an opaque one.
     *
     * <p>Needed because most of this palette is partly transparent, and the contrast
     * of a colour nobody actually sees is not worth checking.
     */
    public static int over(int foreground, int background) {
        double weight = alpha(foreground);
        int red = (int) Math.round((red(foreground) * weight + red(background) * (1 - weight)) * 255);
        int green = (int) Math.round((green(foreground) * weight + green(background) * (1 - weight)) * 255);
        int blue = (int) Math.round((blue(foreground) * weight + blue(background) * (1 - weight)) * 255);
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }
}
