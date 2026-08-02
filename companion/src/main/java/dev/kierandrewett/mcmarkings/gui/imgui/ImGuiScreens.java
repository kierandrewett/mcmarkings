package dev.kierandrewett.mcmarkings.gui.imgui;

import com.mojang.blaze3d.platform.Window;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.texture.TextureHandle;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiConfigFlags;
import imgui.ImGuiStyle;
import imgui.ImGuiViewport;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiWindowFlags;
import net.minecraft.client.Minecraft;

import java.util.OptionalInt;

/**
 * Shared plumbing for the ImGui editor screens.
 *
 * <p>The two things every surface here needs are a texture id ImGui will accept
 * and a window that fills the game window. Both are small but easy to get subtly
 * wrong, so they live in one place rather than being retyped per screen.
 */
public final class ImGuiScreens {

    /**
     * Sentinel for "this texture cannot be sampled by ImGui".
     *
     * <p>{@link TextureHandle#glId()} is empty on non-OpenGL backends, and ImGui's
     * renderer only understands raw GL names. Drawing a placeholder is the only
     * honest option; handing zero to ImGui would sample whatever texture happens
     * to be bound to unit 0.
     */
    public static final long NO_TEXTURE = -1L;

    /** Docked-editor window: fixed to the viewport, no chrome, nothing persisted. */
    private static final int FULL_WINDOW_FLAGS = ImGuiWindowFlags.NoTitleBar
            | ImGuiWindowFlags.NoResize
            | ImGuiWindowFlags.NoMove
            | ImGuiWindowFlags.NoCollapse
            | ImGuiWindowFlags.NoBringToFrontOnFocus
            | ImGuiWindowFlags.NoSavedSettings;

    private ImGuiScreens() {
    }

    private static boolean themed;

    /**
     * Restyles ImGui to sit alongside Minecraft's own interface.
     *
     * <p>The single biggest cue is corners. Minecraft's UI has no rounding anywhere,
     * so ImGui's default rounded panels read as a different application pasted over
     * the game. Everything else follows vanilla's palette: near-black translucent
     * panels like the screen background, mid grey raised controls, and the muted
     * grey-white text the game uses rather than pure white.
     *
     * <p>Applied once. The ImGui context is process-wide, so repeating it is waste
     * rather than harm, but it also has to survive being called from either screen.
     */
    /**
     * Turns keyboard navigation on or off for this frame.
     *
     * <p>Without it nothing in the interface can be reached without a mouse: buttons,
     * checkboxes, sliders and fields are all click-only, and the shortcuts and the
     * palette only cover things somebody thought to name as a command. That is a poor
     * answer for a tool whose whole job is placing things by exact amounts.
     *
     * <p>Two flags, and the second is what makes the first usable here.
     * {@code NavEnableKeyboard} on its own forces {@code WantCaptureKeyboard} true
     * whenever any window has focus, and this window always does, so every shortcut
     * in the mod would be swallowed before it was dispatched.
     * {@code NavNoCaptureKeyboard} leaves that flag alone, so navigation works and
     * Ctrl+P, undo and the rest still arrive. Typing into a field still captures,
     * because that is an active item rather than navigation.
     *
     * <p>Turned off for the editor. Navigation claims Tab and the arrow keys, which
     * the editor already uses for stepping through layers and nudging by a pixel, and
     * both firing at once is worse than either. The editor is also the one panel that
     * can already be driven from the keyboard, so it loses nothing.
     */
    public static void setKeyboardNavigation(boolean enabled) {
        ImGuiIO io = ImGui.getIO();
        int flags = ImGuiConfigFlags.NavEnableKeyboard | ImGuiConfigFlags.NavNoCaptureKeyboard;
        if (enabled) {
            io.addConfigFlags(flags);
        } else {
            io.removeConfigFlags(flags);
        }
    }

    /** Cell size of the transparency chequerboard, in screen pixels. */
    public static final float CHEQUER_SIZE = 8.0f;

    /** Past this it is more draw calls than it is worth. */
    private static final long MAX_CHEQUER_CELLS = 4096;

