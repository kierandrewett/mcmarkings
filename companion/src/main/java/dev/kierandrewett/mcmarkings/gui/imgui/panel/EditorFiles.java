package dev.kierandrewett.mcmarkings.gui.imgui.panel;

import dev.kierandrewett.mcmarkings.CompanionServices;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.command.Command;
import dev.kierandrewett.mcmarkings.command.CommandRegistry;
import dev.kierandrewett.mcmarkings.command.Shortcut;
import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.core.RelativeTime;
import dev.kierandrewett.mcmarkings.doc.BuilderLayout;
import dev.kierandrewett.mcmarkings.doc.Document;
import dev.kierandrewett.mcmarkings.doc.DocumentJson;
import dev.kierandrewett.mcmarkings.doc.DocumentRenderer;
import dev.kierandrewett.mcmarkings.doc.History;
import dev.kierandrewett.mcmarkings.doc.RecoveryStore;
import dev.kierandrewett.mcmarkings.doc.RepositoryImages;
import dev.kierandrewett.mcmarkings.doc.TemplateStore;
import dev.kierandrewett.mcmarkings.gui.imgui.ImGuiScreens;
import dev.kierandrewett.mcmarkings.repo.RepoScanner;
import dev.kierandrewett.mcmarkings.imageframe.ImageFrameCommands;
import dev.kierandrewett.mcmarkings.gui.imgui.Notice;
import dev.kierandrewett.mcmarkings.gui.imgui.PublishFlow;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Keeping and placing what the editor makes.
 *
 * <p>Split out of {@link EditorPanel} because the panel is already the largest file
 * in the mod and none of this is about drawing a canvas. It owns the file commands,
 * the two modals they need, and the publish flow, and it reports through one status
 * line that the panel draws.
 *
 * <p>The whole point is that composing something is not throwaway. Until this
 * existed the editor was a sketchpad: you could build a sign and then had no way to
 * keep it, reopen it, or put it on a wall. Everything here is about closing that
 * loop, so the answer to "what happens to this when I press Escape" is never
 * "it is gone".
 *
 * <p>Every file operation runs on a virtual thread. Saving walks and writes the
 * templates folder, publishing renders at full size and then talks to git, and both
 * are far too slow to do between two frames.
 */
public final class EditorFiles {

    private static final int NAME_BUFFER = 128;

    /** Below this many templates, a search box is furniture rather than help. */
    private static final int SEARCH_THRESHOLD = 8;

    private static final int KEY_N = 'N';

    private static final int KEY_O = 'O';

    private static final int KEY_S = 'S';

    /** What a document is called before anyone has named it. */
    static final String UNTITLED = "untitled";

    static final GridSize DEFAULT_GRID = new GridSize(2, 1);

    static final int DEFAULT_PIXELS_PER_FRAME = 256;

    private final CompanionServices services;

    private final History history;

    private final ImGuiScreens.Status status = new ImGuiScreens.Status();

    private final PublishFlow publish;

    private final ImString nameBuffer = new ImString("", NAME_BUFFER);

    private final ImString templateQuery = new ImString("", NAME_BUFFER);

    /**
     * Written from a worker and read every frame, hence volatile. The list itself is
     * replaced wholesale rather than mutated, so readers never see it half built.
     */
    private volatile List<TemplateStore.Entry> templates = List.of();

    private volatile boolean listing;

    private volatile String listingProblem;

    /**
     * Compositions left behind by the old builder, found beside the images it
     * published. Listed alongside templates because from where someone is sitting
     * they are the same thing: work they made earlier and want back.
     */
    private volatile List<Path> layouts = List.of();

    /**
     * True from the moment a file operation starts until it finishes.
     *
     * <p>Only ever written on the client thread, which is why it is not volatile: the
     * workers hop back before touching it.
     */
    private boolean busy;

    /** What "now" is for this frame. Zero until the first draw. */
    private long drawnAtMillis;

