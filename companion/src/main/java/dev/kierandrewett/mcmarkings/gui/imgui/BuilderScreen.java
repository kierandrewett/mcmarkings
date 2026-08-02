package dev.kierandrewett.mcmarkings.gui.imgui;

import cn.enaium.fabric.imgui.ImGuiRenderable;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.kierandrewett.mcmarkings.CompanionServices;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.core.RepoImage;
import dev.kierandrewett.mcmarkings.gui.BrowserScreen;
import dev.kierandrewett.mcmarkings.imageframe.ImageFrameCommands;
import dev.kierandrewett.mcmarkings.texture.TextureHandle;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiMouseButton;
import imgui.type.ImInt;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The WYSIWYG sign builder: drag images out of the repository onto a canvas that
 * shows exactly where the item frame boundaries will fall, then publish the
 * flattened result.
 *
 * <p>The canvas works in "design pixels", one map frame being 128 of them, which
 * is what makes the cell grid meaningful. Export multiplies that by
 * {@code exportPixelsPerFrame} so the pushed PNG carries more detail than a
 * vanilla map would, without changing any of the coordinates on screen.
 *
 * <p>Placed items are held in z order: index 0 is the bottom of the stack. The
 * {@code z} field only appears when the layout is serialised, where an index is
 * not implicit.
 */
public class BuilderScreen extends Screen implements ImGuiRenderable {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String PAYLOAD_TYPE = "mcmarkings.repo-image";
    private static final String LAYOUT_SUFFIX = ".layout.json";

    private static final float PALETTE_WIDTH = 260.0f;
    private static final float INSPECTOR_WIDTH = 240.0f;
    private static final float PALETTE_CELL = 40.0f;
    private static final int PALETTE_LIMIT = 80;
    private static final int TEXT_BUFFER = 256;

    private static final float MIN_ZOOM = 0.05f;
    private static final float MAX_ZOOM = 12.0f;

    /** Beyond this a grid stops being something anyone places by hand. */
    private static final int MAX_GRID_DIMENSION = 8;

    /** An 8x8 grid at 1024 per frame is already a 268MB image; refuse to go past it. */
    private static final int MAX_PIXELS_PER_FRAME = 1024;
    private static final int MIN_PIXELS_PER_FRAME = 16;

    private final CompanionServices services;
    private final ImGuiScreens.Status status = new ImGuiScreens.Status();
    private final PublishFlow publish;

    private final ImString search = new ImString("", TEXT_BUFFER);
    private final ImString name = new ImString("composition", TEXT_BUFFER);
    private final ImInt columns = new ImInt(2);
    private final ImInt rows = new ImInt(1);

    private List<RepoImage> results = List.of();
    private final Set<String> requestedThumbnails = new HashSet<>();

    /** Bottom of the stack first. */
    private final List<Placed> items = new ArrayList<>();
    private int selectedIndex = -1;

    private float panX;
    private float panY;
    private float zoom = 1.0f;
    private boolean viewFitted;
    private boolean panning;
    private boolean draggingItem;

    private List<String> layouts = List.of();
    private boolean composing;

    private final float[] positionScratch = new float[2];
    private final float[] scaleScratch = new float[1];

    private ImGuiIO io;
    private String renderError;

    public BuilderScreen(CompanionServices services) {
        super(Component.literal("MCMarkings builder"));
        this.services = services;
        this.publish = new PublishFlow(services, status);
    }

    @Override
    protected void init() {
        refreshResults();
        scanLayouts();
    }

    /**
     * The wrapper chains GLFW callbacks rather than consuming them, so escape would
     * otherwise close the screen while the user is typing a name into ImGui.
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (io != null && io.getWantCaptureKeyboard()) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (io != null && io.getWantCaptureMouse()) {
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (io != null && io.getWantCaptureMouse()) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    /** Nothing may escape: this is called from inside the game's frame loop. */
    @Override
    public void render(ImGuiIO frameIo) {
        this.io = frameIo;
        try {
            ImGuiScreens.fullViewportWindow("##mcmarkings-builder", this::drawBody);
            renderError = null;
        } catch (Throwable throwable) {
            renderError = String.valueOf(throwable);
            McMarkingsCompanion.LOGGER.error("[mcmarkings] builder screen render failed", throwable);
        }
    }