    /**
     * A chequerboard, for anywhere a transparent image is shown.
     *
     * <p>Transparency drawn over a flat dark panel is indistinguishable from a dark
     * layer: a sign with a see-through background and one with a black one look
     * identical, and you find out which you had after it is on a wall. The
     * chequerboard is the usual way to tell them apart, and it belongs everywhere an
     * image is shown rather than only on the canvas someone happened to write it for.
     *
     * <p>Dropped rather than drawn past a cell count, since a large area would
     * otherwise be thousands of quads a frame.
     */
    public static void chequerboard(imgui.ImDrawList drawList, float left, float top,
            float right, float bottom) {
        if (right <= left || bottom <= top) {
            return;
        }

        drawList.addRectFilled(left, top, right, bottom, ImGui.getColorU32(0.16f, 0.16f, 0.17f, 1.0f));

        int columns = (int) Math.ceil((right - left) / CHEQUER_SIZE);
        int rows = (int) Math.ceil((bottom - top) / CHEQUER_SIZE);
        if (columns <= 0 || rows <= 0 || (long) columns * rows > MAX_CHEQUER_CELLS) {
            return;
        }

        int light = ImGui.getColorU32(0.22f, 0.22f, 0.23f, 1.0f);
        for (int row = 0; row < rows; row++) {
            for (int column = row % 2; column < columns; column += 2) {
                float cellX = left + column * CHEQUER_SIZE;
                float cellY = top + row * CHEQUER_SIZE;
                drawList.addRectFilled(cellX, cellY, Math.min(right, cellX + CHEQUER_SIZE),
                        Math.min(bottom, cellY + CHEQUER_SIZE), light);
            }
        }
    }

    public static void applyMinecraftTheme() {
        if (themed) {
            return;
        }
        themed = true;

        ImGuiStyle style = ImGui.getStyle();

        // Minecraft is blocky. Nothing in its interface is rounded.
        style.setWindowRounding(0.0f);
        style.setChildRounding(0.0f);
        style.setFrameRounding(0.0f);
        style.setPopupRounding(0.0f);
        style.setScrollbarRounding(0.0f);
        style.setGrabRounding(0.0f);
        style.setTabRounding(0.0f);

        // Vanilla widgets are outlined rather than shadowed.
        style.setWindowBorderSize(1.0f);
        style.setChildBorderSize(1.0f);
        style.setFrameBorderSize(1.0f);
        style.setPopupBorderSize(1.0f);

        style.setWindowPadding(8.0f, 8.0f);
        style.setFramePadding(6.0f, 4.0f);
        style.setItemSpacing(6.0f, 6.0f);
        style.setScrollbarSize(10.0f);
        style.setGrabMinSize(10.0f);

        setColour(ImGuiCol.WindowBg, Theme.WINDOW_BACKGROUND);
        setColour(ImGuiCol.ChildBg, Theme.CHILD_BACKGROUND);
        setColour(ImGuiCol.PopupBg, Theme.POPUP_BACKGROUND);
        setColour(ImGuiCol.Border, Theme.BORDER);

        // The keyboard focus ring, and the frame around the window while Ctrl+Tab is
        // held. Both default to a faint blue that vanishes on a dark panel.
        setColour(ImGuiCol.NavHighlight, Theme.FOCUS_RING);
        setColour(ImGuiCol.NavWindowingHighlight, Theme.FOCUS_RING);

        // Vanilla text is a slightly warm off-white, not pure white.
        setColour(ImGuiCol.Text, Theme.TEXT);
        setColour(ImGuiCol.TextDisabled, Theme.TEXT_MUTED);

        // Sunken fields, matching a vanilla text box.
        setColour(ImGuiCol.FrameBg, Theme.FIELD);
        setColour(ImGuiCol.FrameBgHovered, Theme.FIELD_HOVERED);
        setColour(ImGuiCol.FrameBgActive, Theme.FIELD_ACTIVE);

        // Raised controls, matching a vanilla button and its hover highlight.
        setColour(ImGuiCol.Button, Theme.BUTTON);
        setColour(ImGuiCol.ButtonHovered, Theme.BUTTON_HOVERED);
        setColour(ImGuiCol.ButtonActive, Theme.BUTTON_ACTIVE);

        setColour(ImGuiCol.Header, Theme.HEADER);
        setColour(ImGuiCol.HeaderHovered, Theme.HEADER_HOVERED);
        setColour(ImGuiCol.HeaderActive, Theme.HEADER_ACTIVE);

        setColour(ImGuiCol.TitleBg, Theme.TAB);
        setColour(ImGuiCol.TitleBgActive, Theme.TAB_ACTIVE);

        setColour(ImGuiCol.ScrollbarBg, Theme.SCROLLBAR_BACKGROUND);
        setColour(ImGuiCol.ScrollbarGrab, Theme.SCROLLBAR_GRAB);
        setColour(ImGuiCol.ScrollbarGrabHovered, Theme.SCROLLBAR_GRAB_HOVERED);

        setColour(ImGuiCol.CheckMark, Theme.CHECK_MARK);
        setColour(ImGuiCol.SliderGrab, Theme.SLIDER_GRAB);
        setColour(ImGuiCol.SliderGrabActive, Theme.SLIDER_GRAB_ACTIVE);

        setColour(ImGuiCol.Separator, Theme.SEPARATOR);
        setColour(ImGuiCol.Tab, Theme.TAB);
        setColour(ImGuiCol.TabHovered, Theme.TAB_HOVERED);
        setColour(ImGuiCol.TabSelected, Theme.TAB_ACTIVE);
    }