    /** Everything behind the last shortened status message. */
    private List<String> statusDetail = List.of();

    /** The status message the detail above belongs to. */
    private String statusDetailFor = "";

    /** Which template is asking to be deleted, so the confirm is inline per row. */
    private String confirmingDelete = "";

    private boolean openSavePopup;

    private boolean openTemplatePopup;

    /**
     * Work left behind by a session that did not come back, once the check for it
     * has finished. Read every frame, set from a worker.
     */
    private volatile RecoveryStore.Recovered offered;

    /** The canvas as it was when the offer appeared, so an edit releases the hold. */
    private volatile Document documentWhenOffered;

    private boolean recoveryAnswered;

    /** True once the check has finished, whether or not it found anything. */
    private volatile boolean recoveryChecked;

    public EditorFiles(CompanionServices services, History history) {
        this.services = services;
        this.history = history;
        this.publish = new PublishFlow(services, status);

        // Reading the snapshot is file IO, and it happens while the first frame of
        // the editor is being drawn, so it cannot be done inline.
        Thread.ofVirtual().name("mcmarkings-editor-recovery").start(() -> {
            RecoveryStore.Recovered found = null;
            try {
                found = services.recovery.pending().orElse(null);
            } catch (RuntimeException failure) {
                McMarkingsCompanion.LOGGER.warn("[mcmarkings] could not read the recovery snapshot", failure);
            }

            // All three set together, on the client thread. Marking the check finished
            // on the worker and letting the offer land later leaves a window where the
            // hold is already released and the offer does not exist yet, which is the
            // gap the hold was added to close.
            RecoveryStore.Recovered result = found;
            Minecraft.getInstance().execute(() -> {
                documentWhenOffered = history.current();
                offered = result;
                recoveryChecked = true;
            });
        });
    }

    /**
     * True while recovered work is still waiting to be accepted or discarded.
     *
     * <p>The editor stops taking snapshots while this holds. Without it, opening the
     * editor after a crash immediately records the empty canvas over the top of the
     * snapshot, and fifteen seconds later the recovered work is gone for good. The
     * window is small and the consequence is total, which is the worst combination.
     *
     * <p>It also covers the moment before the check has finished, since a snapshot
     * nobody has read yet is exactly as easy to destroy as one being offered.
     */
    public boolean holdsRecovery() {
        if (!recoveryChecked) {
            return true;
        }
        if (offered == null || recoveryAnswered) {
            return false;
        }
        // Only while the canvas is still as it was when the offer appeared. Holding
        // it open regardless was a bug of mine: someone who does not answer the
        // banner and simply starts composing would have had nothing snapshotted for
        // the whole session, which is exactly the protection they thought they had.
        //
        // Once there is live work, that is what needs protecting. The offered
        // document is still restorable this session, because it is held in memory
        // rather than read back from the file each time.
        return history.current().equals(documentWhenOffered);
    }

    public ImGuiScreens.Status status() {
        return status;
    }

    /**
     * The full text behind a status line that had to be shortened, for a tooltip.
     *
     * <p>Tied to the message it belongs to rather than cleared by hand at every
     * place that sets a new one. Detail outliving its message is worse than none: it
     * would hang a list of missing layers off a later "Saved" and read as that save
     * having gone wrong.
     */
    public List<String> statusDetail() {
        return status.message().equals(statusDetailFor) ? statusDetail : List.of();
    }

    /** True when the document has changed since it was last written. */
    public boolean hasUnsavedChanges() {
        return services.hasUnsavedEdits();
    }

    public boolean busy() {
        return busy || publish.running();
    }

    // -----------------------------------------------------------------------
    // Commands
    // -----------------------------------------------------------------------