    private void drawBody() {
        drawHeader();
        ImGui.separator();

        // ImGui reads a negative child size as "the remaining space minus this", so
        // a window too small for the panes would silently invert the layout.
        float bodyHeight = Math.max(64.0f, ImGui.getContentRegionAvailY() - ImGui.getFrameHeightWithSpacing());
        ImGuiScreens.child("##palette", PALETTE_WIDTH, bodyHeight, this::drawPalette);
        ImGui.sameLine();
        float canvasWidth = Math.max(120.0f, ImGui.getContentRegionAvailX() - INSPECTOR_WIDTH - 8.0f);
        ImGuiScreens.child("##canvas", canvasWidth, bodyHeight, this::drawCanvas);
        ImGui.sameLine();
        ImGuiScreens.child("##inspector", 0.0f, bodyHeight, this::drawInspector);

        status.draw();
    }

    private void drawHeader() {
        // This is an ImGui window, so none of the mod's normal navigation is on
        // screen. Without a way back the only exit is closing the game's screen
        // entirely, which reads as being stranded.
        if (ImGui.button("< Back")) {
            Minecraft.getInstance().setScreen(new BrowserScreen(services));
        }

        ImGui.sameLine();
        ImGui.setNextItemWidth(160.0f);
        ImGui.inputTextWithHint("##name", "Map name", name);

        ImGui.sameLine();
        ImGui.setNextItemWidth(110.0f);
        if (ImGui.inputInt("Columns", columns, 1)) {
            columns.set(Math.clamp(columns.get(), 1, MAX_GRID_DIMENSION));
            viewFitted = false;
        }

        ImGui.sameLine();
        ImGui.setNextItemWidth(110.0f);
        if (ImGui.inputInt("Rows", rows, 1)) {
            rows.set(Math.clamp(rows.get(), 1, MAX_GRID_DIMENSION));
            viewFitted = false;
        }

        GridSize gridSize = grid();
        ImGui.sameLine();
        ImGui.textDisabled(gridSize.pixelWidth() + "x" + gridSize.pixelHeight()
                + " design px, " + gridSize.frameCount() + " frames");

        ImGui.beginDisabled(composing || publish.running() || items.isEmpty());
        if (ImGui.button("Save & publish")) {
            composeAndPublish();
        }
        ImGui.endDisabled();

        ImGui.sameLine();
        if (ImGui.button("Get frames")) {
            services.commands.send(ImageFrameCommands.giveInvisibleFrames(
                    services.config.commandAlias, services.config.glowingFrames, gridSize.frameCount()));
            status.good("Requested " + gridSize.frameCount() + " invisible frames");
        }

        ImGui.sameLine();
        if (ImGui.button("Fit view")) {
            viewFitted = false;
        }

        ImGui.sameLine();
        if (ImGui.button("Clear")) {
            items.clear();
            selectedIndex = -1;
        }

        ImGui.sameLine();
        if (ImGui.button("Close")) {
            onClose();
        }

        if (composing) {
            ImGui.sameLine();
            ImGui.textDisabled("Composing...");
        }
        if (renderError != null) {
            ImGui.sameLine();
            ImGui.textColored(0.95f, 0.45f, 0.45f, 1.0f,
                    "render error: " + ImGuiScreens.truncate(renderError, 100));
        }
    }

    private void drawPalette() {
        ImGui.setNextItemWidth(-1.0f);
        if (ImGui.inputTextWithHint("##search", "Search images", search)) {
            refreshResults();
        }
        ImGui.separator();

        for (RepoImage image : results) {
            drawPaletteRow(image);
        }

        if (results.isEmpty()) {
            ImGui.textDisabled("No matches");
        }

        drawLayoutList();
    }

