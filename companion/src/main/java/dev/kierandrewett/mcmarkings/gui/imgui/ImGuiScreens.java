package dev.kierandrewett.mcmarkings.gui.imgui;

import dev.kierandrewett.mcmarkings.texture.TextureHandle;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiWindowFlags;

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
                case GOOD -> ImGui.textColored(0.45f, 0.85f, 0.45f, 1.0f, message);
                case BAD -> ImGui.textColored(0.95f, 0.45f, 0.45f, 1.0f, message);
                default -> ImGui.textDisabled(message);
            }
        }
    }
}