    public void registerCommands(CommandRegistry commands) {
        commands.register(Command.of("editor.file.new", "New document").category("File")
                .hint("Start again with an empty canvas")
                .shortcut(Shortcut.control(KEY_N))
                .enabledWhen(() -> !busy())
                .does(this::startNew));

        commands.register(Command.of("editor.file.open", "Open template").category("File")
                .hint("Reopen something saved in this repository")
                .shortcut(Shortcut.control(KEY_O))
                .enabledWhen(this::repositoryReady)
                .does(this::beginOpen));

        commands.register(Command.of("editor.file.save", "Save").category("File")
                .hint("Write this document into the repository's templates folder")
                .shortcut(Shortcut.control(KEY_S))
                .enabledWhen(this::canWrite)
                .does(this::save));

        commands.register(Command.of("editor.file.saveAs", "Save as").category("File")
                .hint("Save under a different name, keeping the original")
                .shortcut(Shortcut.controlShift(KEY_S))
                .enabledWhen(this::canWrite)
                .does(this::promptForName));

        // Every other tab that can put something on a wall offers this, and the one
        // where the thing was made did not. Placing a sign and then having to go to
        // another tab to get the frames to hang it on is a gap you notice every time.
        commands.register(Command.of("editor.file.frames", "Get frames").category("File")
                .hint(() -> "Ask for the " + history.current().grid().frameCount()
                        + " invisible frames this document needs")
                .does(this::requestFrames));

        commands.register(Command.of("editor.file.publish", "Place as a map").category("File")
                // Says push, because it does. A plain git push sends the whole branch,
                // so placing one sign also sends every other local commit, and that is
                // not something to discover afterwards.
                //
                // And says why it is unavailable when it is. This is the most
                // consequential button in the mod, so "greyed out for some reason" is
                // the worst thing it could tell someone.
                .hint(this::describePublish)
                .enabledWhen(() -> canWrite() && !history.current().layers().isEmpty())
                .does(this::placeAsMap));
    }

    /** What placing would do, or why it cannot right now. */
    private String describePublish() {
        if (!services.hasRepositories()) {
            return "No repository set up, so there is nowhere to write the image.";
        }
        if (services.isLoading()) {
            return "Still opening the repository.";
        }
        if (busy()) {
            return "Waiting for the last one to finish.";
        }
        if (history.current().layers().isEmpty()) {
            return "The canvas is empty, so there is nothing to place.";
        }
        // Named plainly when there is something to name. "Pushes the branch" is
        // accurate and abstract; "you also have local commits it will send" is the
        // sentence that stops someone pushing a fortnight of unrelated work by
        // pressing a button labelled place a sign.
        return "Renders at full size, commits it, pushes the branch, "
                + "then runs the ImageFrame command"
                + services.pushState().note();
    }

    private boolean repositoryReady() {
        return services.hasRepositories() && !services.isLoading();
    }

    private boolean canWrite() {
        return repositoryReady() && !busy();
    }

    // -----------------------------------------------------------------------
    // Drawing
    // -----------------------------------------------------------------------