    private void drawPaletteRow(RepoImage image) {
        TextureHandle handle = services.thumbnails.peek(image).orElse(null);
        if (handle == null && requestedThumbnails.add(image.path())) {
            // Fire and forget: the next frame that finds it resident will draw it.
            services.thumbnails.request(image);
        }

        float rowWidth = Math.max(PALETTE_CELL + 8.0f, ImGui.getContentRegionAvailX());
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();

        ImGui.selectable("##palette-" + image.path(), false, 0, rowWidth, PALETTE_CELL);

        boolean dragging = ImGui.beginDragDropSource();
        if (dragging) {
            ImGui.setDragDropPayload(PAYLOAD_TYPE, image.path());
            ImGui.text(ImGuiScreens.truncate(image.displayName(), 40));
            ImGui.endDragDropSource();
        } else if (ImGui.isItemHovered()) {
            ImGui.setTooltip(image.path() + "\n" + image.width() + " x " + image.height() + " px");
        }

        float scale = ImGuiScreens.fitScale(image.width(), image.height(), PALETTE_CELL, PALETTE_CELL);
        float drawWidth = Math.max(1.0f, image.width() * scale);
        float drawHeight = Math.max(1.0f, image.height() * scale);

        ImDrawList drawList = ImGui.getWindowDrawList();
        float imageX = x + (PALETTE_CELL - drawWidth) * 0.5f;
        float imageY = y + (PALETTE_CELL - drawHeight) * 0.5f;
        ImGuiScreens.drawImage(drawList, handle, imageX, imageY, imageX + drawWidth, imageY + drawHeight);
        drawList.addText(x + PALETTE_CELL + 6.0f, y + PALETTE_CELL * 0.5f - 7.0f,
                ImGui.getColorU32(0.85f, 0.85f, 0.85f, 1.0f),
                ImGuiScreens.truncate(image.displayName(), 24));
    }

    private void drawLayoutList() {
        ImGui.separator();
        if (!ImGui.collapsingHeader("Saved layouts")) {
            return;
        }
        if (ImGui.button("Rescan##layouts")) {
            scanLayouts();
        }
        if (layouts.isEmpty()) {
            ImGui.textDisabled("None in " + services.config.generatedDirectory);
            return;
        }
        for (String layout : layouts) {
            if (ImGui.selectable(ImGuiScreens.truncate(layout, 28) + "##layout-" + layout)) {
                loadLayout(layout);
            }
        }
    }

    private void drawCanvas() {
        float availWidth = Math.max(64.0f, ImGui.getContentRegionAvailX());
        float availHeight = Math.max(64.0f, ImGui.getContentRegionAvailY());
        float originX = ImGui.getCursorScreenPosX();
        float originY = ImGui.getCursorScreenPosY();

        GridSize gridSize = grid();
        if (!viewFitted) {
            fitView(gridSize, availWidth, availHeight);
        }

        ImGui.invisibleButton("##canvas-surface", availWidth, availHeight);
        boolean hovered = ImGui.isItemHovered();

        acceptDrop(originX, originY, gridSize);
        handleZoom(hovered, originX, originY);
        handlePan(hovered);
        handleSelectionAndDrag(hovered, originX, originY);

        ImDrawList drawList = ImGui.getWindowDrawList();
        drawList.pushClipRect(originX, originY, originX + availWidth, originY + availHeight, true);
        try {
            paintCanvas(drawList, originX, originY, gridSize);
        } finally {
            drawList.popClipRect();
        }
    }

