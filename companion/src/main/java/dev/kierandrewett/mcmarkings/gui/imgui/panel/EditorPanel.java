package dev.kierandrewett.mcmarkings.gui.imgui.panel;

import dev.kierandrewett.mcmarkings.CompanionServices;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.command.Command;
import dev.kierandrewett.mcmarkings.command.CommandRegistry;
import dev.kierandrewett.mcmarkings.command.Shortcut;
import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.core.RepoImage;
import dev.kierandrewett.mcmarkings.doc.Alignment;
import dev.kierandrewett.mcmarkings.doc.Document;
import dev.kierandrewett.mcmarkings.doc.DocumentRenderer;
import dev.kierandrewett.mcmarkings.doc.Edits;
import dev.kierandrewett.mcmarkings.doc.History;
import dev.kierandrewett.mcmarkings.doc.Insets;
import dev.kierandrewett.mcmarkings.doc.Layer;
import dev.kierandrewett.mcmarkings.doc.RepositoryImages;
import dev.kierandrewett.mcmarkings.doc.Snapping;
import dev.kierandrewett.mcmarkings.gui.imgui.ImGuiScreens;
import dev.kierandrewett.mcmarkings.gui.imgui.Notice;
import dev.kierandrewett.mcmarkings.render.FontRegistry;
import dev.kierandrewett.mcmarkings.texture.TextureHandle;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiButtonFlags;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiColorEditFlags;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiSelectableFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImInt;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The layer editor: the stack on the left, the canvas in the middle, properties on
 * the right.
 *
 * <p>Everything this panel does to a document goes through {@link History}, and
 * every action it offers goes through a {@link Command}, so a toolbar button, a
 * keyboard shortcut and the palette are one code path and cannot disagree about
 * whether something is available. That is what makes the keyboard the fast way to
 * work here rather than a second-class one.
 *
 * <p>Nothing in {@link #draw()} decodes an image, renders a document or touches the
 * filesystem. An edit marks the document dirty, the edits settle for
 * {@link #RENDER_DEBOUNCE_MILLIS}, and one virtual thread at a time renders and
 * hands the pixels to the texture cache. The render thread only ever draws a texture
 * that is already resident. Doing any of that inline froze the whole game, which is
 * the failure this arrangement exists to prevent.
 *
 * <p>Every {@code begin}/{@code end} pair closes in a {@code finally}, including
 * when the body throws. ImGui keeps that stack across frames, so an unbalanced one
 * takes out the frame after the failure rather than the frame that caused it, and
 * the cause is then very hard to find.
 */
public final class EditorPanel implements Panel {

    /**
     * How long an edit settles before the document is re-rendered.
     *
     * <p>Short enough that a drag looks live, long enough that a keystroke or a
     * slider does not start a render per frame. Renders are single-flight anyway, so
     * this bounds waste rather than correctness.
     */
    private static final long RENDER_DEBOUNCE_MILLIS = 160L;

    /**
     * Longest edge of the preview texture.
     *
     * <p>A document can be several thousand pixels across and the canvas pane is a
     * few hundred. Uploading the full render would cost VRAM and a per-pixel copy for
     * detail nobody can see. Export renders at full size separately.
     */
    private static final int PREVIEW_MAX_EDGE = 1024;

    private static final double MIN_ZOOM = 0.02;
    private static final double MAX_ZOOM = 32.0;

    /** One wheel notch. Multiplicative, so the steps feel even at any zoom. */
    private static final double ZOOM_STEP = 1.2;

    /** Snap pull in screen pixels, converted to document pixels for the current zoom. */
    private static final int SNAP_SCREEN_PIXELS = 7;

    /** Below this the frame grid is denser than it is useful, so it is left out. */
    private static final float MIN_GRID_SPACING = 5.0f;

    private static final int NUDGE_SMALL = 1;
    private static final int NUDGE_LARGE = 10;

    /** Cell size of the transparency chequerboard, in screen pixels. */
    private static final float CHEQUER_SIZE = 12.0f;

    /** Past this the chequerboard is more draw calls than it is worth. */
    private static final int MAX_CHEQUER_CELLS = 4096;

    private static final int NAME_BUFFER = 128;
    private static final int TEXT_BUFFER = 4096;

    /** Column widths, in text lines, so they follow the game's GUI scale. */
    private static final float LAYERS_MIN_LINES = 12.0f;
    private static final float LAYERS_MAX_LINES = 17.0f;
    private static final float PROPERTIES_MIN_LINES = 16.0f;
    private static final float PROPERTIES_MAX_LINES = 26.0f;

    /** Under this the three columns cannot all fit, so the side panels share one. */
    private static final float THREE_COLUMN_LINES = 54.0f;

    /** Layer rows only carry raise and lower buttons when the column is this wide. */
    private static final float REORDER_BUTTON_LINES = 13.0f;

    private static final String[] FIT_LABELS = { "Contain", "Cover", "Stretch" };
    private static final String[] HORIZONTAL_LABELS = { "Left", "Centre", "Right" };
    private static final String[] VERTICAL_LABELS = { "Top", "Middle", "Bottom" };

    /**
     * GLFW key codes, written out rather than taken from the GLFW class so this file
     * says what it binds without a lookup. A printable key shares its code with its
     * uppercase character.
     */
    private static final int KEY_A = 'A';
    private static final int KEY_D = 'D';
    private static final int KEY_G = 'G';
    private static final int KEY_Z = 'Z';
    private static final int KEY_ZERO = '0';
    private static final int KEY_MINUS = '-';
    private static final int KEY_EQUAL = '=';
    private static final int KEY_DELETE = 261;
    private static final int KEY_RIGHT = 262;
    private static final int KEY_LEFT = 263;
    private static final int KEY_DOWN = 264;
    private static final int KEY_UP = 265;

    /** GLFW modifier bits, as a key callback reports them. */
    private static final int MOD_SHIFT = 0x0001;
    private static final int MOD_CONTROL = 0x0002;
    private static final int MOD_ALT = 0x0004;
    private static final int MOD_SUPER = 0x0008;


    private static final String PADDING_LABEL = "Padding (top, right, bottom, left)";


    /**
     * The toolbar, in order. A null command id is a plain divider between groups.
     *
     * <p>A list rather than a run of calls, because the row has to be able to wrap
     * and wrapping needs to look at each item before it is submitted.
     */
    private static final List<ToolbarItem> TOOLBAR = List.of(
            new ToolbarItem("New", "editor.file.new"),
            new ToolbarItem("Open", "editor.file.open"),
            new ToolbarItem("Save", "editor.file.save"),
            new ToolbarItem("Place as map", "editor.file.publish"),
            new ToolbarItem("|", null),
            new ToolbarItem("Undo", "editor.undo"),
            new ToolbarItem("Redo", "editor.redo"),
            new ToolbarItem("|", null),
            new ToolbarItem("Duplicate", "editor.duplicate"),
            new ToolbarItem("Delete", "editor.delete"),
            new ToolbarItem("Group", "editor.group"),
            new ToolbarItem("Ungroup", "editor.ungroup"),
            new ToolbarItem("Front", "editor.front"),
            new ToolbarItem("Back", "editor.back"),
            new ToolbarItem("|", null),
            new ToolbarItem("Align", null),
            new ToolbarItem("L##align-left", "editor.align.left"),
            new ToolbarItem("C##align-centre", "editor.align.centre"),
            new ToolbarItem("R##align-right", "editor.align.right"),
            new ToolbarItem("T##align-top", "editor.align.top"),
            new ToolbarItem("M##align-middle", "editor.align.middle"),
            new ToolbarItem("B##align-bottom", "editor.align.bottom"),
            new ToolbarItem("|", null),
            new ToolbarItem("-##zoom-out", "editor.zoom.out"),
            new ToolbarItem("+##zoom-in", "editor.zoom.in"),
            new ToolbarItem("Fit", "editor.zoom.fit"));

    private final CompanionServices services;

    /** Its own browser instance, so the picker's search does not follow the Browse tab. */
    private final ImageBrowserPanel picker;

    private final CommandRegistry commands = new CommandRegistry();

    /** Saving, opening and placing. Owns its own modals and status line. */
    private final EditorFiles files;

    /** What the recovery snapshot last saw, so an unchanged frame costs nothing. */
    private Document recorded;

    /** Borrowed from the session, so the work survives the screen being discarded. */
    private final History history;

    /** Insertion-ordered, so the first layer clicked stays the one actions read first. */
    private final LinkedHashSet<String> selection = new LinkedHashSet<>();

    private double zoom = 1.0;
    private float panX;
    private float panY;
    private boolean viewInitialised;

    /**
     * Zoom and fit are commands, but the region they work against is only known while
     * the canvas is being drawn, so they leave a request here.
     */
    private int pendingZoomSteps;
    private boolean fitRequested;

    private boolean snapEnabled = true;

    private Drag drag;
    private List<Snapping.Guide> guides = List.of();

    /** True while the narrow layout is showing properties rather than the layer stack. */
    private boolean sideShowsProperties;

    private String renamingId;
    private boolean renameFocusPending;

    private final ImString nameBuffer = new ImString("", NAME_BUFFER);
    private final ImString textBuffer = new ImString("", TEXT_BUFFER);
    private final ImString renameBuffer = new ImString("", NAME_BUFFER);
    private final ImString documentNameBuffer = new ImString("", NAME_BUFFER);

    /** Which layer the name and text buffers were last filled from. */
    private String buffersFor;


    /** Reused rather than allocated per frame; only ever touched while drawing. */
    private final int[] pair = new int[2];
    private final int[] quad = new int[4];
    private final int[] single = new int[1];
    private final float[] scalar = new float[1];
    private final float[] rgba = new float[4];
    private final ImInt choice = new ImInt();

    /** Measured once a frame rather than per row, since each measurement crosses JNI. */
    private float characterWidth = 7.0f;

    /** One render at a time. A queued render for a document nobody will see is waste. */
    private final AtomicBoolean rendering = new AtomicBoolean();

    /** Zero means the preview matches the document; otherwise when the last edit landed. */
    private long dirtyAtMillis;

    /** The document the newest render was started for, so an idle frame costs nothing. */
    private Document renderedDocument;

    private int renderSequence;
    private String textureKey;
    private TextureHandle texture;
    private List<String> renderProblems = List.of();
    private String renderFailure;

    /**
     * Font families, resolved off-thread.
     *
     * <p>The first call to {@link FontRegistry#availableFamilies()} enumerates every
     * font on the machine, which is far too slow for a frame, so the list is fetched
     * once on a worker and the combo says so until it lands.
     */
    private volatile String[] fontFamilies;
    private boolean fontsRequested;

    public EditorPanel(CompanionServices services) {
        this.services = services;
        this.history = services.editing;
        this.picker = new ImageBrowserPanel(services, "editor-pick", "Images");
        this.files = new EditorFiles(services, history);
        // Long text would otherwise be silently cut at the buffer size.
        this.textBuffer.inputData.isResizable = true;
        registerCommands();
        files.registerCommands(commands);
    }

    @Override
    public String title() {
        return "Editor";
    }

    /** Everything the editor can do, for the window's palette to search. */
    @Override
    public CommandRegistry commands() {
        return commands;
    }

    /**
     * Runs whatever a key press is bound to.
     *
     * <p>Must only be called when ImGui does not want the keyboard, or typing a "d"
     * into a text field duplicates the selection.
     *
     * @param modifiers GLFW modifier bits from the key event
     * @return true when a command handled it
     */
    public boolean handleKey(int keyCode, int modifiers) {
        // Command on macOS is the conventional modifier for these and GLFW reports it
        // separately, so both count as control.
        boolean control = (modifiers & (MOD_CONTROL | MOD_SUPER)) != 0;
        return commands.handleKey(keyCode, control, (modifiers & MOD_SHIFT) != 0, (modifiers & MOD_ALT) != 0);
    }

    /** Drops the preview texture. Without it, one survives every open for the session. */
    public void close() {
        if (textureKey == null) {
            return;
        }
        services.thumbnails.evict(textureKey);
        textureKey = null;
        texture = null;
    }

    @Override
    public void draw() {
        try {
            noteForRecovery();
            drawEditor();
        } catch (Exception failure) {
            // The shell catches this too, but handling it here keeps the failure to
            // the body and still submits the picker and palette below.
            McMarkingsCompanion.LOGGER.error("[mcmarkings] editor panel failed to draw", failure);
            Notice.error("The editor could not finish drawing this frame.");
            ImGui.textWrapped(String.valueOf(failure));
        } finally {
            // Unconditional: an ImGui popup is only submitted while its owner is, so
            // skipping this on any frame would close whatever is open.
            picker.drawPicker();
            files.drawPopups();
        }
    }

    private void drawEditor() {
        forgetMissingSelection();
        maybeStartRender();
        characterWidth = Math.max(1.0f, ImGui.calcTextSizeX("n"));

        files.drawRecoveryOffer();
        drawToolbar();
        ImGui.separator();
        drawColumns();
        drawStatusLine();
    }

    private void drawColumns() {
        float unit = unit();
        float availWidth = Math.max(unit * 8.0f, ImGui.getContentRegionAvailX());
        float availHeight = Math.max(unit * 8.0f,
                ImGui.getContentRegionAvailY() - ImGui.getFrameHeightWithSpacing());
        float spacing = ImGui.getStyle().getItemSpacingX();

        if (availWidth >= unit * THREE_COLUMN_LINES) {
            float layersWidth = Math.clamp(availWidth * 0.18f,
                    unit * LAYERS_MIN_LINES, unit * LAYERS_MAX_LINES);
            float propertiesWidth = Math.clamp(availWidth * 0.24f,
                    unit * PROPERTIES_MIN_LINES, unit * PROPERTIES_MAX_LINES);

            ImGuiScreens.child("##editor-layers", layersWidth, availHeight, this::drawLayers);
            ImGui.sameLine();
            drawCanvasRegion(availWidth - layersWidth - propertiesWidth - spacing * 2.0f, availHeight);
            ImGui.sameLine();
            ImGuiScreens.child("##editor-properties", propertiesWidth, availHeight, this::drawProperties);
            return;
        }

        // Too narrow for three columns without squeezing the canvas down to nothing,
        // and the canvas is the one region that has to stay usable. The side panels
        // share a column and a toggle picks between them.
        float sideWidth = Math.clamp(availWidth * 0.36f, unit * LAYERS_MIN_LINES, unit * LAYERS_MAX_LINES);
        ImGuiScreens.child("##editor-side", sideWidth, availHeight, this::drawSharedSideColumn);
        ImGui.sameLine();
        drawCanvasRegion(availWidth - sideWidth - spacing, availHeight);
    }

    private void drawSharedSideColumn() {
        float available = ImGui.getContentRegionAvailX() - ImGui.getStyle().getItemSpacingX();
        float half = Math.max(unit() * 3.0f, available / 2.0f);

        if (ImGui.button("Layers##side-layers", half, 0.0f)) {
            sideShowsProperties = false;
        }
        ImGui.sameLine();
        if (ImGui.button("Props##side-properties", half, 0.0f)) {
            sideShowsProperties = true;
        }
        ImGui.separator();

        if (sideShowsProperties) {
            drawProperties();
            return;
        }
        drawLayers();
    }

    /**
     * One line at the bottom, showing whichever of these matters most right now.
     *
     * <p>A render failure hides a layer problem hides a message hides the summary,
     * because a line that shows all four at once is a line nobody reads.
     */
    private void drawStatusLine() {
        if (renderFailure != null) {
            Notice.error(
                    "Render failed: " + ImGuiScreens.truncate(renderFailure, 90));
            return;
        }
        if (!renderProblems.isEmpty()) {
            String first = ImGuiScreens.truncate(renderProblems.getFirst(), 70);
            String extra = renderProblems.size() > 1 ? " (+" + (renderProblems.size() - 1) + " more)" : "";
            Notice.warning(first + extra);
            return;
        }

        if (!files.status().message().isEmpty()) {
            files.status().draw();
            ImGui.sameLine();
            ImGui.textDisabled("  |  ");
            ImGui.sameLine();
        }

        Document document = history.current();
        ImGui.textDisabled(document.grid() + " frames, " + document.width() + " x " + document.height() + " px"
                + "   zoom " + Math.round(zoom * 100.0) + "%"
                + "   " + document.layers().size() + " layer(s)"
                + (selection.isEmpty() ? "" : ", " + selection.size() + " selected")
                + (files.hasUnsavedChanges() ? "   unsaved" : ""));
    }

    // -----------------------------------------------------------------------
    // Toolbar
    // -----------------------------------------------------------------------

    private void drawToolbar() {
        boolean first = true;
        for (ToolbarItem item : TOOLBAR) {
            if (!first) {
                flowTo(item.label());
            }
            first = false;

            if (item.commandId() == null) {
                ImGui.textDisabled(item.label());
                continue;
            }
            commandButton(item.label(), item.commandId());
        }

        flowTo("Snap");
        if (ImGui.checkbox("Snap", snapEnabled)) {
            snapEnabled = !snapEnabled;
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Snap to the canvas, the frame grid and the other layers."
                    + " Hold Alt to suspend it for one drag.");
        }
    }

    /**
     * Keeps the next control on this row while there is room, and starts a new one
     * when there is not.
     *
     * <p>ImGui does not wrap a run of {@code sameLine} calls. Without this the last
     * few controls run off the edge of an 854 pixel game window and cannot be
     * reached at all, which is worse than a toolbar that is two rows deep. The width
     * is estimated from the label and the frame height, deliberately generously, so
     * it wraps a little early rather than a little late.
     */
    private void flowTo(String label) {
        ImGui.sameLine();
        float width = ImGui.calcTextSizeX(visibleLabel(label)) + ImGui.getFrameHeight();
        if (ImGui.getCursorPosX() + width > ImGui.getContentRegionMaxX()) {
            ImGui.newLine();
        }
    }

    /** Everything after "##" is ImGui's id, not text, so it does not take any width. */
    static String visibleLabel(String label) {
        int marker = label.indexOf("##");
        return marker < 0 ? label : label.substring(0, marker);
    }

    /**
     * A button that runs a command.
     *
     * <p>The press is acted on after the disabled block closes. An action that threw
     * inside it would leave ImGui's disabled stack unbalanced and take out the frame
     * after this one.
     */
    private void commandButton(String label, String commandId) {
        Command command = commands.byId(commandId).orElse(null);
        if (command == null) {
            return;
        }

        ImGui.beginDisabled(!command.isEnabled());
        boolean pressed = ImGui.button(label);
        ImGui.endDisabled();

        if (ImGui.isItemHovered()) {
            String shortcut = command.shortcut() == null ? "" : "   " + command.shortcut().display();
            ImGui.setTooltip(command.label() + shortcut + (command.hint().isBlank() ? "" : "\n" + command.hint()));
        }
        if (pressed) {
            command.run();
        }
    }

    // -----------------------------------------------------------------------
    // Canvas
    // -----------------------------------------------------------------------

    /**
     * The canvas gets its own child with scrolling turned off, because the wheel is
     * the zoom here and a scrolling child would eat it first.
     */
    private void drawCanvasRegion(float width, float height) {
        boolean visible = ImGui.beginChild("##editor-canvas", Math.max(unit() * 8.0f, width), height,
                ImGuiChildFlags.Borders, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
        try {
            if (visible) {
                drawCanvas();
            }
        } finally {
            ImGui.endChild();
        }
    }

    private void drawCanvas() {
        float originX = ImGui.getCursorScreenPosX();
        float originY = ImGui.getCursorScreenPosY();
        float width = Math.max(unit() * 4.0f, ImGui.getContentRegionAvailX());
        float height = Math.max(unit() * 4.0f, ImGui.getContentRegionAvailY());

        applyPendingView(history.current(), width, height);

        // Input first, then everything below is drawn from what it left behind, so a
        // drag shows in the frame it happened rather than the one after it. The draw
        // list works in screen coordinates and does not mind that the layout cursor
        // has already moved past this region.
        ImGui.invisibleButton("##canvas-input", width, height,
                ImGuiButtonFlags.MouseButtonLeft | ImGuiButtonFlags.MouseButtonRight);
        handleCanvasInput(history.current(), originX, originY);

        Document document = history.current();
        ImDrawList drawList = ImGui.getWindowDrawList();
        drawList.pushClipRect(originX, originY, originX + width, originY + height, true);
        try {
            drawCanvasBackdrop(drawList, document, originX, originY);
            drawPreviewTexture(drawList, document, originX, originY);
            drawFrameGrid(drawList, document, originX, originY);
            drawOverlay(drawList, document, originX, originY, width, height);
        } finally {
            drawList.popClipRect();
        }
    }

    /** Applies a zoom or fit a command asked for, now that the region is known. */
    private void applyPendingView(Document document, float width, float height) {
        if (!viewInitialised) {
            viewInitialised = true;
            fitRequested = true;
        }
        if (fitRequested) {
            fitRequested = false;
            pendingZoomSteps = 0;
            fitToRegion(document, width, height);
            return;
        }
        if (pendingZoomSteps != 0) {
            zoomAbout(width / 2.0f, height / 2.0f, pendingZoomSteps);
            pendingZoomSteps = 0;
        }
    }

    private void fitToRegion(Document document, float width, float height) {
        // A margin, so the canvas edge and its handles are not flush against the pane.
        double fit = Math.min(width / (double) document.width(), height / (double) document.height()) * 0.92;
        zoom = Math.clamp(fit, MIN_ZOOM, MAX_ZOOM);
        panX = (float) ((width - document.width() * zoom) / 2.0);
        panY = (float) ((height - document.height() * zoom) / 2.0);
    }

    /**
     * Zooms about a point in the region, so whatever is under the cursor stays there.
     *
     * <p>Zooming about the region's origin instead is the classic mistake: the thing
     * being looked at slides away and has to be chased with a pan.
     */
    private void zoomAbout(float regionX, float regionY, double steps) {
        double target = Math.clamp(zoom * Math.pow(ZOOM_STEP, steps), MIN_ZOOM, MAX_ZOOM);
        if (target == zoom) {
            return;
        }
        double documentX = (regionX - panX) / zoom;
        double documentY = (regionY - panY) / zoom;
        zoom = target;
        panX = (float) (regionX - documentX * zoom);
        panY = (float) (regionY - documentY * zoom);
    }

    /**
     * A chequerboard under the canvas, then the canvas outline.
     *
     * <p>Most of what is composed here is transparent, and transparency drawn over a
     * flat dark pane is indistinguishable from a dark layer. The chequerboard is the
     * usual way to tell them apart. It is dropped rather than drawn past a cell
     * count, since a zoomed-out canvas would otherwise be thousands of quads a frame.
     */
    private void drawCanvasBackdrop(ImDrawList drawList, Document document, float originX, float originY) {
        float left = originX + panX;
        float top = originY + panY;
        float right = left + (float) (document.width() * zoom);
        float bottom = top + (float) (document.height() * zoom);

        drawList.addRectFilled(left, top, right, bottom, ImGui.getColorU32(0.16f, 0.16f, 0.17f, 1.0f));

        int columns = (int) Math.ceil((right - left) / CHEQUER_SIZE);
        int rows = (int) Math.ceil((bottom - top) / CHEQUER_SIZE);
        if (columns > 0 && rows > 0 && (long) columns * rows <= MAX_CHEQUER_CELLS) {
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

        drawList.addRect(left, top, right, bottom, ImGui.getColorU32(0.75f, 0.75f, 0.78f, 0.9f));
    }

    private void drawPreviewTexture(ImDrawList drawList, Document document, float originX, float originY) {
        if (texture == null) {
            return;
        }
        float left = originX + panX;
        float top = originY + panY;
        ImGuiScreens.drawImage(drawList, texture, left, top,
                left + (float) (document.width() * zoom), top + (float) (document.height() * zoom));
    }

    /**
     * The frame cell lines.
     *
     * <p>A cell is one item frame, so these are the boundaries the finished image is
     * cut on. Composing across one without meaning to is the difference between a
     * legible result and a seam through the middle of a letter.
     */
    private void drawFrameGrid(ImDrawList drawList, Document document, float originX, float originY) {
        float spacing = (float) (document.pixelsPerFrame() * zoom);
        if (spacing < MIN_GRID_SPACING) {
            return;
        }

        float left = originX + panX;
        float top = originY + panY;
        float right = left + (float) (document.width() * zoom);
        float bottom = top + (float) (document.height() * zoom);
        int colour = ImGui.getColorU32(0.75f, 0.75f, 0.78f, 0.28f);

        for (int column = 1; column < document.grid().columns(); column++) {
            float x = left + column * spacing;
            drawList.addLine(x, top, x, bottom, colour);
        }
        for (int row = 1; row < document.grid().rows(); row++) {
            float y = top + row * spacing;
            drawList.addLine(left, y, right, y, colour);
        }
    }

    /** Selection outlines, resize handles, snap guides, the marquee and the empty state. */
    private void drawOverlay(ImDrawList drawList, Document document, float originX, float originY,
            float width, float height) {
        int selectedColour = ImGui.getColorU32(0.40f, 0.72f, 1.00f, 1.0f);

        for (String id : selection) {
            Layer layer = document.byId(id).orElse(null);
            if (layer == null) {
                continue;
            }
            Layer.Bounds bounds = layer.bounds();
            drawList.addRect(screenX(bounds.x(), originX), screenY(bounds.y(), originY),
                    screenX(bounds.right(), originX), screenY(bounds.bottom(), originY), selectedColour);
        }

        Layer focused = singleSelection(document).filter(layer -> !layer.locked()).orElse(null);
        if (focused != null) {
            drawHandles(drawList, focused.bounds(), originX, originY, selectedColour);
        }

        int guideColour = ImGui.getColorU32(1.00f, 0.45f, 0.75f, 0.9f);
        for (Snapping.Guide guide : guides) {
            if (guide.vertical()) {
                float x = screenX(guide.position(), originX);
                drawList.addLine(x, originY, x, originY + height, guideColour);
                continue;
            }
            float y = screenY(guide.position(), originY);
            drawList.addLine(originX, y, originX + width, y, guideColour);
        }

        if (drag != null && drag.handle() == Handle.MARQUEE) {
            drawMarquee(drawList, originX, originY, selectedColour);
        }

        if (document.layers().isEmpty()) {
            String hint = "Add a layer from the panel on the left";
            drawList.addText(originX + (width - ImGui.calcTextSizeX(hint)) / 2.0f, originY + height / 2.0f,
                    ImGui.getColorU32(ImGuiCol.TextDisabled), hint);
            return;
        }
        if (texture == null && renderFailure == null) {
            drawList.addText(originX + unit() * 0.5f, originY + unit() * 0.5f,
                    ImGui.getColorU32(ImGuiCol.TextDisabled), "Rendering...");
        }
    }

    private void drawMarquee(ImDrawList drawList, float originX, float originY, int colour) {
        int currentX = mouseDocX(originX);
        int currentY = mouseDocY(originY);
        float left = screenX(Math.min(drag.startDocX(), currentX), originX);
        float top = screenY(Math.min(drag.startDocY(), currentY), originY);
        float right = screenX(Math.max(drag.startDocX(), currentX), originX);
        float bottom = screenY(Math.max(drag.startDocY(), currentY), originY);

        drawList.addRectFilled(left, top, right, bottom, ImGui.getColorU32(0.40f, 0.72f, 1.00f, 0.18f));
        drawList.addRect(left, top, right, bottom, colour);
    }

    private void drawHandles(ImDrawList drawList, Layer.Bounds bounds, float originX, float originY, int colour) {
        float half = handleHalfSize();
        for (Handle handle : Handle.RESIZE_HANDLES) {
            float x = screenX(handleDocX(bounds, handle), originX);
            float y = screenY(handleDocY(bounds, handle), originY);
            drawList.addRectFilled(x - half, y - half, x + half, y + half, colour);
        }
    }

    // -----------------------------------------------------------------------
    // Canvas input
    // -----------------------------------------------------------------------

    private void handleCanvasInput(Document document, float originX, float originY) {
        boolean hovered = ImGui.isItemHovered();
        boolean active = ImGui.isItemActive();

        if (hovered) {
            float wheel = ImGui.getIO().getMouseWheel();
            if (wheel != 0.0f) {
                zoomAbout(ImGui.getMousePosX() - originX, ImGui.getMousePosY() - originY, wheel);
            }
            updateCursorHint(document, originX, originY);
        }

        // Right-drag pans. It has to work anywhere on the canvas, including on top of
        // a layer, so it is checked before anything that selects.
        if (active && ImGui.isMouseDown(ImGuiMouseButton.Right)) {
            panX += ImGui.getIO().getMouseDeltaX();
            panY += ImGui.getIO().getMouseDeltaY();
            return;
        }

        if (ImGui.isItemActivated() && ImGui.isMouseDown(ImGuiMouseButton.Left)) {
            beginDrag(document, originX, originY);
        }
        if (drag != null && active) {
            updateDrag(document, originX, originY);
        }
        if (drag != null && !ImGui.isMouseDown(ImGuiMouseButton.Left)) {
            finishDrag(document, originX, originY);
        }
    }

    /** Tells the mouse what a handle would do, which is most of what makes resizing findable. */
    private void updateCursorHint(Document document, float originX, float originY) {
        Layer layer = singleSelection(document).filter(candidate -> !candidate.locked()).orElse(null);
        if (layer == null) {
            return;
        }
        Handle handle = handleAt(layer.bounds(), originX, originY);
        if (handle == null) {
            return;
        }
        ImGui.setMouseCursor(switch (handle) {
            case TOP, BOTTOM -> ImGuiMouseCursor.ResizeNS;
            case LEFT, RIGHT -> ImGuiMouseCursor.ResizeEW;
            case TOP_LEFT, BOTTOM_RIGHT -> ImGuiMouseCursor.ResizeNWSE;
            case TOP_RIGHT, BOTTOM_LEFT -> ImGuiMouseCursor.ResizeNESW;
            default -> ImGuiMouseCursor.Arrow;
        });
    }

    private void beginDrag(Document document, float originX, float originY) {
        int documentX = mouseDocX(originX);
        int documentY = mouseDocY(originY);
        boolean additive = ImGui.getIO().getKeyCtrl();

        Layer focused = singleSelection(document).filter(layer -> !layer.locked()).orElse(null);
        if (focused != null) {
            Handle handle = handleAt(focused.bounds(), originX, originY);
            if (handle != null) {
                drag = new Drag(handle, focused.id(), Map.of(focused.id(), focused.bounds()),
                        documentX, documentY, false);
                return;
            }
        }

        Layer hit = topmostAt(document, documentX, documentY);
        if (hit == null) {
            if (!additive) {
                selection.clear();
            }
            drag = new Drag(Handle.MARQUEE, null, Map.of(), documentX, documentY, additive);
            return;
        }

        if (additive) {
            if (!selection.remove(hit.id())) {
                selection.add(hit.id());
            }
        } else if (!selection.contains(hit.id())) {
            // Clicking inside an existing multi-selection keeps it, so a whole
            // arrangement can be dragged without rebuilding the selection first.
            selection.clear();
            selection.add(hit.id());
        }

        Map<String, Layer.Bounds> movable = movableBounds(document);
        if (hit.locked() || movable.isEmpty()) {
            drag = null;
            return;
        }
        drag = new Drag(Handle.MOVE, hit.id(), movable, documentX, documentY, false);
    }

    private void updateDrag(Document document, float originX, float originY) {
        if (drag.handle() == Handle.MARQUEE) {
            return;
        }

        int deltaX = mouseDocX(originX) - drag.startDocX();
        int deltaY = mouseDocY(originY) - drag.startDocY();

        if (drag.handle() == Handle.MOVE) {
            applyMove(document, deltaX, deltaY);
            return;
        }
        applyResize(document, deltaX, deltaY);
    }

    /**
     * Moves the selection, snapping the layer under the cursor and shifting the rest
     * by the same correction.
     *
     * <p>Snapping each layer separately would pull a carefully spaced arrangement
     * apart the moment it crossed a guide, which is the opposite of the point.
     */
    private void applyMove(Document document, int deltaX, int deltaY) {
        Layer.Bounds start = drag.startBounds().get(drag.primaryId());
        if (start == null) {
            return;
        }

        Layer.Bounds proposed = start.movedTo(start.x() + deltaX, start.y() + deltaY);
        boolean snapping = snapEnabled && !ImGui.getIO().getKeyAlt();
        Snapping.Result snapped = Snapping.snap(proposed, document, drag.primaryId(),
                Snapping.toleranceForZoom(zoom, SNAP_SCREEN_PIXELS), snapping);
        guides = snapped.guides();

        int appliedX = snapped.bounds().x() - start.x();
        int appliedY = snapped.bounds().y() - start.y();

        Document updated = document;
        for (Map.Entry<String, Layer.Bounds> entry : drag.startBounds().entrySet()) {
            Layer layer = updated.byId(entry.getKey()).orElse(null);
            if (layer == null) {
                continue;
            }
            Layer.Bounds from = entry.getValue();
            updated = updated.replace(layer.withBounds(from.movedTo(from.x() + appliedX, from.y() + appliedY)));
        }

        // One coalesce key for the whole gesture, so a drag across the canvas is one
        // undo rather than one per frame.
        history.push(updated, "Move layer", "move:" + drag.primaryId());
    }

    /**
     * Resizes from the handle that was grabbed.
     *
     * <p>Deliberately unsnapped. {@link Snapping} moves an origin and preserves a
     * size, which is the wrong shape for an edge drag, and half a snap would feel
     * worse than none.
     */
    private void applyResize(Document document, int deltaX, int deltaY) {
        Layer.Bounds start = drag.startBounds().get(drag.primaryId());
        Layer layer = document.byId(drag.primaryId()).orElse(null);
        if (start == null || layer == null) {
            return;
        }
        guides = List.of();
        history.push(document.replace(layer.withBounds(resized(start, drag.handle(), deltaX, deltaY))),
                "Resize layer", "resize:" + drag.primaryId());
    }

    private void finishDrag(Document document, float originX, float originY) {
        if (drag.handle() == Handle.MARQUEE) {
            selectWithin(document, drag.startDocX(), drag.startDocY(),
                    mouseDocX(originX), mouseDocY(originY), drag.additive());
        }
        drag = null;
        guides = List.of();
        // Without this, letting go and immediately dragging again would merge into the
        // move that just finished, purely because it happened quickly.
        history.endGesture();
    }

    private void selectWithin(Document document, int startX, int startY, int endX, int endY, boolean additive) {
        int left = Math.min(startX, endX);
        int top = Math.min(startY, endY);
        int right = Math.max(startX, endX);
        int bottom = Math.max(startY, endY);

        // A click that wandered by a pixel is a click, not a marquee.
        if (right - left < 2 && bottom - top < 2) {
            return;
        }

        if (!additive) {
            selection.clear();
        }
        for (Layer layer : document.layers()) {
            Layer.Bounds bounds = layer.bounds();
            boolean overlaps = bounds.x() < right && bounds.right() > left
                    && bounds.y() < bottom && bounds.bottom() > top;
            if (overlaps && layer.visible()) {
                selection.add(layer.id());
            }
        }
    }

    /** Every selected layer allowed to move, with where it started. */
    private Map<String, Layer.Bounds> movableBounds(Document document) {
        Map<String, Layer.Bounds> bounds = new LinkedHashMap<>();
        for (String id : selection) {
            document.byId(id).filter(layer -> !layer.locked())
                    .ifPresent(layer -> bounds.put(layer.id(), layer.bounds()));
        }
        return Map.copyOf(bounds);
    }

    /** Front-most layer under a point, since that is the one being looked at. */
    static Layer topmostAt(Document document, int documentX, int documentY) {
        List<Layer> layers = document.layers();
        for (int index = layers.size() - 1; index >= 0; index--) {
            Layer layer = layers.get(index);
            if (layer.visible() && layer.bounds().contains(documentX, documentY)) {
                return layer;
            }
        }
        return null;
    }

    /** Which handle the mouse is over, corners first so a corner beats the edges meeting it. */
    private Handle handleAt(Layer.Bounds bounds, float originX, float originY) {
        float mouseX = ImGui.getMousePosX();
        float mouseY = ImGui.getMousePosY();
        float reach = handleHalfSize() + 2.0f;

        for (Handle handle : Handle.RESIZE_HANDLES) {
            float x = screenX(handleDocX(bounds, handle), originX);
            float y = screenY(handleDocY(bounds, handle), originY);
            if (Math.abs(mouseX - x) <= reach && Math.abs(mouseY - y) <= reach) {
                return handle;
            }
        }
        return null;
    }

    /** Never smaller than one pixel, and the far edge stays where it was. */
    static Layer.Bounds resized(Layer.Bounds start, Handle handle, int deltaX, int deltaY) {
        int x = start.x();
        int y = start.y();
        int width = start.width();
        int height = start.height();

        if (handle.movesLeftEdge()) {
            x = Math.min(start.right() - 1, start.x() + deltaX);
            width = start.right() - x;
        } else if (handle.movesRightEdge()) {
            width = Math.max(1, start.width() + deltaX);
        }

        if (handle.movesTopEdge()) {
            y = Math.min(start.bottom() - 1, start.y() + deltaY);
            height = start.bottom() - y;
        } else if (handle.movesBottomEdge()) {
            height = Math.max(1, start.height() + deltaY);
        }

        return new Layer.Bounds(x, y, width, height);
    }

    private static int handleDocX(Layer.Bounds bounds, Handle handle) {
        return switch (handle) {
            case TOP_LEFT, LEFT, BOTTOM_LEFT -> bounds.x();
            case TOP, BOTTOM -> bounds.centreX();
            default -> bounds.right();
        };
    }

    private static int handleDocY(Layer.Bounds bounds, Handle handle) {
        return switch (handle) {
            case TOP_LEFT, TOP, TOP_RIGHT -> bounds.y();
            case LEFT, RIGHT -> bounds.centreY();
            default -> bounds.bottom();
        };
    }

    // -----------------------------------------------------------------------
    // Layers
    // -----------------------------------------------------------------------

    private void drawLayers() {
        drawAddButtons();
        ImGui.separator();
        // The list scrolls inside itself rather than scrolling the column, so the add
        // buttons are still there with fifty layers in the stack.
        ImGuiScreens.child("##editor-layer-list", 0.0f, 0.0f, this::drawLayerList);
    }

    private void drawLayerList() {
        Document document = history.current();
        if (document.layers().isEmpty()) {
            ImGui.textWrapped("No layers yet. Add an image, some text or a shape to start.");
            return;
        }

        boolean showReorder = ImGui.getContentRegionAvailX() >= unit() * REORDER_BUTTON_LINES;
        float rowHeight = ImGui.getFrameHeight();

        // Collected rather than run inside the loop. Every one of these replaces the
        // layer list, and mutating it while iterating it is a bug waiting for the
        // first person with fifty layers.
        Runnable pending = null;

        // Reversed: index 0 is the bottom of the stack, and the top of a layers list
        // is the front. Showing it the other way up reads as back to front.
        List<Layer> layers = document.layers();
        for (int index = layers.size() - 1; index >= 0; index--) {
            Runnable action = drawLayerRow(layers.get(index), showReorder, rowHeight);
            if (action != null) {
                pending = action;
            }
        }

        if (pending != null) {
            pending.run();
        }
    }

    private void drawAddButtons() {
        float available = ImGui.getContentRegionAvailX() - ImGui.getStyle().getItemSpacingX();
        float half = Math.max(unit() * 3.0f, available / 2.0f);

        if (ImGui.button("Image##add-image", half, 0.0f)) {
            picker.openPicker(this::addImageLayer);
        }
        ImGui.sameLine();
        if (ImGui.button("Text##add-text", half, 0.0f)) {
            addTextLayer();
        }
        if (ImGui.button("Shape##add-shape", half, 0.0f)) {
            addShapeLayer();
        }
        ImGui.sameLine();

        Command group = commands.byId("editor.group").orElse(null);
        ImGui.beginDisabled(group == null || !group.isEnabled());
        boolean pressed = ImGui.button("Group##add-group", half, 0.0f);
        ImGui.endDisabled();
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Wraps two or more selected layers in a group that moves together");
        }
        if (pressed && group != null) {
            group.run();
        }
    }

    /** @return an edit to run once the list has been iterated, or null */
    private Runnable drawLayerRow(Layer layer, boolean showReorder, float rowHeight) {
        Runnable pending = null;
        ImGui.pushID(layer.id());
        try {
            boolean visible = layer.visible();
            if (ImGui.smallButton(visible ? "V" : ".")) {
                pending = () -> setVisible(layer, !visible);
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(visible ? "Visible. Click to hide." : "Hidden. Click to show.");
            }

            ImGui.sameLine();
            boolean locked = layer.locked();
            if (ImGui.smallButton(locked ? "L" : "-")) {
                pending = () -> setLocked(layer, !locked);
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(locked ? "Locked. Click to unlock." : "Unlocked. Click to lock.");
            }

            ImGui.sameLine();
            if (layer.id().equals(renamingId)) {
                return drawRenameField(layer);
            }

            float trailing = showReorder ? unit() * 3.6f : 0.0f;
            float nameWidth = Math.max(unit() * 2.0f, ImGui.getContentRegionAvailX() - trailing);
            Runnable rowAction = drawLayerName(layer, nameWidth, rowHeight);
            if (rowAction != null) {
                pending = rowAction;
            }

            if (showReorder) {
                ImGui.sameLine();
                if (ImGui.smallButton("^")) {
                    pending = () -> reorderLayer(layer, 1);
                }
                ImGui.sameLine();
                if (ImGui.smallButton("v")) {
                    pending = () -> reorderLayer(layer, -1);
                }
            }
        } finally {
            ImGui.popID();
        }
        return pending;
    }

    private Runnable drawLayerName(Layer layer, float nameWidth, float rowHeight) {
        Runnable pending = null;
        int limit = Math.max(6, (int) (nameWidth / characterWidth));

        if (ImGui.selectable(ImGuiScreens.truncate(displayName(layer), limit), selection.contains(layer.id()),
                ImGuiSelectableFlags.AllowDoubleClick, nameWidth, 0.0f)) {
            if (ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) {
                renamingId = layer.id();
                renameFocusPending = true;
                renameBuffer.set(layer.name());
            } else if (ImGui.getIO().getKeyCtrl()) {
                if (!selection.remove(layer.id())) {
                    selection.add(layer.id());
                }
            } else {
                selection.clear();
                selection.add(layer.id());
            }
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(displayName(layer) + "\n" + kindOf(layer) + ", " + describe(layer.bounds()));
        }

        // Dragging a row past its own height swaps it with its neighbour. Inverted,
        // because up this list is towards the front of the stack.
        if (ImGui.isItemActive() && !ImGui.isItemHovered()) {
            float dragY = ImGui.getMouseDragDeltaY(ImGuiMouseButton.Left);
            if (Math.abs(dragY) > rowHeight) {
                int delta = dragY < 0.0f ? 1 : -1;
                pending = () -> reorderLayer(layer, delta);
                ImGui.resetMouseDragDelta(ImGuiMouseButton.Left);
            }
        }
        return pending;
    }

    private Runnable drawRenameField(Layer layer) {
        if (renameFocusPending) {
            ImGui.setKeyboardFocusHere();
            renameFocusPending = false;
        }
        ImGui.setNextItemWidth(-1.0f);
        boolean committed = ImGui.inputText("##rename", renameBuffer, ImGuiInputTextFlags.EnterReturnsTrue);
        if (!committed && !ImGui.isItemDeactivated()) {
            return null;
        }

        // Clicking away commits too. Losing a rename because the mouse moved is the
        // sort of small betrayal that stops people trusting a tool.
        String name = renameBuffer.get().trim();
        renamingId = null;
        if (name.isEmpty() || name.equals(layer.name())) {
            return null;
        }
        return () -> apply(history.current().replace(rebuilt(layer, name, layer.visible(), layer.locked(),
                layer.opacity(), layer.margins())), "Rename layer", null);
    }

    private void setVisible(Layer layer, boolean visible) {
        apply(history.current().replace(rebuilt(layer, layer.name(), visible, layer.locked(),
                layer.opacity(), layer.margins())), visible ? "Show layer" : "Hide layer", null);
    }

    private void setLocked(Layer layer, boolean locked) {
        apply(history.current().replace(rebuilt(layer, layer.name(), layer.visible(), locked,
                layer.opacity(), layer.margins())), locked ? "Lock layer" : "Unlock layer", null);
    }

    private void reorderLayer(Layer layer, int delta) {
        // Coalesced per layer, so dragging a row up through ten others is one undo.
        apply(history.current().reorder(layer.id(), delta), "Reorder layer", "order:" + layer.id());
    }

    // -----------------------------------------------------------------------
    // Properties
    // -----------------------------------------------------------------------

    private void drawProperties() {
        Document document = history.current();

        if (selection.size() > 1) {
            ImGui.textDisabled(selection.size() + " layers selected");
            ImGui.textWrapped("Alignment and the layer actions apply to all of them."
                    + " Select one layer to edit its properties.");
        } else {
            singleSelection(document).ifPresentOrElse(
                    layer -> drawLayerProperties(document, layer),
                    () -> ImGui.textDisabled("Nothing selected"));
        }

        ImGui.separator();
        drawDocumentProperties(document);
    }

    private void drawLayerProperties(Document document, Layer layer) {
        if (!layer.id().equals(buffersFor)) {
            buffersFor = layer.id();
            nameBuffer.set(layer.name());
            textBuffer.set(layer instanceof Layer.Text text ? text.text() : "");
        }

        ImGui.separatorText(kindOf(layer));

        // Only re-seeded while nothing at all is being typed into. An undo landing
        // mid-word would otherwise fight whoever is typing, and asking ImGui whether
        // anything is active is both simpler and safer than each field remembering
        // whether it was active last frame.
        if (idle() && !layer.name().equals(nameBuffer.get())) {
            nameBuffer.set(layer.name());
        }
        ImGui.setNextItemWidth(-1.0f);
        ImGui.inputText("##layer-name", nameBuffer);
        if (ImGui.isItemDeactivatedAfterEdit() && !nameBuffer.get().isBlank()) {
            apply(document.replace(rebuilt(layer, nameBuffer.get().trim(), layer.visible(), layer.locked(),
                    layer.opacity(), layer.margins())), "Rename layer", null);
        }
        ImGui.textDisabled("Name");

        drawBoundsFields(document, layer);
        drawCommonFields(document, layer);

        switch (layer) {
            case Layer.Image image -> drawImageFields(document, image);
            case Layer.Text text -> drawTextFields(document, text);
            case Layer.Shape shape -> drawShapeFields(document, shape);
            case Layer.Group group -> drawGroupFields(document, group);
        }
    }

    private void drawBoundsFields(Document document, Layer layer) {
        Layer.Bounds bounds = layer.bounds();

        pair[0] = bounds.x();
        pair[1] = bounds.y();
        if (field("Position (x, y)", () -> ImGui.dragInt2("##position", pair))) {
            apply(document.replace(layer.withBounds(bounds.movedTo(pair[0], pair[1]))),
                    "Move layer", "bounds:" + layer.id());
        }

        pair[0] = bounds.width();
        pair[1] = bounds.height();
        if (field("Size (w, h)", () -> ImGui.dragInt2("##size", pair, 1.0f, 1, Integer.MAX_VALUE))) {
            Layer.Bounds resized = new Layer.Bounds(bounds.x(), bounds.y(),
                    Math.max(1, pair[0]), Math.max(1, pair[1]));
            apply(document.replace(layer.withBounds(resized)), "Resize layer", "bounds:" + layer.id());
        }
    }

    private void drawCommonFields(Document document, Layer layer) {
        scalar[0] = (float) layer.opacity();
        if (field("Opacity", () -> ImGui.sliderFloat("##opacity", scalar, 0.0f, 1.0f))) {
            apply(document.replace(rebuilt(layer, layer.name(), layer.visible(), layer.locked(),
                            Math.clamp(scalar[0], 0.0f, 1.0f), layer.margins())),
                    "Change opacity", "opacity:" + layer.id());
        }

        if (ImGui.checkbox("Visible", layer.visible())) {
            setVisible(layer, !layer.visible());
        }
        ImGui.sameLine();
        if (ImGui.checkbox("Locked", layer.locked())) {
            setLocked(layer, !layer.locked());
        }

        if (insetsField("##margins", "Margins (top, right, bottom, left)", layer.margins())) {
            apply(document.replace(rebuilt(layer, layer.name(), layer.visible(), layer.locked(),
                    layer.opacity(), insetsFromQuad())), "Change margins", "margins:" + layer.id());
        }
    }

    private void drawImageFields(Document document, Layer.Image image) {
        ImGui.textDisabled(ImGuiScreens.truncate(image.repoPath(), 44));
        if (ImGui.button("Change image", -1.0f, 0.0f)) {
            String id = image.id();
            picker.openPicker(chosen -> replaceImagePath(id, chosen));
        }

        choice.set(image.fit().ordinal());
        if (field("Fit", () -> ImGui.combo("##fit", choice, FIT_LABELS))) {
            apply(document.replace(new Layer.Image(image.id(), image.name(), image.bounds(), image.visible(),
                            image.locked(), image.opacity(), image.margins(), image.repoPath(),
                            Layer.Fit.values()[choice.get()])),
                    "Change fit", null);
        }
    }

    private void drawTextFields(Document document, Layer.Text text) {
        if (idle() && !text.text().equals(textBuffer.get())) {
            textBuffer.set(text.text());
        }
        ImGui.inputTextMultiline("##text", textBuffer, -1.0f, unit() * 5.0f, ImGuiInputTextFlags.AllowTabInput);
        if (ImGui.isItemEdited()) {
            apply(document.replace(restyled(text, textBuffer.get(), text.font(), text.size(), text.colour(),
                    text.horizontalAlign(), text.verticalAlign(), text.lineGap(), text.tracking(),
                    text.verticalScale())), "Edit text", "text:" + text.id());
        }
        settle();
        ImGui.textDisabled("Text");

        drawFontCombo(document, text);

        scalar[0] = (float) text.size();
        if (field("Size", () -> ImGui.dragFloat("##text-size", scalar, 0.5f, 1.0f, 4096.0f))) {
            apply(document.replace(restyled(text, text.text(), text.font(), Math.max(1.0, scalar[0]),
                    text.colour(), text.horizontalAlign(), text.verticalAlign(), text.lineGap(),
                    text.tracking(), text.verticalScale())), "Change text size", "textsize:" + text.id());
        }

        toRgba(text.colour(), rgba);
        if (field("Colour", () -> ImGui.colorEdit4("##text-colour", rgba, ImGuiColorEditFlags.AlphaBar))) {
            apply(document.replace(restyled(text, text.text(), text.font(), text.size(), toArgb(rgba),
                    text.horizontalAlign(), text.verticalAlign(), text.lineGap(), text.tracking(),
                    text.verticalScale())), "Change text colour", "textcolour:" + text.id());
        }

        choice.set(text.horizontalAlign().ordinal());
        if (field("Horizontal alignment", () -> ImGui.combo("##halign", choice, HORIZONTAL_LABELS))) {
            apply(document.replace(restyled(text, text.text(), text.font(), text.size(), text.colour(),
                    Layer.HorizontalAlign.values()[choice.get()], text.verticalAlign(), text.lineGap(),
                    text.tracking(), text.verticalScale())), "Change alignment", null);
        }

        choice.set(text.verticalAlign().ordinal());
        if (field("Vertical alignment", () -> ImGui.combo("##valign", choice, VERTICAL_LABELS))) {
            apply(document.replace(restyled(text, text.text(), text.font(), text.size(), text.colour(),
                    text.horizontalAlign(), Layer.VerticalAlign.values()[choice.get()], text.lineGap(),
                    text.tracking(), text.verticalScale())), "Change alignment", null);
        }

        scalar[0] = (float) text.lineGap();
        if (field("Line gap", () -> ImGui.dragFloat("##line-gap", scalar, 0.5f, -512.0f, 512.0f))) {
            apply(document.replace(restyled(text, text.text(), text.font(), text.size(), text.colour(),
                    text.horizontalAlign(), text.verticalAlign(), scalar[0], text.tracking(),
                    text.verticalScale())), "Change line gap", "linegap:" + text.id());
        }

        scalar[0] = (float) text.tracking();
        if (field("Tracking", () -> ImGui.dragFloat("##tracking", scalar, 0.1f, -64.0f, 64.0f))) {
            apply(document.replace(restyled(text, text.text(), text.font(), text.size(), text.colour(),
                    text.horizontalAlign(), text.verticalAlign(), text.lineGap(), scalar[0],
                    text.verticalScale())), "Change tracking", "tracking:" + text.id());
        }

        scalar[0] = (float) text.verticalScale();
        if (field("Vertical scale", () -> ImGui.dragFloat("##vertical-scale", scalar, 0.01f, 0.05f, 8.0f))) {
            apply(document.replace(restyled(text, text.text(), text.font(), text.size(), text.colour(),
                    text.horizontalAlign(), text.verticalAlign(), text.lineGap(), text.tracking(),
                    Math.max(0.05, scalar[0]))), "Change vertical scale", "vscale:" + text.id());
        }
    }

    private void drawFontCombo(Document document, Layer.Text text) {
        ensureFontsRequested();
        String[] families = fontFamilies;
        if (families == null) {
            ImGui.textDisabled("Reading the installed fonts...");
            return;
        }

        String chosen = null;
        ImGui.setNextItemWidth(-1.0f);
        if (ImGui.beginCombo("##font", ImGuiScreens.truncate(text.font(), 30))) {
            try {
                for (String family : families) {
                    if (ImGui.selectable(family, family.equalsIgnoreCase(text.font()))) {
                        chosen = family;
                    }
                }
            } finally {
                ImGui.endCombo();
            }
        }
        ImGui.textDisabled("Font");

        if (chosen != null) {
            apply(document.replace(restyled(text, text.text(), chosen, text.size(), text.colour(),
                            text.horizontalAlign(), text.verticalAlign(), text.lineGap(), text.tracking(),
                            text.verticalScale())),
                    "Change font", null);
        }
    }

    private void drawShapeFields(Document document, Layer.Shape shape) {
        toRgba(shape.fill(), rgba);
        if (field("Fill", () -> ImGui.colorEdit4("##fill", rgba, ImGuiColorEditFlags.AlphaBar))) {
            apply(document.replace(reshaped(shape, toArgb(rgba), shape.cornerRadius(), shape.borderColour(),
                    shape.borderWidth(), shape.padding())), "Change fill", "fill:" + shape.id());
        }

        single[0] = shape.cornerRadius();
        if (field("Corner radius", () -> ImGui.dragInt("##corner-radius", single, 1.0f, 0, 4096))) {
            apply(document.replace(reshaped(shape, shape.fill(), Math.max(0, single[0]), shape.borderColour(),
                    shape.borderWidth(), shape.padding())), "Change corner radius", "radius:" + shape.id());
        }

        toRgba(shape.borderColour(), rgba);
        if (field("Border colour",
                () -> ImGui.colorEdit4("##border-colour", rgba, ImGuiColorEditFlags.AlphaBar))) {
            apply(document.replace(reshaped(shape, shape.fill(), shape.cornerRadius(), toArgb(rgba),
                    shape.borderWidth(), shape.padding())), "Change border colour", "border:" + shape.id());
        }

        single[0] = shape.borderWidth();
        if (field("Border width", () -> ImGui.dragInt("##border-width", single, 1.0f, 0, 4096))) {
            apply(document.replace(reshaped(shape, shape.fill(), shape.cornerRadius(), shape.borderColour(),
                    Math.max(0, single[0]), shape.padding())), "Change border width", "borderwidth:" + shape.id());
        }

        if (insetsField("##shape-padding", PADDING_LABEL, shape.padding())) {
            apply(document.replace(reshaped(shape, shape.fill(), shape.cornerRadius(), shape.borderColour(),
                    shape.borderWidth(), insetsFromQuad())), "Change padding", "padding:" + shape.id());
        }
    }

    private void drawGroupFields(Document document, Layer.Group group) {
        ImGui.textDisabled(group.children().size() + " layer(s) inside");
        if (insetsField("##group-padding", PADDING_LABEL, group.padding())) {
            apply(document.replace(new Layer.Group(group.id(), group.name(), group.bounds(), group.visible(),
                            group.locked(), group.opacity(), group.margins(), insetsFromQuad(), group.children())),
                    "Change padding", "padding:" + group.id());
        }
    }

    private void drawDocumentProperties(Document document) {
        ImGui.separatorText("Document");

        if (idle() && !document.name().equals(documentNameBuffer.get())) {
            documentNameBuffer.set(document.name());
        }
        ImGui.setNextItemWidth(-1.0f);
        ImGui.inputText("##document-name", documentNameBuffer);
        if (ImGui.isItemDeactivatedAfterEdit() && !documentNameBuffer.get().isBlank()) {
            apply(document.withName(documentNameBuffer.get().trim()), "Rename document", null);
        }
        ImGui.textDisabled("Name");

        toRgba(document.background(), rgba);
        if (field("Background", () -> ImGui.colorEdit4("##background", rgba, ImGuiColorEditFlags.AlphaBar))) {
            apply(document.withBackground(toArgb(rgba)), "Change background", "background");
        }

        pair[0] = document.grid().columns();
        pair[1] = document.grid().rows();
        if (field("Frames (columns, rows)", () -> ImGui.dragInt2("##grid", pair, 0.1f, 1, 64))) {
            apply(document.withGrid(new GridSize(Math.max(1, pair[0]), Math.max(1, pair[1])),
                    document.pixelsPerFrame()), "Change frame grid", "grid");
        }

        single[0] = document.pixelsPerFrame();
        if (field("Pixels per frame", () -> ImGui.dragInt("##pixels-per-frame", single, 1.0f, 16, 2048))) {
            apply(document.withGrid(document.grid(), Math.max(16, single[0])), "Change resolution", "resolution");
        }
    }

    /** Four insets on one row, in the order CSS writes them. */
    private boolean insetsField(String id, String label, Insets insets) {
        quad[0] = insets.top();
        quad[1] = insets.right();
        quad[2] = insets.bottom();
        quad[3] = insets.left();
        return field(label, () -> ImGui.dragInt4(id, quad, 1.0f, 0, 4096));
    }

    private Insets insetsFromQuad() {
        return new Insets(Math.max(0, quad[0]), Math.max(0, quad[1]),
                Math.max(0, quad[2]), Math.max(0, quad[3]));
    }

    /**
     * A full-width control with its label underneath it.
     *
     * <p>Every property is the same four moves: take the width, draw the control,
     * end the gesture when it is let go, then label it. Written out fifteen times
     * that is exactly where a forgotten {@code settle} hides, and a forgotten one
     * silently welds two edits into a single undo.
     */
    private boolean field(String label, BooleanSupplier control) {
        ImGui.setNextItemWidth(-1.0f);
        boolean changed = control.getAsBoolean();
        settle();
        ImGui.textDisabled(label);
        return changed;
    }

    /** True when no widget is being typed into or dragged, so a buffer may be re-seeded. */
    private static boolean idle() {
        return !ImGui.isAnyItemActive();
    }

    /**
     * Ends the gesture a widget was part of, once it is let go.
     *
     * <p>Without it, a slider dragged, released and dragged again inside the coalesce
     * window merges into one undo entry, and the first drag becomes unreachable.
     */
    private void settle() {
        if (ImGui.isItemDeactivatedAfterEdit()) {
            history.endGesture();
        }
    }

    // -----------------------------------------------------------------------
    // Commands
    // -----------------------------------------------------------------------

    private void registerCommands() {
        commands.register(Command.of("editor.undo", "Undo").category("Edit")
                .hint("Step back one action")
                .shortcut(Shortcut.control(KEY_Z))
                .enabledWhen(history::canUndo)
                .does(() -> {
                    history.undo();
                    forgetMissingSelection();
                }));
        commands.register(Command.of("editor.redo", "Redo").category("Edit")
                .hint("Step forward again")
                .shortcut(Shortcut.controlShift(KEY_Z))
                .enabledWhen(history::canRedo)
                .does(() -> {
                    history.redo();
                    forgetMissingSelection();
                }));

        commands.register(Command.of("editor.duplicate", "Duplicate").category("Layer")
                .hint("Copy the selection, offset slightly")
                .shortcut(Shortcut.control(KEY_D))
                .enabledWhen(this::hasSelection)
                .does(this::duplicateSelection));
        commands.register(Command.of("editor.delete", "Delete").category("Layer")
                .hint("Remove the selected layers")
                .shortcut(Shortcut.of(KEY_DELETE))
                .enabledWhen(this::hasSelection)
                .does(this::deleteSelection));
        commands.register(Command.of("editor.selectAll", "Select all").category("Layer")
                .shortcut(Shortcut.control(KEY_A))
                .enabledWhen(() -> !history.current().layers().isEmpty())
                .does(this::selectAll));

        commands.register(Command.of("editor.group", "Group").category("Layer")
                .hint("Wrap the selection in a group that moves together")
                .shortcut(Shortcut.control(KEY_G))
                .enabledWhen(() -> selection.size() >= 2)
                .does(this::groupSelection));
        commands.register(Command.of("editor.ungroup", "Ungroup").category("Layer")
                .hint("Unwrap a group, leaving its contents where they are")
                .shortcut(Shortcut.controlShift(KEY_G))
                .enabledWhen(this::hasGroupSelected)
                .does(this::ungroupSelection));

        commands.register(Command.of("editor.front", "Bring to front").category("Order")
                .shortcut(new Shortcut(KEY_UP, true, true, false))
                .enabledWhen(this::hasSelection)
                .does(() -> apply(Edits.bringToFront(history.current(), selectionIds()), "Bring to front", null)));
        commands.register(Command.of("editor.back", "Send to back").category("Order")
                .shortcut(new Shortcut(KEY_DOWN, true, true, false))
                .enabledWhen(this::hasSelection)
                .does(() -> apply(Edits.sendToBack(history.current(), selectionIds()), "Send to back", null)));
        commands.register(Command.of("editor.fitToCanvas", "Fit layer to canvas").category("Layer")
                .hint("Size one layer to the whole canvas, for a backdrop")
                .enabledWhen(() -> selection.size() == 1)
                .does(() -> apply(Edits.fitToCanvas(history.current(), selectionIds().getFirst()),
                        "Fit to canvas", null)));

        registerNudgeCommands();
        registerAlignCommands();
        registerViewCommands();
    }

    private void registerNudgeCommands() {
        record Direction(String id, String label, int keyCode, int deltaX, int deltaY) {
        }

        List<Direction> directions = List.of(
                new Direction("left", "left", KEY_LEFT, -1, 0),
                new Direction("right", "right", KEY_RIGHT, 1, 0),
                new Direction("up", "up", KEY_UP, 0, -1),
                new Direction("down", "down", KEY_DOWN, 0, 1));

        for (Direction direction : directions) {
            commands.register(Command.of("editor.nudge." + direction.id(), "Nudge " + direction.label())
                    .category("Layer")
                    .hint("Move the selection by one pixel")
                    .shortcut(Shortcut.of(direction.keyCode()))
                    .enabledWhen(this::hasSelection)
                    .does(() -> nudge(direction.deltaX() * NUDGE_SMALL, direction.deltaY() * NUDGE_SMALL)));
            commands.register(Command.of("editor.nudge." + direction.id() + ".big",
                            "Nudge " + direction.label() + " by " + NUDGE_LARGE)
                    .category("Layer")
                    .shortcut(new Shortcut(direction.keyCode(), false, true, false))
                    .enabledWhen(this::hasSelection)
                    .does(() -> nudge(direction.deltaX() * NUDGE_LARGE, direction.deltaY() * NUDGE_LARGE)));
        }
    }

    private void registerAlignCommands() {
        String hint = "One layer aligns to the canvas, several to the box enclosing them";

        for (Alignment.Horizontal how : Alignment.Horizontal.values()) {
            String name = how.name().toLowerCase(Locale.ROOT);
            commands.register(Command.of("editor.align." + name, "Align " + name).category("Align")
                    .hint(hint)
                    .enabledWhen(this::hasSelection)
                    .does(() -> apply(Alignment.alignHorizontally(history.current(), selectionIds(), how),
                            "Align " + name, null)));
        }
        for (Alignment.Vertical how : Alignment.Vertical.values()) {
            String name = how.name().toLowerCase(Locale.ROOT);
            commands.register(Command.of("editor.align." + name, "Align " + name).category("Align")
                    .hint(hint)
                    .enabledWhen(this::hasSelection)
                    .does(() -> apply(Alignment.alignVertically(history.current(), selectionIds(), how),
                            "Align " + name, null)));
        }

        commands.register(Command.of("editor.distribute.horizontal", "Distribute horizontally").category("Align")
                .hint("Equalise the gaps, leaving the outermost two where they are")
                .enabledWhen(() -> selection.size() >= 3)
                .does(() -> apply(Alignment.distributeHorizontally(history.current(), selectionIds()),
                        "Distribute horizontally", null)));
        commands.register(Command.of("editor.distribute.vertical", "Distribute vertically").category("Align")
                .hint("Equalise the gaps, leaving the outermost two where they are")
                .enabledWhen(() -> selection.size() >= 3)
                .does(() -> apply(Alignment.distributeVertically(history.current(), selectionIds()),
                        "Distribute vertically", null)));
    }

    private void registerViewCommands() {
        commands.register(Command.of("editor.zoom.in", "Zoom in").category("View")
                .shortcut(Shortcut.control(KEY_EQUAL))
                .does(() -> pendingZoomSteps++));
        commands.register(Command.of("editor.zoom.out", "Zoom out").category("View")
                .shortcut(Shortcut.control(KEY_MINUS))
                .does(() -> pendingZoomSteps--));
        commands.register(Command.of("editor.zoom.fit", "Zoom to fit").category("View")
                .hint("Put the whole canvas back on screen")
                .shortcut(Shortcut.control(KEY_ZERO))
                .does(() -> fitRequested = true));
        commands.register(Command.of("editor.snap", "Toggle snapping").category("View")
                .hint("Hold Alt to suspend it for one drag")
                .does(() -> snapEnabled = !snapEnabled));
    }

    private void nudge(int deltaX, int deltaY) {
        // Keyed on the selection, so holding an arrow key is one undo but nudging
        // something else afterwards starts a new one.
        apply(Edits.nudge(history.current(), selectionIds(), deltaX, deltaY), "Nudge layer",
                "nudge:" + String.join(",", selection));
    }

    private void duplicateSelection() {
        Edits.Result result = Edits.duplicate(history.current(), selectionIds());
        apply(result.document(), "Duplicate layer", null);
        select(result.createdIds());
    }

    private void deleteSelection() {
        apply(Edits.remove(history.current(), selectionIds()), "Delete layer", null);
        selection.clear();
    }

    private void groupSelection() {
        Edits.Result result = Edits.group(history.current(), selectionIds(), Insets.NONE);
        apply(result.document(), "Group layers", null);
        select(result.createdIds());
    }

    private void ungroupSelection() {
        Edits.Result result = Edits.ungroup(history.current(), selectionIds().getFirst());
        apply(result.document(), "Ungroup", null);
        select(result.createdIds());
    }

    private void selectAll() {
        selection.clear();
        history.current().layers().forEach(layer -> selection.add(layer.id()));
    }

    private void select(List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        selection.clear();
        selection.addAll(ids);
    }

    private boolean hasSelection() {
        return !selection.isEmpty();
    }

    private boolean hasGroupSelected() {
        return selection.size() == 1
                && history.current().byId(selectionIds().getFirst()).orElse(null) instanceof Layer.Group;
    }

    private List<String> selectionIds() {
        return List.copyOf(selection);
    }

    private Optional<Layer> singleSelection(Document document) {
        if (selection.size() != 1) {
            return Optional.empty();
        }
        return document.byId(selection.iterator().next());
    }

    /** An undo, or a delete, can leave the selection pointing at layers that have gone. */
    private void forgetMissingSelection() {
        Document document = history.current();
        selection.removeIf(id -> document.byId(id).isEmpty());
        if (renamingId != null && document.byId(renamingId).isEmpty()) {
            renamingId = null;
        }
    }

    private void apply(Document next, String label, String coalesceKey) {
        history.push(next, label, coalesceKey);
    }

    /**
     * Tells the recovery snapshot what is on screen.
     *
     * <p>Done here rather than at each edit because drags push straight to the
     * history, and one missed call is an hour of work that quietly was not
     * protected. Documents are immutable, so an unchanged frame is a single
     * reference comparison; the snapshot itself is written on a timer, off this
     * thread.
     */
    private void noteForRecovery() {
        if (files.holdsRecovery()) {
            return;
        }
        Document current = history.current();
        if (current == recorded) {
            return;
        }
        recorded = current;
        services.recovery.record(current, services.activeRepositoryId());
    }

    // -----------------------------------------------------------------------
    // Adding layers
    // -----------------------------------------------------------------------

    /**
     * Adds a repository image to the document, from outside the editor.
     *
     * <p>So the browser can put something on the canvas without you having to come
     * here and find it again through a picker. Seeing a sign and wanting it are the
     * same moment, and making that cost a tab change and a second search is how a
     * session turns into admin.
     *
     * <p>Safe before the editor has ever been drawn: the document lives in the
     * services, not in this panel.
     */
    public void addImage(RepoImage image) {
        addImageLayer(image);
    }

    private void addImageLayer(RepoImage image) {
        Document document = history.current();
        Layer.Bounds bounds = centred(document, image.width(), image.height());
        Layer.Image layer = new Layer.Image(Edits.newId("image"), image.displayName(), bounds, true, false,
                1.0, Insets.NONE, image.path(), Layer.Fit.CONTAIN);

        apply(document.add(layer), "Add image layer", null);
        select(List.of(layer.id()));
    }

    private void addTextLayer() {
        Document document = history.current();
        Layer.Bounds bounds = centred(document, document.width() / 2, Math.max(24, document.height() / 4));
        Layer.Text layer = new Layer.Text(Edits.newId("text"), "Text", bounds, true, false, 1.0, Insets.NONE,
                "Text", FontRegistry.DEFAULT_FONT, Math.max(12.0, bounds.height() * 0.6), 0xFFFFFFFF,
                Layer.HorizontalAlign.CENTRE, Layer.VerticalAlign.MIDDLE, 0.0, 0.0, 1.0);

        apply(document.add(layer), "Add text layer", null);
        select(List.of(layer.id()));
    }

    private void addShapeLayer() {
        Document document = history.current();
        Layer.Bounds bounds = centred(document, document.width() / 2, document.height() / 2);
        Layer.Shape layer = new Layer.Shape(Edits.newId("shape"), "Shape", bounds, true, false, 1.0,
                Insets.NONE, Insets.NONE, 0xFFFFFFFF, 0, 0x00000000, 0);

        apply(document.add(layer), "Add shape layer", null);
        select(List.of(layer.id()));
    }

    private void replaceImagePath(String layerId, RepoImage image) {
        Document document = history.current();
        if (!(document.byId(layerId).orElse(null) instanceof Layer.Image existing)) {
            return;
        }
        apply(document.replace(new Layer.Image(existing.id(), existing.name(), existing.bounds(),
                        existing.visible(), existing.locked(), existing.opacity(), existing.margins(),
                        image.path(), existing.fit())),
                "Change image", null);
    }

    /**
     * A new layer lands in the middle of the canvas, scaled down to fit inside it.
     *
     * <p>Predictable beats clever: putting it where the view happens to be scrolled
     * would drop layers off the canvas entirely whenever someone was zoomed into a
     * corner.
     */
    static Layer.Bounds centred(Document document, int width, int height) {
        double factor = Math.min(1.0,
                Math.min(document.width() / (double) Math.max(1, width),
                        document.height() / (double) Math.max(1, height)));
        int fittedWidth = Math.max(1, (int) Math.round(width * factor));
        int fittedHeight = Math.max(1, (int) Math.round(height * factor));
        return new Layer.Bounds((document.width() - fittedWidth) / 2, (document.height() - fittedHeight) / 2,
                fittedWidth, fittedHeight);
    }

    // -----------------------------------------------------------------------
    // Rendering, off the render thread
    // -----------------------------------------------------------------------

    /**
     * Starts a render once the document has settled.
     *
     * <p>Called from {@code draw}, but nothing here renders: it starts a virtual
     * thread and returns. One render runs at a time, so a drag that keeps changing
     * the document produces a stream of previews rather than a queue of them.
     */
    private void maybeStartRender() {
        Document document = history.current();
        if (document.equals(renderedDocument)) {
            dirtyAtMillis = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (dirtyAtMillis == 0L) {
            dirtyAtMillis = now;
            return;
        }
        if (now - dirtyAtMillis < RENDER_DEBOUNCE_MILLIS) {
            return;
        }
        if (!rendering.compareAndSet(false, true)) {
            return;
        }

        dirtyAtMillis = 0L;
        renderedDocument = document;
        int sequence = ++renderSequence;

        // The repository root is read here rather than on the worker. Which workspace
        // is active is client-thread state, and this also pins the render to the
        // repository it started against if someone switches mid-render.
        Path root = services.repo().root();

        Thread.ofVirtual().name("mcmarkings-editor-render")
                .start(() -> renderInBackground(document, sequence, root));
    }

    private void renderInBackground(Document document, int sequence, Path root) {
        try {
            // A renderer per render, because problems() belongs to the most recent
            // one and sharing an instance would make that report a race.
            DocumentRenderer renderer = new DocumentRenderer(services.fonts, services.composer);
            BufferedImage rendered = renderer.render(document,
                    RepositoryImages.in(services.composer, root));
            List<String> problems = renderer.problems();
            String key = "editor-preview/" + sequence;

            // upload converts the pixels on the calling thread and only hops to the
            // render thread for the GL call, which is exactly why it is called here.
            services.thumbnails.upload(key, downscaleForPreview(rendered))
                    .thenAccept(handle -> Minecraft.getInstance()
                            .execute(() -> onRendered(sequence, key, handle, problems)))
                    .exceptionally(throwable -> {
                        McMarkingsCompanion.LOGGER.error("[mcmarkings] editor preview upload failed", throwable);
                        return null;
                    });
        } catch (RuntimeException failure) {
            McMarkingsCompanion.LOGGER.error("[mcmarkings] editor render failed", failure);
            Minecraft.getInstance().execute(() -> onRenderFailed(sequence, String.valueOf(failure)));
        } finally {
            rendering.set(false);
        }
    }


    private BufferedImage downscaleForPreview(BufferedImage rendered) {
        int longest = Math.max(rendered.getWidth(), rendered.getHeight());
        if (longest <= PREVIEW_MAX_EDGE) {
            return rendered;
        }
        double factor = PREVIEW_MAX_EDGE / (double) longest;
        return services.composer.scale(rendered,
                Math.max(1, (int) Math.round(rendered.getWidth() * factor)),
                Math.max(1, (int) Math.round(rendered.getHeight() * factor)));
    }

    private void onRendered(int sequence, String key, TextureHandle handle, List<String> problems) {
        if (sequence != renderSequence) {
            // A newer render landed first. Showing this one would show an older
            // document, so it goes rather than what is already on screen.
            services.thumbnails.evict(key);
            return;
        }

        String previous = textureKey;
        textureKey = key;
        texture = handle;
        renderProblems = problems;
        renderFailure = null;

        if (previous != null && !previous.equals(key)) {
            services.thumbnails.evict(previous);
        }
    }

    private void onRenderFailed(int sequence, String message) {
        if (sequence != renderSequence) {
            return;
        }
        renderFailure = message;
    }

    private void ensureFontsRequested() {
        if (fontsRequested) {
            return;
        }
        fontsRequested = true;
        Thread.ofVirtual().name("mcmarkings-editor-fonts")
                .start(() -> fontFamilies = services.fonts.availableFamilies().toArray(new String[0]));
    }

    // -----------------------------------------------------------------------
    // Small helpers
    // -----------------------------------------------------------------------

    private float screenX(int documentX, float originX) {
        return originX + panX + (float) (documentX * zoom);
    }

    private float screenY(int documentY, float originY) {
        return originY + panY + (float) (documentY * zoom);
    }

    private int mouseDocX(float originX) {
        return (int) Math.floor((ImGui.getMousePosX() - originX - panX) / zoom);
    }

    private int mouseDocY(float originY) {
        return (int) Math.floor((ImGui.getMousePosY() - originY - panY) / zoom);
    }

    private float handleHalfSize() {
        return Math.max(3.0f, unit() * 0.25f);
    }

    /** The one metric everything else is sized from, so the layout follows the GUI scale. */
    private static float unit() {
        return Math.max(8.0f, ImGui.getTextLineHeight());
    }

    private static String displayName(Layer layer) {
        return layer.name() == null || layer.name().isBlank() ? kindOf(layer) : layer.name();
    }

    private static String kindOf(Layer layer) {
        return switch (layer) {
            case Layer.Image ignored -> "Image";
            case Layer.Text ignored -> "Text";
            case Layer.Shape ignored -> "Shape";
            case Layer.Group ignored -> "Group";
        };
    }

    private static String describe(Layer.Bounds bounds) {
        return bounds.width() + " x " + bounds.height() + " at " + bounds.x() + ", " + bounds.y();
    }

    /**
     * Rebuilds a layer's shared frame without touching the kind-specific parts.
     *
     * <p>{@link Layer} is sealed and every kind is a record, so there is no generic
     * wither. One switch here beats the same four cases at every call site.
     */
    private static Layer rebuilt(Layer layer, String name, boolean visible, boolean locked,
            double opacity, Insets margins) {
        return switch (layer) {
            case Layer.Image image -> new Layer.Image(image.id(), name, image.bounds(), visible, locked,
                    opacity, margins, image.repoPath(), image.fit());
            case Layer.Text text -> new Layer.Text(text.id(), name, text.bounds(), visible, locked, opacity,
                    margins, text.text(), text.font(), text.size(), text.colour(), text.horizontalAlign(),
                    text.verticalAlign(), text.lineGap(), text.tracking(), text.verticalScale());
            case Layer.Shape shape -> new Layer.Shape(shape.id(), name, shape.bounds(), visible, locked,
                    opacity, margins, shape.padding(), shape.fill(), shape.cornerRadius(),
                    shape.borderColour(), shape.borderWidth());
            case Layer.Group group -> new Layer.Group(group.id(), name, group.bounds(), visible, locked,
                    opacity, margins, group.padding(), group.children());
        };
    }

    private static Layer.Text restyled(Layer.Text from, String body, String font, double size, int colour,
            Layer.HorizontalAlign horizontal, Layer.VerticalAlign vertical, double lineGap, double tracking,
            double verticalScale) {
        return new Layer.Text(from.id(), from.name(), from.bounds(), from.visible(), from.locked(),
                from.opacity(), from.margins(), body, font, size, colour, horizontal, vertical, lineGap,
                tracking, verticalScale);
    }

    private static Layer.Shape reshaped(Layer.Shape from, int fill, int cornerRadius, int borderColour,
            int borderWidth, Insets padding) {
        return new Layer.Shape(from.id(), from.name(), from.bounds(), from.visible(), from.locked(),
                from.opacity(), from.margins(), padding, fill, cornerRadius, borderColour, borderWidth);
    }

    static void toRgba(int argb, float[] target) {
        target[0] = ((argb >> 16) & 0xFF) / 255.0f;
        target[1] = ((argb >> 8) & 0xFF) / 255.0f;
        target[2] = (argb & 0xFF) / 255.0f;
        target[3] = ((argb >>> 24) & 0xFF) / 255.0f;
    }

    static int toArgb(float[] source) {
        return (channel(source[3]) << 24) | (channel(source[0]) << 16)
                | (channel(source[1]) << 8) | channel(source[2]);
    }

    private static int channel(float value) {
        return Math.clamp(Math.round(value * 255.0f), 0, 255);
    }

    /** One control on the toolbar. A null command id is a divider rather than a button. */
    private record ToolbarItem(String label, String commandId) {
    }

    /** A drag in progress on the canvas. */
    private record Drag(Handle handle, String primaryId, Map<String, Layer.Bounds> startBounds,
            int startDocX, int startDocY, boolean additive) {
    }

    /** What a canvas drag is doing. */
    enum Handle {
        MOVE,
        MARQUEE,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
        TOP,
        BOTTOM,
        LEFT,
        RIGHT;

        /** Corners first, so a corner wins over the two edges that meet there. */
        private static final List<Handle> RESIZE_HANDLES =
                List.of(TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, TOP, BOTTOM, LEFT, RIGHT);

        private boolean movesLeftEdge() {
            return this == TOP_LEFT || this == LEFT || this == BOTTOM_LEFT;
        }

        private boolean movesRightEdge() {
            return this == TOP_RIGHT || this == RIGHT || this == BOTTOM_RIGHT;
        }

        private boolean movesTopEdge() {
            return this == TOP_LEFT || this == TOP || this == TOP_RIGHT;
        }

        private boolean movesBottomEdge() {
            return this == BOTTOM_LEFT || this == BOTTOM || this == BOTTOM_RIGHT;
        }
    }
}