    /**
     * The recovery offer, drawn above the toolbar.
     *
     * <p>Deliberately a bar in the editor rather than a modal on open. A dialog in
     * front of an empty canvas before you have done anything is startling, and being
     * forced to decide about work you had forgotten about is worse than being asked
     * once, quietly, with the option to carry on ignoring it.
     */
    public void drawRecoveryOffer() {
        RecoveryStore.Recovered recovered = offered;
        if (recovered == null || recoveryAnswered) {
            return;
        }

        // How old it is decides whether it is worth taking. Work from ten minutes ago
        // is almost certainly wanted; work from three weeks ago is almost certainly
        // something already saved and forgotten about.
        Notice.warning("Unsaved work from " + RelativeTime.describe(recovered.savedAtMillis(), nowMillis())
                + ": \"" + recovered.document().name() + "\", "
                + recovered.document().layers().size() + " layer(s).");
        // Which repository it came from was recorded from the beginning and never
        // read. It matters: restoring work made against another repository resolves
        // every image layer against the wrong root, so the document comes back with
        // its pictures missing and no explanation of why.
        String from = recovered.repositoryId();
        boolean elsewhere = !from.isBlank() && !from.equals(services.activeRepositoryId());
        String name = elsewhere
                ? services.byId(from).map(workspace -> workspace.entry().displayName()).orElse("")
                : "";

        if (elsewhere) {
            ImGui.sameLine();
            ImGui.textDisabled(name.isEmpty()
                    ? "from a repository that is no longer set up"
                    : "from " + name);
        }

        ImGui.sameLine();
        String restore = elsewhere && !name.isEmpty()
                ? "Restore and switch to " + name + "##recovery"
                : "Restore##recovery";
        if (ImGui.button(restore)) {
            recoveryAnswered = true;

            // Switched before the document lands, so the first render already resolves
            // against the right repository rather than flashing a screen of missing
            // images on the way through.
            if (elsewhere && !name.isEmpty()) {
                services.setActive(from);
            }

            history.push(recovered.document(), "Restore recovered work", null);
            history.endGesture();
            services.requestEditorFit();

            // Cleared because it is no longer lost work: it is the document on the
            // canvas. Leaving it would offer to restore it again the next time the
            // window is opened, which reads as the restore not having worked. The
            // editor starts snapshotting it again within seconds, so nothing is at
            // risk in the gap.
            services.recovery.clear();

            if (elsewhere && name.isEmpty()) {
                status.bad("Restored \"" + recovered.document().name()
                        + "\", but the repository it was made in is not set up here, "
                        + "so its images will not resolve.");
            } else {
                status.good("Restored \"" + recovered.document().name() + "\". Save it to keep it.");
            }
        }
        ImGuiScreens.flowTo("Discard##recovery");
        if (ImGui.button("Discard##recovery")) {
            recoveryAnswered = true;
            services.recovery.clear();
            status.info("Discarded the recovered work.");
        }
        ImGui.separator();
    }

    /**
     * The modals. Called unconditionally from the panel's {@code finally}, because an
     * ImGui popup closes the moment its owner stops submitting it.
     */
    public void drawPopups() {
        // Read once for the frame, so every row agrees on what "now" is and nothing
        // asks the system for the time once per template.
        drawnAtMillis = System.currentTimeMillis();

        drawSavePopup();
        drawTemplatePopup();
    }

    private void drawSavePopup() {
        if (openSavePopup) {
            ImGui.openPopup("Save as###editor-save");
            openSavePopup = false;
        }
        if (!ImGui.beginPopupModal("Save as###editor-save", ImGuiWindowFlags.AlwaysAutoResize)) {
            return;
        }

        ImGui.text("Save into " + TemplateStore.DIRECTORY + "/ in this repository.");
        ImGui.setNextItemWidth(ImGuiScreens.fieldWidth(18.0f));
        boolean submitted = ImGui.inputText("##editor-save-name", nameBuffer);
        ImGui.textDisabled("Reopening it later uses this name, so make it one you will recognise.");

        String name = nameBuffer.get().trim();
        boolean valid = !name.isEmpty();
        if (!valid) {
            Notice.error("It needs a name.");
        } else if (existingTemplate(name)) {
            Notice.warning("This replaces the template already called that.");
        }

        ImGui.separator();
        if ((ImGui.button("Save##editor-save-confirm") || submitted) && valid) {
            ImGui.closeCurrentPopup();
            saveAs(name);
        }
        ImGuiScreens.flowTo("Cancel##editor-save-cancel");
        if (ImGui.button("Cancel##editor-save-cancel")) {
            ImGui.closeCurrentPopup();
        }
        ImGui.endPopup();
    }