    private void paintCanvas(ImDrawList drawList, float originX, float originY, GridSize gridSize) {
        float left = toScreenX(originX, 0.0f);
        float top = toScreenY(originY, 0.0f);
        float right = toScreenX(originX, gridSize.pixelWidth());
        float bottom = toScreenY(originY, gridSize.pixelHeight());

        drawList.addRectFilled(left, top, right, bottom, ImGui.getColorU32(0.10f, 0.10f, 0.12f, 1.0f));

        for (int index = 0; index < items.size(); index++) {
            Placed item = items.get(index);
            TextureHandle handle = handleFor(item);
            float itemLeft = toScreenX(originX, item.x);
            float itemTop = toScreenY(originY, item.y);
            float itemRight = toScreenX(originX, item.x + item.width());
            float itemBottom = toScreenY(originY, item.y + item.height());

            ImGuiScreens.drawImage(drawList, handle, itemLeft, itemTop, itemRight, itemBottom);

            if (index == selectedIndex) {
                drawList.addRect(itemLeft - 1.0f, itemTop - 1.0f, itemRight + 1.0f, itemBottom + 1.0f,
                        ImGui.getColorU32(1.0f, 0.78f, 0.2f, 1.0f), 0.0f, 0, 2.0f);
            }
        }

        // Frame boundaries last so they stay readable over dark artwork; this is the
        // whole point of the canvas, because a boundary through a letter looks wrong
        // once it is on a wall.
        int lineColour = ImGui.getColorU32(1.0f, 1.0f, 1.0f, 0.25f);
        for (int column = 1; column < gridSize.columns(); column++) {
            float x = toScreenX(originX, (float) column * GridSize.MAP_PIXELS);
            drawList.addLine(x, top, x, bottom, lineColour);
        }
        for (int row = 1; row < gridSize.rows(); row++) {
            float y = toScreenY(originY, (float) row * GridSize.MAP_PIXELS);
            drawList.addLine(left, y, right, y, lineColour);
        }
        drawList.addRect(left, top, right, bottom, ImGui.getColorU32(1.0f, 1.0f, 1.0f, 0.65f));
    }

    private void drawInspector() {
        ImGui.text("Placed: " + items.size());
        ImGui.textDisabled(String.format("zoom %.0f%%", zoom * 100.0f));
        ImGui.separator();

        if (selectedIndex < 0 || selectedIndex >= items.size()) {
            ImGui.textDisabled("Click an item on the canvas");
            return;
        }

        Placed item = items.get(selectedIndex);
        ImGui.textWrapped(ImGuiScreens.truncate(item.repoPath, 60));
        ImGui.textDisabled(item.nativeWidth + " x " + item.nativeHeight + " px source");
        ImGui.separator();

        positionScratch[0] = item.x;
        positionScratch[1] = item.y;
        ImGui.setNextItemWidth(-1.0f);
        if (ImGui.dragFloat2("##position", positionScratch, 0.5f)) {
            item.x = positionScratch[0];
            item.y = positionScratch[1];
        }
        ImGui.textDisabled("Position (design px)");

        scaleScratch[0] = item.scale;
        ImGui.setNextItemWidth(-1.0f);
        if (ImGui.dragFloat("##scale", scaleScratch, 0.005f, 0.01f, 20.0f)) {
            item.scale = Math.clamp(scaleScratch[0], 0.01f, 20.0f);
        }
        ImGui.textDisabled("Scale");

        ImGui.separator();
        if (ImGui.button("Raise")) {
            swap(selectedIndex, selectedIndex + 1);
        }
        ImGui.sameLine();
        if (ImGui.button("Lower")) {
            swap(selectedIndex, selectedIndex - 1);
        }
        ImGui.sameLine();
        if (ImGui.button("Centre")) {
            GridSize gridSize = grid();
            item.x = (gridSize.pixelWidth() - item.width()) * 0.5f;
            item.y = (gridSize.pixelHeight() - item.height()) * 0.5f;
        }

        if (ImGui.button("Delete")) {
            items.remove(selectedIndex);
            selectedIndex = -1;
        }
    }

    // Canvas interaction.

    private void fitView(GridSize gridSize, float availWidth, float availHeight) {
        zoom = Math.clamp(ImGuiScreens.fitScale(gridSize.pixelWidth(), gridSize.pixelHeight(),
                availWidth - 24.0f, availHeight - 24.0f), MIN_ZOOM, MAX_ZOOM);
        panX = (availWidth - gridSize.pixelWidth() * zoom) * 0.5f;
        panY = (availHeight - gridSize.pixelHeight() * zoom) * 0.5f;
        viewFitted = true;
    }

    /** Zoom about the cursor, so the pixel under the pointer stays put. */
    private void handleZoom(boolean hovered, float originX, float originY) {
        if (!hovered || io == null) {
            return;
        }
        float wheel = io.getMouseWheel();
        if (wheel == 0.0f) {
            return;
        }

        float mouseX = io.getMousePosX();
        float mouseY = io.getMousePosY();
        float designX = toDesignX(originX, mouseX);
        float designY = toDesignY(originY, mouseY);

        zoom = Math.clamp(zoom * (float) Math.pow(1.15, wheel), MIN_ZOOM, MAX_ZOOM);
        panX = mouseX - originX - designX * zoom;
        panY = mouseY - originY - designY * zoom;
    }