    /**
     * Takes the colour from {@link Theme}, where it has been checked for contrast.
     *
     * <p>Colours are not written inline here so the palette can be tested. Text that
     * fails against its own background is a quiet failure: people give up rather
     * than report it.
     */
    private static void setColour(int target, int argb) {
        ImGui.getStyle().setColor(target,
                Theme.red(argb), Theme.green(argb), Theme.blue(argb), Theme.alpha(argb));
    }

    /**
     * What the ImGui style is currently scaled by.
     *
     * <p>Tracked because {@code scaleAllSizes} multiplies the current metrics rather
     * than setting them, so re-applying a scale would compound. Static because the
     * ImGui context is process-wide and outlives any one screen.
     */
    private static float appliedScale = 1.0f;

    /**
     * Matches ImGui to Minecraft's own GUI scale.
     *
     * <p>ImGui lays out in raw framebuffer pixels, so on a high resolution display,
     * or with the GUI scale turned up, its text and controls come out a fraction of
     * the size of everything else on screen. Following the game's setting keeps the
     * two looking like one interface.
     *
     * <p>Call once per frame before any widgets. It returns immediately unless the
     * setting has actually changed.
     */
    public static void matchGameGuiScale() {
        Window window = Minecraft.getInstance().getWindow();
        if (window == null) {
            return;
        }

        float target = Math.max(1.0f, window.getGuiScale());
        if (Math.abs(target - appliedScale) < 0.01f) {
            return;
        }

        // Padding, spacing and borders are multiplied by the delta from whatever is
        // already applied, since there is no way to reset the style to defaults.
        ImGui.getStyle().scaleAllSizes(target / appliedScale);

        // Deliberately does not touch the font scale. This backend cannot
        // re-rasterise glyphs on demand, so scaling the font here would stretch a
        // 13 pixel bitmap and produce exactly the blur this avoids. ImGuiFonts
        // rebuilds the atlas at the right size instead.

        appliedScale = target;
        McMarkingsCompanion.LOGGER.debug("[mcmarkings] imgui scaled to match gui scale {}", target);
    }

    /**
     * ImGui texture ids are 64-bit in 1.92, so the GL name has to be widened.
     * Unsigned widening because GL names are unsigned in the spec even though the
     * Java binding hands them back as int.
     */
    public static long textureId(TextureHandle handle) {
        if (handle == null) {
            return NO_TEXTURE;
        }
        OptionalInt glId = handle.glId();
        return glId.isPresent() ? Integer.toUnsignedLong(glId.getAsInt()) : NO_TEXTURE;
    }