    private void drawTemplatePopup() {
        if (openTemplatePopup) {
            ImGui.openPopup("Open template###editor-open");
            openTemplatePopup = false;
        }
        if (!ImGui.beginPopupModal("Open template###editor-open", ImGuiWindowFlags.AlwaysAutoResize)) {
            return;
        }

        if (listing) {
            ImGui.text("Reading " + TemplateStore.DIRECTORY + "/...");
        } else if (listingProblem != null) {
            Notice.error(
                    "Could not read the templates: " + ImGuiScreens.truncate(listingProblem, 80));
        } else if (templates.isEmpty()) {
            ImGui.text("Nothing saved yet.");
            ImGui.textDisabled("Anything you save lands in " + TemplateStore.DIRECTORY
                    + "/ and shows up here, in this repository and any clone of it.");
        } else {
            drawTemplateList();
        }

        drawLayoutList();

        if (hasUnsavedChanges() && !templates.isEmpty()) {
            ImGui.separator();
            Notice.warning(
                    "Opening one leaves the current document undoable, not lost.");
        }

        ImGui.separator();
        if (ImGui.button("Cancel##editor-open-cancel")) {
            ImGui.closeCurrentPopup();
        }
        ImGui.endPopup();
    }

    private void drawTemplateList() {
        List<TemplateStore.Entry> found = matchingTemplates();

        // Only once there are enough to be worth searching. A search box above four
        // items is furniture.
        if (templates.size() >= SEARCH_THRESHOLD) {
            ImGui.setNextItemWidth(ImGuiScreens.fieldWidth(22.0f));
            ImGui.inputTextWithHint("##editor-open-query", "Search templates", templateQuery);
        }

        float rowHeight = ImGui.getFrameHeightWithSpacing();
        float height = Math.min(rowHeight * 10.0f, rowHeight * Math.max(1, found.size()));

        if (ImGui.beginChild("##editor-open-list",
                ImGuiScreens.withinWindow(ImGui.getFontSize() * 22.0f, 0.8f, false), height, true)) {
            if (found.isEmpty()) {
                ImGui.textDisabled("Nothing matches that.");
            }
            for (TemplateStore.Entry entry : found) {
                drawTemplateRow(entry);
            }
        }
        ImGui.endChild();
    }

    /**
     * One template, with a way to get rid of it.
     *
     * <p>Saving has always been possible and deleting never was, so the list only ever
     * grew. For a tool meant for long sessions that is the wrong direction: every
     * experiment stays in the way of the things worth keeping.
     *
     * <p>The confirm says it removes the file, because unlike forgetting a placed map
     * this one genuinely deletes something. It is a working tree change like any
     * other, so it is not pushed anywhere and git will show it.
     */
    private void drawTemplateRow(TemplateStore.Entry entry) {
        ImGui.pushID(entry.file().toString());

        if (confirmingDelete.equals(entry.file().toString())) {
            Notice.warning("Delete " + entry.name() + "?");
            ImGuiScreens.flowTo("Delete");
            if (ImGui.button("Delete")) {
                confirmingDelete = "";
                delete(entry);
            }
            ImGuiScreens.flowTo("Keep");
            if (ImGui.button("Keep")) {
                confirmingDelete = "";
            }
            ImGui.textDisabled("Removes the file from " + TemplateStore.DIRECTORY + "/.");
            ImGui.popID();
            return;
        }

        if (ImGui.selectable(entry.name() + "##open")) {
            ImGui.closeCurrentPopup();
            open(entry);
        }
        if (entry.savedAtMillis() > 0) {
            // Usually the one you want is the one you were last working on.
            ImGui.sameLine();
            ImGui.textDisabled(RelativeTime.describe(entry.savedAtMillis(), nowMillis()));
        }
        ImGui.sameLine();
        if (ImGui.smallButton("x")) {
            confirmingDelete = entry.file().toString();
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Delete this template");
        }

        ImGui.popID();
    }