    private void handlePan(boolean hovered) {
        if (io == null) {
            return;
        }
        if (hovered && ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
            panning = true;
        }
        if (!panning) {
            return;
        }
        if (!ImGui.isMouseDown(ImGuiMouseButton.Right)) {
            panning = false;
            return;
        }
        panX += mouseDeltaX();
        panY += mouseDeltaY();
    }

    /**
     * Per-frame mouse movement. ImGui reports a far out-of-range previous position
     * when the cursor was outside the window last frame, and using that as a delta
     * would fling the canvas or the dragged item off screen.
     */
    private float mouseDeltaX() {
        return hasMouseDelta() ? io.getMousePosX() - io.getMousePosPrevX() : 0.0f;
    }

    private float mouseDeltaY() {
        return hasMouseDelta() ? io.getMousePosY() - io.getMousePosPrevY() : 0.0f;
    }

    private boolean hasMouseDelta() {
        return io != null
                && ImGui.isMousePosValid(io.getMousePosX(), io.getMousePosY())
                && ImGui.isMousePosValid(io.getMousePosPrevX(), io.getMousePosPrevY());
    }

    private void handleSelectionAndDrag(boolean hovered, float originX, float originY) {
        if (io == null) {
            return;
        }

        if (hovered && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            selectedIndex = hitTest(toDesignX(originX, io.getMousePosX()),
                    toDesignY(originY, io.getMousePosY()));
            draggingItem = selectedIndex >= 0;
        }

        if (!draggingItem) {
            return;
        }
        if (!ImGui.isMouseDown(ImGuiMouseButton.Left) || selectedIndex < 0 || selectedIndex >= items.size()) {
            draggingItem = false;
            return;
        }

        Placed item = items.get(selectedIndex);
        item.x += mouseDeltaX() / zoom;
        item.y += mouseDeltaY() / zoom;
    }

    /** Topmost item wins, so the stack behaves the way it looks. */
    private int hitTest(float designX, float designY) {
        for (int index = items.size() - 1; index >= 0; index--) {
            Placed item = items.get(index);
            if (designX >= item.x && designX <= item.x + item.width()
                    && designY >= item.y && designY <= item.y + item.height()) {
                return index;
            }
        }
        return -1;
    }

    private void acceptDrop(float originX, float originY, GridSize gridSize) {
        if (!ImGui.beginDragDropTarget()) {
            return;
        }
        try {
            String repoPath = ImGui.acceptDragDropPayload(PAYLOAD_TYPE, String.class);
            if (repoPath == null || io == null) {
                return;
            }
            place(repoPath, toDesignX(originX, io.getMousePosX()),
                    toDesignY(originY, io.getMousePosY()), gridSize);
        } finally {
            ImGui.endDragDropTarget();
        }
    }

    /**
     * Dropped centred on the cursor and shrunk to fit, because repository PNGs run
     * to a couple of thousand pixels and a 1:1 drop would land entirely off-canvas.
     */
    private void place(String repoPath, float designX, float designY, GridSize gridSize) {
        RepoImage image = services.repo().byPath(repoPath).orElse(null);
        if (image == null) {
            status.bad("Unknown image " + repoPath);
            return;
        }

        float scale = Math.min(1.0f, ImGuiScreens.fitScale(image.width(), image.height(),
                gridSize.pixelWidth(), gridSize.pixelHeight()));

        Placed item = new Placed(repoPath, image.width(), image.height());
        item.scale = scale;
        item.x = designX - item.width() * 0.5f;
        item.y = designY - item.height() * 0.5f;

        items.add(item);
        selectedIndex = items.size() - 1;
        status.info("Placed " + image.displayName());
    }

    private void swap(int from, int to) {
        if (from < 0 || to < 0 || from >= items.size() || to >= items.size()) {
            return;
        }
        items.add(to, items.remove(from));
        selectedIndex = to;
    }