    /** Scale that fits {@code source} inside {@code box} without distorting it. */
    public static float fitScale(int sourceWidth, int sourceHeight, float boxWidth, float boxHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || boxWidth <= 0.0f || boxHeight <= 0.0f) {
            return 1.0f;
        }
        return Math.min(boxWidth / sourceWidth, boxHeight / sourceHeight);
    }

    /**
     * An inline image, or a placeholder box of the same size when the texture is
     * missing or unsamplable. Either way the layout advances by exactly
     * {@code width x height}, so callers can lay out a grid without branching.
     */
    public static void image(TextureHandle handle, float width, float height) {
        long id = textureId(handle);
        if (id == NO_TEXTURE) {
            placeholder(width, height);
            return;
        }
        ImGui.image(id, width, height);
    }

    /** A filled, outlined box occupying the same space an image would have. */
    public static void placeholder(float width, float height) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();

        ImDrawList drawList = ImGui.getWindowDrawList();
        drawList.addRectFilled(x, y, x + width, y + height, ImGui.getColorU32(ImGuiCol.FrameBg));
        drawList.addRect(x, y, x + width, y + height, ImGui.getColorU32(ImGuiCol.Border));

        ImGui.dummy(width, height);
    }

    /**
     * Draw an image into an arbitrary draw list rectangle, degrading to an
     * outlined box when there is no usable texture.
     */
    public static void drawImage(ImDrawList drawList, TextureHandle handle,
            float x0, float y0, float x1, float y1) {
        long id = textureId(handle);
        if (id == NO_TEXTURE) {
            drawList.addRectFilled(x0, y0, x1, y1, ImGui.getColorU32(ImGuiCol.FrameBg));
            drawList.addRect(x0, y0, x1, y1, ImGui.getColorU32(ImGuiCol.Border));
            return;
        }
        drawList.addImage(id, x0, y0, x1, y1);
    }

    /**
     * Run {@code body} inside a window pinned to the whole game window.
     *
     * <p>{@code end} is called whether or not {@code begin} returned true, because
     * ImGui pairs them unconditionally for top-level windows.
     */
    public static void fullViewportWindow(String id, Runnable body) {
        ImGuiViewport viewport = ImGui.getMainViewport();
        ImGui.setNextWindowPos(viewport.getWorkPosX(), viewport.getWorkPosY());
        ImGui.setNextWindowSize(viewport.getWorkSizeX(), viewport.getWorkSizeY());

        boolean visible = ImGui.begin(id, FULL_WINDOW_FLAGS);
        try {
            if (visible) {
                body.run();
            }
        } finally {
            ImGui.end();
        }
    }

    /**
     * A bordered child region.
     *
     * <p>{@code endChild} is called unconditionally, including when {@code body}
     * throws. ImGui keeps a stack of begin/end pairs and an unbalanced stack takes
     * out the following frame, so the pairing must survive a bug in the body.
     */
    public static void child(String id, float width, float height, Runnable body) {
        boolean visible = ImGui.beginChild(id, width, height, ImGuiChildFlags.Borders);
        try {
            if (visible) {
                body.run();
            }
        } finally {
            ImGui.endChild();
        }
    }

    /** Trim for labels that would otherwise blow out a fixed-width pane. */
    public static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, Math.max(1, limit - 3)) + "...";
    }

    /**
     * One line of feedback shown at the bottom of an editor.
     *
     * <p>Mutated from callbacks that finish on the client thread, read from the
     * render thread; both are the same thread in practice, which is why this is
     * deliberately not synchronised.
     */
    public static final class Status {

        public enum Level {
            INFO,
            GOOD,
            BAD
        }

        private String message = "";
        private Level level = Level.INFO;

        public void info(String text) {
            set(text, Level.INFO);
        }

        public void good(String text) {
            set(text, Level.GOOD);
        }

        public void bad(String text) {
            set(text, Level.BAD);
        }

        public void set(String text, Level newLevel) {
            this.message = text == null ? "" : text;
            this.level = newLevel;
        }

        public String message() {
            return message;
        }

        public void draw() {
            if (message.isEmpty()) {
                return;
            }
            switch (level) {
                case GOOD -> Notice.success(message);
                case BAD -> Notice.error(message);
                default -> ImGui.textDisabled(message);
            }
        }
    }
}
