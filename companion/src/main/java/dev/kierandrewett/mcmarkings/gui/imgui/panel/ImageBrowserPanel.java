package dev.kierandrewett.mcmarkings.gui.imgui.panel;

import dev.kierandrewett.mcmarkings.CompanionServices;
import dev.kierandrewett.mcmarkings.core.RepoImage;
import dev.kierandrewett.mcmarkings.gui.imgui.ImGuiScreens;
import dev.kierandrewett.mcmarkings.texture.TextureHandle;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiSelectableFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A searchable grid of repository images with a preview of whatever is selected.
 *
 * <p>This is the one component the rest of the interface is built around. Picking
 * an image is the same job whether it is the main screen or a field in an editor
 * asking for one, so both go through here: {@link #draw()} fills a tab, and
 * {@link #openPicker(Consumer)} plus {@link #drawPicker()} put the identical body
 * inside a modal and hand the choice back through a callback. Two entry points,
 * one implementation, so the picker cannot drift away from the browser.
 *
 * <p>Nothing here reads a file or decodes an image. {@link CompanionServices#thumbnails}
 * is asked for what it already has, and told to prepare anything missing on a
 * worker; a cell that has nothing yet draws an outlined box. That is the whole
 * reason the grid can show a repository of several thousand PNGs without the game
 * stalling.
 *
 * <p>Rows outside the visible part of the scroll region are replaced by a spacer of
 * the exact height they would have taken. Layout cost is therefore a screenful
 * rather than the whole repository, and because the spacer is exact the scrollbar
 * still reaches the last row.
 */
public final class ImageBrowserPanel implements Panel {

    /**
     * Most images the grid will lay out at once.
     *
     * <p>Culling means the cost of a large result set is small, but not free: the
     * search itself and the row arithmetic still scale with it. A repository big
     * enough to hit this is one where searching is the faster path anyway.
     */
    private static final int MAX_RESULTS = 2000;

    private static final int SEARCH_BUFFER = 256;

    /**
     * Cell edge as a multiple of the line height rather than a pixel count.
     *
     * <p>ImGui lays out in framebuffer pixels and the font is rebuilt to match the
     * game's GUI scale, so a fixed pixel size looks right at one scale and wrong at
     * every other. Everything sized here follows the text instead.
     */
    private static final float CELL_LINES = 7.0f;

    /** A cell may stretch this far past its target before the row gains a column. */
    private static final float CELL_STRETCH_LIMIT = 1.5f;

    private static final float DETAIL_FRACTION = 0.28f;

    /** Below this the preview goes under the grid instead of beside it. */
    private static final float SIDE_BY_SIDE_LINES = 44.0f;

    private final CompanionServices services;

    /** Prefix for every widget id, so two instances on screen do not collide. */
    private final String id;

    private final String title;
    private final String pickerTitle;

    private final ImString search = new ImString("", SEARCH_BUFFER);

    private List<RepoImage> results = List.of();

    /** What the results were computed from, so they are only recomputed on a change. */
    private String resultsSignature = "";

    private RepoImage selected;

    private Consumer<RepoImage> chooseListener;
    private Consumer<RepoImage> detailExtras;

    private Consumer<RepoImage> pickerListener;
    private boolean pickerRequested;

    /** True only while the body is being drawn inside the modal. */
    private boolean pickerActive;

    /**
     * Paths with a thumbnail being prepared, and paths whose decode failed.
     *
     * <p>The in-flight set is what stops a visible cell asking again every frame,
     * and it is cleared on completion rather than kept, so an image the cache has
     * since evicted is fetched again when it scrolls back into view. Failures are
     * remembered permanently: a PNG that cannot be decoded will not decode on the
     * hundredth attempt either, and retrying every frame is a disk read per frame.
     */
    private final Set<String> requested = new HashSet<>();
    private final Set<String> failed = new HashSet<>();

    /** Reused rather than allocated per measurement, which happens every frame. */
    private final ImVec2 measurement = new ImVec2();

    private float characterWidth = 7.0f;

    public ImageBrowserPanel(CompanionServices services, String id, String title) {
        this.services = services;
        this.id = id;
        this.title = title;
        // Popups are keyed by their label, and the label is also the modal's title
        // bar, so the visible half has to come first.
        this.pickerTitle = "Pick an image##" + id;
    }

    /**
     * Called when an image is committed rather than merely highlighted: a double
     * click on a cell, or the button under the preview.
     */
    public ImageBrowserPanel onChoose(Consumer<RepoImage> listener) {
        this.chooseListener = listener;
        return this;
    }

    /**
     * Extra widgets drawn under the preview, given the selected image.
     *
     * <p>This is how a caller adds actions that mean something to it without the
     * browser having to know what they are. Skipped in picker mode, where the only
     * sensible action is choosing the image.
     */
    public ImageBrowserPanel onDetail(Consumer<RepoImage> extras) {
        this.detailExtras = extras;
        return this;
    }

    @Override
    public String title() {
        return title;
    }

    /** The highlighted image, or null when nothing has been picked yet. */
    public RepoImage selected() {
        return selected;
    }

    /**
     * Forces the result list to be rebuilt on the next frame.
     *
     * <p>Needed after a pull or a repository switch: the search is only re-run when
     * the query, the active repository or the image count changes, and a pull can
     * replace a file without changing any of those.
     */
    public void refresh() {
        resultsSignature = "";
    }

    /**
     * Arms the picker. The modal appears on the next {@link #drawPicker()} and
     * {@code onChosen} runs once, on the render thread, with the chosen image.
     */
    public void openPicker(Consumer<RepoImage> onChosen) {
        this.pickerListener = onChosen;
        this.pickerRequested = true;
    }

    /**
     * Draws the picker modal when it is open.
     *
     * <p>Call unconditionally, every frame, from inside the window that owns the
     * picker. ImGui popups are only submitted while their parent window is, so
     * skipping this on frames where nothing is open would close the modal.
     */
    public void drawPicker() {
        if (pickerRequested) {
            ImGui.openPopup(pickerTitle);
            pickerRequested = false;
        }

        ImGuiViewport viewport = ImGui.getMainViewport();
        ImGui.setNextWindowSize(viewport.getWorkSizeX() * 0.7f, viewport.getWorkSizeY() * 0.7f,
                ImGuiCond.Appearing);

        // Unlike a window, a popup's end is only paired when its begin returned true.
        if (!ImGui.beginPopupModal(pickerTitle, ImGuiWindowFlags.NoSavedSettings)) {
            return;
        }

        pickerActive = true;
        try {
            float bodyHeight = Math.max(unit() * 6.0f,
                    ImGui.getContentRegionAvailY() - ImGui.getFrameHeightWithSpacing());
            ImGuiScreens.child(id + "-picker-body", 0.0f, bodyHeight, this::draw);
            if (ImGui.button("Cancel")) {
                pickerListener = null;
                ImGui.closeCurrentPopup();
            }
        } finally {
            pickerActive = false;
            ImGui.endPopup();
        }
    }

    @Override
    public void draw() {
        measureText();

        float unit = unit();
        drawSearchRow(unit);
        refreshIfStale();

        float availWidth = Math.max(unit * 6.0f, ImGui.getContentRegionAvailX());
        float availHeight = Math.max(unit * 6.0f, ImGui.getContentRegionAvailY());
        float spacing = ImGui.getStyle().getItemSpacingX();

        if (availWidth >= unit * SIDE_BY_SIDE_LINES) {
            // The grid takes everything the preview does not, so widening the window
            // widens the grid rather than the gap either side of it.
            float detailWidth = Math.clamp(availWidth * DETAIL_FRACTION, unit * 16.0f, unit * 26.0f);
            detailWidth = Math.min(detailWidth, availWidth - unit * 20.0f);
            ImGuiScreens.child(id + "-grid", availWidth - detailWidth - spacing, availHeight, this::drawGrid);
            ImGui.sameLine();
            ImGuiScreens.child(id + "-detail", 0.0f, availHeight, this::drawDetail);
            return;
        }

        // Too narrow to keep both side by side without squeezing the grid down to a
        // couple of columns, which is worse than a shorter preview.
        float detailHeight = Math.min(availHeight * 0.45f, unit * 16.0f);
        ImGuiScreens.child(id + "-grid", 0.0f, availHeight - detailHeight - spacing, this::drawGrid);
        ImGuiScreens.child(id + "-detail", 0.0f, 0.0f, this::drawDetail);
    }

    private void drawSearchRow(float unit) {
        float trailing = unit * 14.0f;
        ImGui.setNextItemWidth(Math.max(unit * 8.0f, ImGui.getContentRegionAvailX() - trailing));
        ImGui.inputTextWithHint("##" + id + "-search", "Search images", search);

        ImGui.sameLine();
        ImGui.beginDisabled(search.get().isEmpty());
        boolean clearPressed = ImGui.button("Clear##" + id);
        ImGui.endDisabled();
        if (clearPressed) {
            search.set("");
        }

        ImGui.sameLine();
        if (services.isLoading()) {
            ImGui.textDisabled("Opening...");
            return;
        }
        ImGui.textDisabled(results.size() == MAX_RESULTS
                ? "first " + MAX_RESULTS + " matches"
                : results.size() + " of " + services.repo().images().size());
    }

    /**
     * Re-runs the search only when something it depends on has moved.
     *
     * <p>Searching is a linear pass over the repository, which is cheap but not
     * cheap enough to repeat sixty times a second for an answer that has not
     * changed.
     */
    private void refreshIfStale() {
        String signature = services.activeRepositoryId()
                + "\n" + services.repo().images().size()
                + "\n" + search.get();
        if (signature.equals(resultsSignature)) {
            return;
        }
        resultsSignature = signature;
        results = List.copyOf(services.repo().search(search.get(), MAX_RESULTS));

        // Checked against the repository rather than against the results, because an
        // image can be perfectly real and filtered out by the current search. Without
        // this, deleting a PNG outside the game and rescanning leaves the detail pane
        // showing it, and offering to place something that is no longer there: the
        // command would go out and the server would fetch a URL for a file the
        // repository does not have.
        if (selected != null && services.repo().byPath(selected.path()).isEmpty()) {
            selected = null;
        }
    }

    private void drawGrid() {
        if (results.isEmpty()) {
            drawEmptyState();
            return;
        }

        float unit = unit();
        float gap = Math.max(2.0f, unit * 0.4f);
        float region = Math.max(unit * 4.0f, ImGui.getContentRegionAvailX());
        float target = unit * CELL_LINES;

        // Column count follows the region rather than being fixed, then the cells
        // divide that region exactly, so the grid has no ragged right margin at any
        // width. The stretch limit is for the one-column case, where a single cell
        // would otherwise be as wide as the whole pane.
        int columns = Math.max(1, (int) Math.floor((region + gap) / (target + gap)));
        float cell = Math.min((region - gap * (columns - 1)) / columns, target * CELL_STRETCH_LIMIT);
        float step = cell + gap;

        int rows = (results.size() + columns - 1) / columns;
        float scrollY = ImGui.getScrollY();
        float viewHeight = Math.max(step, ImGui.getContentRegionAvailY());

        // One row of slack either side, so a row is never missing while it is
        // halfway into view.
        int firstRow = Math.max(0, (int) Math.floor(scrollY / step) - 1);
        int lastRow = Math.min(rows - 1, (int) Math.ceil((scrollY + viewHeight) / step) + 1);

        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, gap, gap);
        try {
            // The spacers are one gap short because ImGui puts item spacing between
            // them and the first drawn row. Getting this exactly right is what makes
            // the scrollbar stop at the true bottom rather than short of it.
            if (firstRow > 0) {
                ImGui.dummy(1.0f, firstRow * step - gap);
            }
            for (int row = firstRow; row <= lastRow; row++) {
                drawRow(row, columns, cell, unit);
            }
            int trailingRows = rows - 1 - lastRow;
            if (trailingRows > 0) {
                ImGui.dummy(1.0f, trailingRows * step - gap);
            }
        } finally {
            ImGui.popStyleVar();
        }
    }

    private void drawRow(int row, int columns, float cell, float unit) {
        int start = row * columns;
        int end = Math.min(results.size(), start + columns);
        for (int index = start; index < end; index++) {
            if (index > start) {
                ImGui.sameLine();
            }
            drawCell(results.get(index), cell, unit);
        }
    }

    private void drawCell(RepoImage image, float cell, float unit) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImDrawList drawList = ImGui.getWindowDrawList();

        // Submitted before the selectable so its hover and selection highlight land
        // on top of the cell background rather than under it.
        //
        // A chequerboard rather than a flat fill, because most of what this browses is
        // transparent and a transparent background is indistinguishable from a dark
        // one against a flat panel. The editor's canvas has always said so; the
        // browser is where people actually decide which image they want.
        ImGuiScreens.chequerboard(drawList, x, y, x + cell, y + cell);

        ImGui.pushID(image.path());
        try {
            boolean clicked = ImGui.selectable("##cell", image.equals(selected),
                    ImGuiSelectableFlags.AllowDoubleClick, cell, cell);
            if (clicked) {
                selected = image;
                if (ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) {
                    choose(image);
                }
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(image.displayName() + "\n" + image.path()
                        + "\n" + image.width() + " x " + image.height() + " px");
            }
        } finally {
            ImGui.popID();
        }

        drawList.addRect(x, y, x + cell, y + cell, ImGui.getColorU32(ImGuiCol.Border));

        float pad = Math.max(2.0f, unit * 0.25f);
        float captionHeight = unit * 1.1f;
        drawThumbnail(drawList, image, x + pad, y + pad,
                cell - pad * 2.0f, cell - captionHeight - pad * 2.0f);
        drawCaption(drawList, image, x + pad, y + cell - captionHeight, cell - pad * 2.0f, captionHeight);
    }

    private void drawThumbnail(ImDrawList drawList, RepoImage image,
            float x, float y, float width, float height) {
        if (width <= 1.0f || height <= 1.0f) {
            return;
        }

        TextureHandle handle = thumbnail(image);
        if (handle == null) {
            centredText(drawList, x, y, width, height, "...", ImGui.getColorU32(ImGuiCol.TextDisabled));
            return;
        }

        // Scaled from the source dimensions, not the texture's: the cache preserves
        // aspect when it thumbnails, so this keeps every shape from a square roundel
        // to a very wide direction plate undistorted.
        float scale = ImGuiScreens.fitScale(image.width(), image.height(), width, height);
        float drawWidth = Math.max(1.0f, image.width() * scale);
        float drawHeight = Math.max(1.0f, image.height() * scale);
        float left = x + (width - drawWidth) * 0.5f;
        float top = y + (height - drawHeight) * 0.5f;
        ImGuiScreens.drawImage(drawList, handle, left, top, left + drawWidth, top + drawHeight);
    }

    private void drawCaption(ImDrawList drawList, RepoImage image,
            float x, float y, float width, float height) {
        // Character count estimated from one measurement per frame rather than
        // measuring each label, because a screenful of cells would otherwise be a
        // hundred size queries across the JNI boundary for a line nobody reads
        // closely. The estimate is generous for a proportional font, so the text is
        // also clipped: a caption bleeding into the next cell is worse than a
        // truncated one.
        int limit = Math.max(4, (int) (width / Math.max(1.0f, characterWidth)));
        drawList.pushClipRect(x, y, x + width, y + height, true);
        try {
            drawList.addText(x, y, ImGui.getColorU32(ImGuiCol.Text),
                    ImGuiScreens.truncate(image.displayName(), limit));
        } finally {
            drawList.popClipRect();
        }
    }

    /**
     * Says why the grid is empty, which is four different situations.
     *
     * <p>Still loading is not the same as nothing configured, and a search that
     * matched nothing is not the same as a repository with no images in it. Telling
     * them apart is the difference between waiting and going to fix something.
     */
    private void drawEmptyState() {
        if (!services.hasConfiguredRepositories()) {
            ImGui.textWrapped("No image repository yet. Add a folder of PNGs from the Repositories tab.");
            return;
        }
        if (services.isLoading()) {
            ImGui.textDisabled("Opening the repository, one moment...");
            return;
        }
        if (!search.get().isBlank()) {
            ImGui.textDisabled("Nothing matches " + ImGuiScreens.truncate(search.get(), 40));
            return;
        }
        // The one empty state that was still a dead end, and the one a new repository
        // always starts in. The answer is not only "go and put files there": two of
        // the tabs can fill it, which is most of the point of the mod.
        ImGui.textWrapped("This repository has no PNGs in it yet.");
        ImGui.spacing();
        ImGui.textWrapped("Compose one in the Editor, or run a script in Generate, and placing it "
                + "writes the image into this repository.");
        ImGui.spacing();
        ImGui.textDisabled("Already added some outside the game? Rescan, at the top.");
    }

    private void drawDetail() {
        RepoImage image = selected;
        if (image == null) {
            ImGui.textDisabled("Pick an image to see it here");
            return;
        }

        float unit = unit();
        ImGui.textWrapped(image.displayName());
        ImGui.textDisabled(ImGuiScreens.truncate(image.path(), 72));
        ImGui.text(image.width() + " x " + image.height() + " px");
        if (image.reference() != null && !image.reference().isBlank()) {
            ImGui.textDisabled(image.reference());
        }
        ImGui.separator();

        // Never taller than it is wide, so a tall pane leaves room for the actions
        // instead of pushing them off the bottom. If they still do not fit, this
        // pane is a scrolling child and they come into view.
        float previewWidth = Math.max(unit * 4.0f, ImGui.getContentRegionAvailX());
        float previewHeight = Math.max(unit * 6.0f,
                Math.min(ImGui.getContentRegionAvailY() * 0.55f, previewWidth));
        drawPreview(image, previewWidth, previewHeight);

        ImGui.separator();
        if (pickerActive || chooseListener != null) {
            if (ImGui.button("Use this image", -1.0f, 0.0f)) {
                choose(image);
            }
        }
        if (!pickerActive && detailExtras != null) {
            detailExtras.accept(image);
        }
    }

    /**
     * The image itself, not a description of it.
     *
     * <p>Shows the cached thumbnail, which is the only version of the image that
     * exists without reading the PNG, and reading it here would be a decode on the
     * render thread. It is upscaled in a large pane and the softness shows, which is
     * a fair trade for a preview that appears instantly and never stalls a frame.
     */
    private void drawPreview(RepoImage image, float boxWidth, float boxHeight) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();

        ImDrawList drawList = ImGui.getWindowDrawList();
        ImGuiScreens.chequerboard(drawList, x, y, x + boxWidth, y + boxHeight);
        drawList.addRect(x, y, x + boxWidth, y + boxHeight, ImGui.getColorU32(ImGuiCol.Border));

        // Advances the layout by the box, so whatever follows lands under it.
        ImGui.dummy(boxWidth, boxHeight);

        TextureHandle handle = preview(image);
        if (handle == null) {
            centredText(drawList, x, y, boxWidth, boxHeight, "Loading...",
                    ImGui.getColorU32(ImGuiCol.TextDisabled));
            return;
        }

        float pad = Math.max(2.0f, unit() * 0.4f);
        float scale = ImGuiScreens.fitScale(image.width(), image.height(),
                boxWidth - pad * 2.0f, boxHeight - pad * 2.0f);
        float drawWidth = Math.max(1.0f, image.width() * scale);
        float drawHeight = Math.max(1.0f, image.height() * scale);
        float left = x + (boxWidth - drawWidth) * 0.5f;
        float top = y + (boxHeight - drawHeight) * 0.5f;
        ImGuiScreens.drawImage(drawList, handle, left, top, left + drawWidth, top + drawHeight);
    }

    private void choose(RepoImage image) {
        if (image == null) {
            return;
        }
        selected = image;

        if (pickerActive) {
            // Taken before closing, and cleared, so a listener cannot fire twice if
            // the caller reopens the picker from inside it.
            Consumer<RepoImage> listener = pickerListener;
            pickerListener = null;
            ImGui.closeCurrentPopup();
            if (listener != null) {
                listener.accept(image);
            }
            return;
        }

        if (chooseListener != null) {
            chooseListener.accept(image);
        }
    }

    /**
     * The image's texture if it is already resident, otherwise null and a request
     * on its way.
     *
     * <p>{@code peek} never blocks and {@code request} decodes on a worker, so this
     * is the only contact the render thread has with an image file: none. Requests
     * are made for drawn cells only, which after culling is a screenful rather than
     * the whole repository.
     */
    private TextureHandle thumbnail(RepoImage image) {
        TextureHandle handle = services.thumbnails.peek(image).orElse(null);
        if (handle != null) {
            return handle;
        }
        askFor(image, "", () -> services.thumbnails.request(image));
        return null;
    }

    /**
     * The sharper texture for the detail pane, falling back to the grid thumbnail
     * while it decodes.
     *
     * <p>Showing the small one meanwhile rather than a placeholder means the pane
     * fills instantly and then sharpens, instead of sitting empty for a moment
     * every time the selection changes.
     */
    private TextureHandle preview(RepoImage image) {
        TextureHandle sharp = services.thumbnails.peekPreview(image).orElse(null);
        if (sharp != null) {
            return sharp;
        }
        askFor(image, "preview:", () -> services.thumbnails.requestPreview(image));
        return services.thumbnails.peek(image).orElse(null);
    }

    /**
     * Starts one decode per image per tier, on the worker pool.
     *
     * <p>The in-flight set is what stops a cell asking again on every frame while
     * its first request is still running, which at sixty frames a second would
     * queue thousands of decodes for one screenful.
     */
    private void askFor(RepoImage image, String tier, Supplier<CompletableFuture<TextureHandle>> start) {
        String key = tier + image.path();
        if (failed.contains(key) || !requested.add(key)) {
            return;
        }

        start.get().whenComplete((ready, error) ->
                Minecraft.getInstance().execute(() -> {
                    requested.remove(key);
                    if (error != null) {
                        failed.add(key);
                    }
                }));
    }

    private void centredText(ImDrawList drawList, float x, float y,
            float width, float height, String text, int colour) {
        ImGui.calcTextSize(measurement, text);
        drawList.addText(x + (width - measurement.x) * 0.5f, y + (height - measurement.y) * 0.5f, colour, text);
    }

    private void measureText() {
        ImGui.calcTextSize(measurement, "n");
        characterWidth = Math.max(1.0f, measurement.x);
    }

    /** The one metric everything else is sized from. */
    private static float unit() {
        return Math.max(8.0f, ImGui.getTextLineHeight());
    }
}