    private TextureHandle handleFor(Placed item) {
        RepoImage image = services.repo().byPath(item.repoPath).orElse(null);
        if (image == null) {
            return null;
        }
        TextureHandle handle = services.thumbnails.peek(image).orElse(null);
        if (handle == null && requestedThumbnails.add(item.repoPath)) {
            services.thumbnails.request(image);
        }
        return handle;
    }

    private float toScreenX(float originX, float designX) {
        return originX + panX + designX * zoom;
    }

    private float toScreenY(float originY, float designY) {
        return originY + panY + designY * zoom;
    }

    private float toDesignX(float originX, float screenX) {
        return (screenX - originX - panX) / zoom;
    }

    private float toDesignY(float originY, float screenY) {
        return (screenY - originY - panY) / zoom;
    }

    private GridSize grid() {
        return new GridSize(Math.clamp(columns.get(), 1, MAX_GRID_DIMENSION),
                Math.clamp(rows.get(), 1, MAX_GRID_DIMENSION));
    }

    // Search, layouts, publish.

    private void refreshResults() {
        results = List.copyOf(services.repo().search(search.get(), PALETTE_LIMIT));
    }

    private void scanLayouts() {
        Path directory = generatedDirectory();
        Thread.ofVirtual().start(() -> {
            List<String> found = new ArrayList<>();
            try (Stream<Path> stream = Files.list(directory)) {
                stream.map(path -> path.getFileName().toString())
                        .filter(fileName -> fileName.endsWith(LAYOUT_SUFFIX))
                        .map(fileName -> fileName.substring(0, fileName.length() - LAYOUT_SUFFIX.length()))
                        .sorted()
                        .forEach(found::add);
            } catch (NoSuchFileException exception) {
                // Nothing generated yet; an empty list is the right answer.
            } catch (IOException | RuntimeException exception) {
                McMarkingsCompanion.LOGGER.warn("[mcmarkings] could not list layouts", exception);
            }
            Minecraft.getInstance().execute(() -> layouts = List.copyOf(found));
        });
    }

    private void loadLayout(String layoutName) {
        Path path = generatedDirectory().resolve(layoutName + LAYOUT_SUFFIX);
        status.info("Loading " + layoutName + "...");

        Thread.ofVirtual().start(() -> {
            try {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                LayoutFile layout = GSON.fromJson(json, LayoutFile.class);
                Minecraft.getInstance().execute(() -> applyLayout(layoutName, layout));
            } catch (IOException | RuntimeException exception) {
                McMarkingsCompanion.LOGGER.error("[mcmarkings] could not read layout " + layoutName, exception);
                Minecraft.getInstance().execute(() ->
                        status.bad("Could not read layout: " + exception.getMessage()));
            }
        });
    }

    private void applyLayout(String layoutName, LayoutFile layout) {
        if (layout == null) {
            status.bad("Layout " + layoutName + " is empty or malformed");
            return;
        }

        columns.set(Math.clamp(Math.max(1, layout.columns), 1, MAX_GRID_DIMENSION));
        rows.set(Math.clamp(Math.max(1, layout.rows), 1, MAX_GRID_DIMENSION));
        name.set(layoutName);

        items.clear();
        selectedIndex = -1;
        viewFitted = false;

        int skipped = 0;
        List<LayoutItem> stored = layout.items == null ? List.of() : layout.items;
        for (LayoutItem entry : stored.stream()
                .filter(entry -> entry != null && entry.repoPath != null)
                .sorted((left, right) -> Integer.compare(left.z, right.z))
                .toList()) {
            RepoImage image = services.repo().byPath(entry.repoPath).orElse(null);
            if (image == null) {
                skipped++;
                continue;
            }
            Placed item = new Placed(entry.repoPath, image.width(), image.height());
            item.x = entry.x;
            item.y = entry.y;
            item.scale = entry.scale <= 0.0f ? 1.0f : entry.scale;
            items.add(item);
        }

        status.good("Loaded " + layoutName + ", " + items.size() + " items"
                + (skipped > 0 ? ", " + skipped + " missing from the repository" : ""));
    }

