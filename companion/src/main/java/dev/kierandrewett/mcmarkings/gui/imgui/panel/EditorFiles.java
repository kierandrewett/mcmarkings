package dev.kierandrewett.mcmarkings.gui.imgui.panel;

import dev.kierandrewett.mcmarkings.CompanionServices;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.command.Command;
import dev.kierandrewett.mcmarkings.command.CommandRegistry;
import dev.kierandrewett.mcmarkings.command.Shortcut;
import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.doc.Document;
import dev.kierandrewett.mcmarkings.doc.DocumentJson;
import dev.kierandrewett.mcmarkings.doc.DocumentRenderer;
import dev.kierandrewett.mcmarkings.doc.History;
import dev.kierandrewett.mcmarkings.doc.RecoveryStore;
import dev.kierandrewett.mcmarkings.doc.RepositoryImages;
import dev.kierandrewett.mcmarkings.doc.TemplateStore;
import dev.kierandrewett.mcmarkings.gui.imgui.ImGuiScreens;
import dev.kierandrewett.mcmarkings.gui.imgui.PublishFlow;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

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

    /**
     * Written from a worker and read every frame, hence volatile. The list itself is
     * replaced wholesale rather than mutated, so readers never see it half built.
     */
    private volatile List<TemplateStore.Entry> templates = List.of();

    private volatile boolean listing;

    private volatile String listingProblem;

    /**
     * True from the moment a file operation starts until it finishes.
     *
     * <p>Only ever written on the client thread, which is why it is not volatile: the
     * workers hop back before touching it.
     */
    private boolean busy;

    /** The document as it was last written, for telling saved from unsaved. */
    private Document saved;

    private boolean openSavePopup;

    private boolean openTemplatePopup;

    /**
     * Work left behind by a session that did not come back, once the check for it
     * has finished. Read every frame, set from a worker.
     */
    private volatile RecoveryStore.Recovered offered;

    private boolean recoveryAnswered;

    /** True once the check has finished, whether or not it found anything. */
    private volatile boolean recoveryChecked;

    public EditorFiles(CompanionServices services, History history) {
        this.services = services;
        this.history = history;
        this.publish = new PublishFlow(services, status);
        this.saved = history.current();

        // Reading the snapshot is file IO, and it happens while the first frame of
        // the editor is being drawn, so it cannot be done inline.
        Thread.ofVirtual().name("mcmarkings-editor-recovery").start(() -> {
            try {
                offered = services.recovery.pending().orElse(null);
            } catch (RuntimeException failure) {
                McMarkingsCompanion.LOGGER.warn("[mcmarkings] could not read the recovery snapshot", failure);
            } finally {
                recoveryChecked = true;
            }
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
        return !recoveryChecked || (offered != null && !recoveryAnswered);
    }

    public ImGuiScreens.Status status() {
        return status;
    }

    /** True when the document has changed since it was last written. */
    public boolean hasUnsavedChanges() {
        return !history.current().equals(saved);
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

        commands.register(Command.of("editor.file.publish", "Place as a map").category("File")
                .hint("Render at full size, commit it, and run the ImageFrame command")
                .enabledWhen(() -> canWrite() && !history.current().layers().isEmpty())
                .does(this::placeAsMap));
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

        ImGui.textColored(0.95f, 0.78f, 0.35f, 1.0f,
                "Unsaved work from a previous session: \"" + recovered.document().name() + "\", "
                        + recovered.document().layers().size() + " layer(s).");
        ImGui.sameLine();
        if (ImGui.button("Restore##recovery")) {
            recoveryAnswered = true;
            history.push(recovered.document(), "Restore recovered work", null);
            history.endGesture();
            status.good("Restored \"" + recovered.document().name() + "\". Save it to keep it.");
        }
        ImGui.sameLine();
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
        ImGui.setNextItemWidth(ImGui.getFontSize() * 18.0f);
        boolean submitted = ImGui.inputText("##editor-save-name", nameBuffer);
        ImGui.textDisabled("Reopening it later uses this name, so make it one you will recognise.");

        String name = nameBuffer.get().trim();
        boolean valid = !name.isEmpty();
        if (!valid) {
            ImGui.textColored(0.95f, 0.45f, 0.45f, 1.0f, "It needs a name.");
        } else if (existingTemplate(name)) {
            ImGui.textColored(0.95f, 0.78f, 0.35f, 1.0f, "This replaces the template already called that.");
        }

        ImGui.separator();
        if ((ImGui.button("Save##editor-save-confirm") || submitted) && valid) {
            ImGui.closeCurrentPopup();
            saveAs(name);
        }
        ImGui.sameLine();
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
            ImGui.textColored(0.95f, 0.45f, 0.45f, 1.0f,
                    "Could not read the templates: " + ImGuiScreens.truncate(listingProblem, 80));
        } else if (templates.isEmpty()) {
            ImGui.text("Nothing saved yet.");
            ImGui.textDisabled("Anything you save lands in " + TemplateStore.DIRECTORY
                    + "/ and shows up here, in this repository and any clone of it.");
        } else {
            drawTemplateList();
        }

        if (hasUnsavedChanges() && !templates.isEmpty()) {
            ImGui.separator();
            ImGui.textColored(0.95f, 0.78f, 0.35f, 1.0f,
                    "Opening one leaves the current document undoable, not lost.");
        }

        ImGui.separator();
        if (ImGui.button("Cancel##editor-open-cancel")) {
            ImGui.closeCurrentPopup();
        }
        ImGui.endPopup();
    }

    private void drawTemplateList() {
        float rowHeight = ImGui.getFrameHeightWithSpacing();
        float height = Math.min(rowHeight * 10.0f, rowHeight * Math.max(1, templates.size()));

        if (ImGui.beginChild("##editor-open-list", ImGui.getFontSize() * 22.0f, height, true)) {
            for (TemplateStore.Entry entry : templates) {
                if (ImGui.selectable(entry.name() + "##template-" + entry.file().getFileName())) {
                    ImGui.closeCurrentPopup();
                    open(entry);
                }
            }
        }
        ImGui.endChild();
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
        saved = blank;
        status.info("New document.");
    }

    private void beginOpen() {
        openTemplatePopup = true;
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
                Document document = store.load(entry);
                Minecraft.getInstance().execute(() -> onOpened(document));
            } catch (IOException | RuntimeException failure) {
                report("Could not open " + entry.name(), failure);
            }
        });
    }

    private void onOpened(Document document) {
        busy = false;

        // Pushed, so opening the wrong thing by mistake does not cost whatever was on
        // the canvas. It is a big undo step, but a recoverable one.
        history.push(document, "Open " + document.name(), null);
        history.endGesture();
        saved = document;
        status.good("Opened " + document.name() + ".");
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
        Document document = withName(history.current(), name);
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
        saved = document;
        templates = List.of();

        // The snapshot only ever describes work that would otherwise be gone, so a
        // successful save is exactly when it stops being true. Editing again starts
        // it over on its own.
        services.recovery.clear();

        status.good("Saved to " + TemplateStore.DIRECTORY + "/" + file.getFileName() + ".");
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

    private boolean existingTemplate(String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        return templates.stream().anyMatch(entry -> entry.name().toLowerCase(Locale.ROOT).equals(wanted));
    }

    private static Document withName(Document document, String name) {
        return new Document(name, document.grid(), document.pixelsPerFrame(),
                document.background(), document.layers());
    }
}