    private void delete(TemplateStore.Entry entry) {
        busy = true;
        status.info("Deleting " + entry.name() + "...");

        TemplateStore store = services.current().templates();
        Thread.ofVirtual().name("mcmarkings-editor-delete").start(() -> {
            try {
                store.delete(entry);
                Minecraft.getInstance().execute(() -> {
                    busy = false;
                    status.good("Deleted " + entry.name() + ".");
                    // Straight away, so the row goes rather than lingering until the
                    // popup is next opened.
                    refreshTemplates();
                });
            } catch (IOException | RuntimeException failure) {
                report("Could not delete " + entry.name(), failure);
            }
        });
    }

    private List<TemplateStore.Entry> matchingTemplates() {
        String text = templateQuery.get().trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return templates;
        }
        return templates.stream()
                .filter(entry -> entry.name().toLowerCase(Locale.ROOT).contains(text))
                .toList();
    }

    private void drawLayoutList() {
        List<Path> found = layouts;
        if (found.isEmpty()) {
            return;
        }

        ImGui.separator();
        ImGui.textDisabled("Alongside published signs");
        for (Path file : found) {
            String name = nameOf(file);
            if (ImGui.selectable(name + "##layout-" + file.getFileName())) {
                ImGui.closeCurrentPopup();
                openLayout(file, name);
            }
        }
    }

    /**
     * Finds the documents saved beside published images.
     *
     * <p>Two different things live here under the same extension. Publishing from the
     * editor writes the document next to the PNG so a sign on a wall can be reopened,
     * and the old builder wrote its own layout format in the same place. Which one a
     * file is gets decided when it is opened, by looking at its shape.
     */
    private List<Path> findBuilderLayouts() {
        Path directory = services.repo().root().resolve(services.config.generatedDirectory);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".layout.json"))
                    .sorted()
                    .toList();
        } catch (IOException | RuntimeException unreadable) {
            return List.of();
        }
    }

    private void openLayout(Path file, String name) {
        busy = true;
        status.info("Opening " + name + "...");

        Path root = services.repo().root();
        int pixelsPerFrame = services.config.exportPixelsPerFrame;

        Thread.ofVirtual().name("mcmarkings-editor-import").start(() -> {
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);

                // Told apart by shape: a builder layout has "items", a document has
                // "layers". Reading a document as a layout finds no items and produces
                // an empty canvas, reported as a successful conversion, which is the
                // worst way to lose a sign.
                if (!BuilderLayout.looksLikeLayout(json)) {
                    DocumentJson.Result result = DocumentJson.readWithReport(json);
                    Minecraft.getInstance().execute(() -> {
                        onOpened(result.document());
                        if (!reportLosses(name, result.warnings())) {
                            status.good("Opened " + name + ". Save it to keep it as a template.");
                        }
                    });
                    return;
                }

                BuilderLayout.Result result = BuilderLayout.read(json, name, pixelsPerFrame,
                        repoPath -> sizeOf(root.resolve(repoPath)));

                Minecraft.getInstance().execute(() -> {
                    onOpened(result.document());

                    // It is now a document rather than a layout, and saving writes a
                    // template. Said plainly, because the file it came from stays where
                    // it is and will keep showing up in this list.
                    if (result.missing().isEmpty()) {
                        status.good("Converted " + name + ". Save it to keep it as a template.");
                    } else {
                        status.bad(result.missing().size()
                                + " image(s) are no longer in the repository and were left out.");
                    }
                });
            } catch (IOException | RuntimeException failure) {
                report("Could not open " + name, failure);
            }
        });
    }

    /**
     * An image's size, without decoding it.
     *
     * <p>Through the scanner's reader, which pulls the dimensions straight out of the
     * PNG header: twenty-four bytes rather than an inflate, with an ImageIO fallback
     * for anything that is not a well-formed IHDR. I wrote a second, slower version
     * of this here first, going through ImageIO for every file. One way to answer a
     * question is worth more than two, and the faster one already existed.
     */
    private static BuilderLayout.Size sizeOf(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        int[] dimensions = RepoScanner.readDimensions(path);
        return dimensions == null ? null : new BuilderLayout.Size(dimensions[0], dimensions[1]);
    }

    /** A layout's display name: the file name without either of its two extensions. */
    private static String nameOf(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".layout.json") ? name.substring(0, name.length() - ".layout.json".length()) : name;
    }

    // -----------------------------------------------------------------------
    // Operations
    // -----------------------------------------------------------------------

    private void startNew() {
        Document blank = Document.blank(UNTITLED, DEFAULT_GRID, DEFAULT_PIXELS_PER_FRAME);

        // Pushed rather than reset, so an accidental Ctrl+N is one Ctrl+Z away. The
        // recovery snapshot is deliberately left alone: it still describes the work
        // that was there a moment ago, which is exactly what it is for.
        history.push(blank, "New document", null);
        history.endGesture();
        services.requestEditorFit();
        services.markSaved(blank);
        status.info("New document.");
    }

    private void beginOpen() {
        openTemplatePopup = true;
        templateQuery.set("");
        confirmingDelete = "";
        listingProblem = null;
        refreshTemplates();
    }

    private void refreshTemplates() {
        if (listing) {
            return;
        }
        listing = true;

        TemplateStore store = services.current().templates();
        Thread.ofVirtual().name("mcmarkings-editor-templates").start(() -> {
            try {
                templates = store.list();
                layouts = findBuilderLayouts();
            } catch (RuntimeException failure) {
                McMarkingsCompanion.LOGGER.error("[mcmarkings] could not list templates", failure);
                templates = List.of();
                listingProblem = String.valueOf(failure);
            } finally {
                listing = false;
            }
        });
    }

    private void open(TemplateStore.Entry entry) {
        busy = true;
        status.info("Opening " + entry.name() + "...");

        Thread.ofVirtual().name("mcmarkings-editor-open").start(() -> {
            try {
                TemplateStore store = services.current().templates();
                DocumentJson.Result result = store.readWithReport(entry.file());
                Minecraft.getInstance().execute(() -> {
                    onOpened(result.document());
                    reportLosses(entry.name(), result.warnings());
                });
            } catch (IOException | RuntimeException failure) {
                report("Could not open " + entry.name(), failure);
            }
        });
    }

    private void onOpened(Document document) {
        busy = false;
        services.requestEditorFit();

        // Pushed, so opening the wrong thing by mistake does not cost whatever was on
        // the canvas. It is a big undo step, but a recoverable one.
        history.push(document, "Open " + document.name(), null);
        history.endGesture();
        services.markSaved(document);
        status.good("Opened " + document.name() + ".");
    }

    /**
     * Says on screen when a document did not come back whole.
     *
     * <p>The reader has always collected this and every caller threw it away, so a
     * template that lost a layer left a line in a log nobody reads and an editor that
     * looked fine. Anything written by a newer build, or with a layer kind this one
     * does not know, comes back quietly short.
     *
     * <p>It matters most at exactly the wrong moment: saving straight afterwards
     * writes the shortened version back over the file, and then it really is gone.
     *
     * @return true when something was said, so the caller does not also report success
     */
    private boolean reportLosses(String name, List<String> warnings) {
        if (warnings.isEmpty()) {
            return false;
        }

        // Kept, not just counted. Which layers a document lost is the whole question,
        // and a number is only enough to worry someone.
        statusDetail = List.copyOf(warnings);

        status.bad(DocumentJson.describeWarnings(name, warnings, 70)
                + ". Saving would make that permanent.");
        statusDetailFor = status.message();
        return true;
    }

    private void save() {
        String name = history.current().name();
        if (name == null || name.isBlank() || name.trim().equalsIgnoreCase(UNTITLED)) {
            promptForName();
            return;
        }
        saveAs(name.trim());
    }

    private void promptForName() {
        // So the popup can say whether this replaces something. Without it the warning
        // only ever appeared if Open happened to have been used first this session,
        // which made a silent overwrite the normal case rather than the rare one.
        refreshTemplates();

        String current = history.current().name();
        nameBuffer.set(current == null || current.isBlank() || current.equalsIgnoreCase(UNTITLED) ? "" : current);
        openSavePopup = true;
    }

    private void saveAs(String name) {
        Document document = history.current().withName(name);
        if (!document.equals(history.current())) {
            history.push(document, "Rename to " + name, null);
            history.endGesture();
        }

        busy = true;
        status.info("Saving " + name + "...");

        TemplateStore store = services.current().templates();
        Thread.ofVirtual().name("mcmarkings-editor-save").start(() -> {
            try {
                Path file = store.save(document);
                Minecraft.getInstance().execute(() -> onSaved(document, file));
            } catch (IOException | RuntimeException failure) {
                report("Could not save " + name, failure);
            }
        });
    }

    private void onSaved(Document document, Path file) {
        busy = false;
        services.markSaved(document);
        templates = List.of();

        // The snapshot only ever describes work that would otherwise be gone, so a
        // successful save is exactly when it stops being true. Editing again starts
        // it over on its own.
        services.recovery.clear();

        status.good("Saved to " + TemplateStore.DIRECTORY + "/" + file.getFileName() + ".");
    }

    /**
     * Asks for the invisible frames this document would need.
     *
     * <p>Counted from the document's own grid rather than from anything published, so
     * it is right before the sign exists as well as after. Changing the frame grid
     * changes the count, which is the whole reason to read it live.
     */
    private void requestFrames() {
        int frames = history.current().grid().frameCount();
        services.commands.send(ImageFrameCommands.giveInvisibleFrames(
                services.config.commandAlias, services.config.glowingFrames, frames));
        status.good("Requested " + frames + " invisible frame(s).");
    }

    private void placeAsMap() {
        Document document = history.current();
        busy = true;
        status.info("Rendering " + document.name() + " at full size...");

        // Read here rather than on the worker: which repository is active is client
        // thread state, and this pins the render to the one it started against.
        Path root = services.repo().root();

        Thread.ofVirtual().name("mcmarkings-editor-place").start(() -> {
            try {
                DocumentRenderer renderer = new DocumentRenderer(services.fonts, services.composer);
                BufferedImage image = renderer.render(document, RepositoryImages.in(services.composer, root));
                String layout = DocumentJson.write(document);

                Minecraft.getInstance().execute(() -> {
                    busy = false;
                    // The layout travels with the PNG, so the sign on the wall can be
                    // reopened and edited rather than only looked at.
                    publish.publish(new PublishFlow.Request(document.name(), image, document.grid(), layout), null);
                });
            } catch (RuntimeException failure) {
                report("Could not render " + document.name(), failure);
            }
        });
    }

    private void report(String what, Throwable failure) {
        McMarkingsCompanion.LOGGER.error("[mcmarkings] " + what, failure);
        Minecraft.getInstance().execute(() -> {
            busy = false;
            String detail = failure.getMessage() == null ? String.valueOf(failure) : failure.getMessage();
            status.bad(what + ": " + ImGuiScreens.truncate(detail, 80));
        });
    }

    /**
     * Whether saving under this name would land on an existing template.
     *
     * <p>Compared by file name, not by what was typed. Names are flattened for the
     * filesystem, so "Give Way" and "give_way" are one file, and comparing the
     * displayed names says they are different right up until the second save
     * replaces the first.
     */
    private boolean existingTemplate(String name) {
        String wanted = TemplateStore.fileNameFor(name);
        return templates.stream()
                .anyMatch(entry -> entry.file().getFileName().toString().equalsIgnoreCase(wanted));
    }

    /** Falls back to a live read for the banner, which is drawn before the popups. */
    private long nowMillis() {
        return drawnAtMillis == 0 ? System.currentTimeMillis() : drawnAtMillis;
    }

}