    private String layoutJson(GridSize gridSize) {
        LayoutFile layout = new LayoutFile();
        layout.columns = gridSize.columns();
        layout.rows = gridSize.rows();
        for (int index = 0; index < items.size(); index++) {
            Placed item = items.get(index);
            LayoutItem entry = new LayoutItem();
            entry.repoPath = item.repoPath;
            entry.x = item.x;
            entry.y = item.y;
            entry.scale = item.scale;
            entry.z = index;
            layout.items.add(entry);
        }
        return GSON.toJson(layout);
    }

    /**
     * Flattening reads every source PNG off disk at full resolution, so it cannot
     * happen on the render thread. The publish itself is handed over only once the
     * image exists.
     */
    private void composeAndPublish() {
        GridSize gridSize = grid();
        List<Placed> snapshot = List.copyOf(items);
        String json = layoutJson(gridSize);
        String requestedName = name.get();

        composing = true;
        status.info("Composing " + gridSize + "...");

        Thread.ofVirtual().start(() -> {
            try {
                BufferedImage image = compose(snapshot, gridSize);
                Minecraft.getInstance().execute(() -> {
                    composing = false;
                    publish.publish(new PublishFlow.Request(requestedName, image, gridSize, json), result ->
                            scanLayouts());
                });
            } catch (IOException | RuntimeException exception) {
                McMarkingsCompanion.LOGGER.error("[mcmarkings] compose failed", exception);
                Minecraft.getInstance().execute(() -> {
                    composing = false;
                    status.bad("Compose failed: " + exception.getMessage());
                });
            }
        });
    }

    private BufferedImage compose(List<Placed> snapshot, GridSize gridSize) throws IOException {
        int pixelsPerFrame = Math.clamp(services.config.exportPixelsPerFrame,
                MIN_PIXELS_PER_FRAME, MAX_PIXELS_PER_FRAME);
        float exportScale = pixelsPerFrame / (float) GridSize.MAP_PIXELS;

        BufferedImage output = new BufferedImage(gridSize.columns() * pixelsPerFrame,
                gridSize.rows() * pixelsPerFrame, BufferedImage.TYPE_INT_ARGB);

        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);

            for (Placed item : snapshot) {
                // Read the repository PNG rather than the cached thumbnail: the
                // thumbnail exists for the browser grid and is not full resolution.
                BufferedImage source = ImageIO.read(services.repo().resolve(item.repoPath).toFile());
                if (source == null) {
                    McMarkingsCompanion.LOGGER.warn("[mcmarkings] could not decode {}", item.repoPath);
                    continue;
                }
                graphics.drawImage(source,
                        Math.round(item.x * exportScale),
                        Math.round(item.y * exportScale),
                        Math.max(1, Math.round(item.width() * exportScale)),
                        Math.max(1, Math.round(item.height() * exportScale)),
                        null);
            }
        } finally {
            graphics.dispose();
        }

        return output;
    }

    private Path generatedDirectory() {
        return services.repoRoot().resolve(services.config.generatedDirectory);
    }

    /** One image on the canvas, in design pixels. */
    private static final class Placed {

        private final String repoPath;
        private final int nativeWidth;
        private final int nativeHeight;

        private float x;
        private float y;
        private float scale = 1.0f;

        private Placed(String repoPath, int nativeWidth, int nativeHeight) {
            this.repoPath = repoPath;
            this.nativeWidth = Math.max(1, nativeWidth);
            this.nativeHeight = Math.max(1, nativeHeight);
        }

        private float width() {
            return nativeWidth * scale;
        }

        private float height() {
            return nativeHeight * scale;
        }
    }

    /**
     * On-disk layout shape. Plain mutable classes rather than records because Gson
     * reflects over fields and this file has to survive whichever Gson version
     * Minecraft happens to bundle.
     */
    static final class LayoutFile {

        int columns = 1;
        int rows = 1;
        List<LayoutItem> items = new ArrayList<>();
    }

    static final class LayoutItem {

        String repoPath;
        float x;
        float y;
        float scale = 1.0f;
        int z;
    }
}
