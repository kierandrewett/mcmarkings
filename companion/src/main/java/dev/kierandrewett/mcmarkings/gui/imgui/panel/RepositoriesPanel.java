package dev.kierandrewett.mcmarkings.gui.imgui.panel;

import dev.kierandrewett.mcmarkings.CompanionServices;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.Workspace;
import dev.kierandrewett.mcmarkings.config.RepositoryEntry;
import dev.kierandrewett.mcmarkings.gui.imgui.ImGuiScreens;
import dev.kierandrewett.mcmarkings.gui.imgui.Notice;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import java.nio.file.Path;
import java.util.List;

/**
 * The repositories you work from.
 *
 * <p>A tab rather than a screen, so setting one up no longer costs sight of
 * everything else. This was the last part of the interface that still threw you out
 * of the window you were in, which is the thing that breaks concentration hardest:
 * you go to add a folder and come back having lost your place.
 *
 * <p>Adding, moving and removing all go through {@link CompanionServices}, which
 * owns the config and does the scanning off the client thread. Nothing here writes
 * config directly except a rename, which is one field and no IO beyond the save.
 */
public final class RepositoriesPanel implements Panel {

    private static final int NAME_BUFFER = 128;

    private static final int URL_BUFFER = 512;

    private final CompanionServices services;

    private final DirectoryPicker picker = new DirectoryPicker("repositories-picker");

    private final ImString renameBuffer = new ImString("", NAME_BUFFER);

    private final ImString slugBuffer = new ImString("", URL_BUFFER);

    private final ImString templateBuffer = new ImString("", URL_BUFFER);

    /** Whose overrides the buffers currently hold, so they are seeded once per row. */
    private String editingUrls = "";

    /** Which row is being renamed, by id. Empty when none is. */
    private String renaming = "";

    /** Which row has asked to be removed, so the confirm is per row rather than modal. */
    private String confirmingRemoval = "";

    public RepositoriesPanel(CompanionServices services) {
        this.services = services;
    }

    @Override
    public String title() {
        return "Repositories";
    }

    @Override
    public void draw() {
        try {
            drawBody();
        } finally {
            // Unconditional: a popup exists only while its owner is still submitting.
            picker.draw();
        }
    }

    private void drawBody() {
        if (services.isLoading()) {
            ImGui.textDisabled("Still opening the repositories...");
            ImGui.separator();
        }

        drawIntro();
        ImGui.separator();

        List<Workspace> workspaces = services.workspaces();
        if (workspaces.isEmpty()) {
            drawEmptyState();
            return;
        }

        for (Workspace workspace : workspaces) {
            drawRow(workspace);
        }

        ImGui.separator();
        drawAddButton();
    }

    private void drawIntro() {
        ImGui.textWrapped("A repository is any folder of images tracked by git. "
                + "The browser reads from the active one, the editor saves templates into it, "
                + "and placing a map commits and pushes there.");
    }

    private void drawEmptyState() {
        // The empty state is the first thing most people ever see here, so it says what
        // to do rather than reporting that a list is empty.
        ImGui.textWrapped("No repositories yet. Pick a folder that git already tracks, "
                + "or one you have cloned, and everything else in the mod becomes available.");
        ImGui.spacing();
        drawAddButton();
    }

    private void drawAddButton() {
        if (ImGui.button("Add a repository##repositories-add")) {
            picker.open("Choose a repository folder", suggestedStart(), this::addRepository);
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Folders already tracked by git are marked in the list.");
        }
    }

    private void drawRow(Workspace workspace) {
        RepositoryEntry entry = services.config.byId(workspace.id()).orElseGet(workspace::entry);
        boolean active = workspace.id().equals(services.activeRepositoryId());

        ImGui.pushID(workspace.id());

        if (ImGui.radioButton("##active", active) && !active) {
            services.setActive(workspace.id());
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(active ? "This is the repository everything reads from."
                    : "Work from this one instead.");
        }

        ImGui.sameLine();
        if (renaming.equals(workspace.id())) {
            drawRenameField(entry);
        } else {
            ImGui.text(entry.displayName());
            if (active) {
                ImGui.sameLine();
                ImGui.textDisabled("(active)");
            }
        }

        ImGui.textDisabled(ImGuiScreens.truncate(entry.path(), 76));

        if (workspace.hasWarning()) {
            // Its own words rather than a generic failure. "not a git repository" tells
            // someone what to do; "could not open" does not.
            Notice.warningWrapped(workspace.warning());
        }

        drawRowActions(workspace, entry);
        drawUrlOverrides(workspace, entry);

        ImGui.popID();
        ImGui.separator();
    }

    private void drawRenameField(RepositoryEntry entry) {
        ImGui.setNextItemWidth(ImGuiScreens.fieldWidth(16.0f));
        boolean submitted = ImGui.inputText("##rename", renameBuffer);
        ImGuiScreens.flowTo("Save##rename-save");
        if (ImGui.button("Save##rename-save") || submitted) {
            String name = renameBuffer.get().trim();
            if (!name.isEmpty()) {
                services.config.replaceRepository(entry.withName(name));
                services.saveConfig();
            }
            renaming = "";
        }
        ImGuiScreens.flowTo("Cancel##rename-cancel");
        if (ImGui.button("Cancel##rename-cancel")) {
            renaming = "";
        }
    }

    private void drawRowActions(Workspace workspace, RepositoryEntry entry) {
        if (ImGui.button("Rename")) {
            renameBuffer.set(entry.displayName());
            renaming = workspace.id();
            confirmingRemoval = "";
        }

        ImGuiScreens.flowTo("Rescan");
        if (ImGui.button("Rescan")) {
            // Walks the folder, so it is explicitly the async one.
            services.reloadAsync(workspace.id());
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Read the folder again, after adding or changing images outside the game.");
        }

        ImGuiScreens.flowTo("Moved...");
        if (ImGui.button("Moved...")) {
            picker.open("Where is " + entry.displayName() + " now?", entry.root(),
                    directory -> relocate(workspace, directory));
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Point this repository at a new folder, keeping its name.");
        }

        ImGuiScreens.flowTo("Remove...");
        if (confirmingRemoval.equals(workspace.id())) {
            // Inline rather than a modal, and it says what it does not do. Removing a
            // repository from a list sounds like it might delete the folder.
            Notice.warning("Remove from the list?");
            ImGuiScreens.flowTo("Remove");
            if (ImGui.button("Remove")) {
                confirmingRemoval = "";
                services.removeRepository(workspace.id());
            }
            ImGuiScreens.flowTo("Keep");
            if (ImGui.button("Keep")) {
                confirmingRemoval = "";
            }
            ImGui.textDisabled("Nothing on disk is touched.");
        } else if (ImGui.button("Remove...")) {
            confirmingRemoval = workspace.id();
            renaming = "";
        }
    }

    /**
     * Where the raw file URLs come from, for repositories the remote cannot describe.
     *
     * <p>The URL resolver has always had these two settings and has always told
     * people to set them when it cannot work a repository out: "set a slug override",
     * "set a raw URL template". Nothing in the interface could, so on a self-hosted
     * forge with an unusual remote the only instruction the mod gives was impossible
     * to follow without hand-editing the config.
     *
     * <p>Folded away, because most repositories never need either and a field nobody
     * should touch is worth hiding rather than explaining twice.
     */
    private void drawUrlOverrides(Workspace workspace, RepositoryEntry entry) {
        if (!ImGui.treeNode("URLs##overrides")) {
            return;
        }

        try {
            ImGui.textWrapped("Only needed when the mod cannot work out where this "
                    + "repository's files are served from. Leave both blank to read it from the remote.");

            if (!editingUrls.equals(workspace.id())) {
                editingUrls = workspace.id();
                slugBuffer.set(entry.slugOverride());
                templateBuffer.set(entry.rawUrlTemplate());
            }

            ImGui.setNextItemWidth(ImGuiScreens.fieldWidth(18.0f));
            ImGui.inputTextWithHint("##slug", "owner/repo", slugBuffer);
            boolean slugDone = ImGui.isItemDeactivatedAfterEdit();
            ImGui.textDisabled("Slug");

            ImGui.setNextItemWidth(-1.0f);
            ImGui.inputTextWithHint("##template", "https://host/{slug}/raw/{commit}/{path}", templateBuffer);
            boolean templateDone = ImGui.isItemDeactivatedAfterEdit();
            ImGui.textDisabled("Raw URL template. {slug} {commit} {path} {host}");

            if (slugDone) {
                apply(workspace, entry.withSlugOverride(slugBuffer.get().trim()));
            }
            if (templateDone) {
                apply(workspace, entry.withRawUrlTemplate(templateBuffer.get().trim()));
            }
        } finally {
            ImGui.treePop();
        }
    }

    /**
     * Saves an override and reopens the repository.
     *
     * <p>The workspace holds the entry it was opened with, so a change to either of
     * these does nothing until it is read again. Reopening is the difference between
     * a setting that works and one that appears not to.
     */
    private void apply(Workspace workspace, RepositoryEntry updated) {
        services.config.replaceRepository(updated);
        services.saveConfig();
        services.reloadAsync(workspace.id());
    }

    private void addRepository(Path directory) {
        try {
            Workspace added = services.addRepository(directory);
            if (services.workspaces().size() == 1) {
                // The first one becomes active on its own. Having added the only
                // repository and still seeing an empty browser makes no sense.
                services.setActive(added.id());
            }
        } catch (RuntimeException failure) {
            McMarkingsCompanion.LOGGER.error("[mcmarkings] could not add " + directory, failure);
        }
    }

    /**
     * Repoints an existing repository at a folder that has moved.
     *
     * <p>The id is derived from the path, so a move is a remove and an add rather
     * than an edit. The name and whether it was active are carried across, because
     * losing either would make a move feel like a loss.
     */
    private void relocate(Workspace workspace, Path directory) {
        RepositoryEntry old = services.config.byId(workspace.id()).orElseGet(workspace::entry);
        boolean wasActive = workspace.id().equals(services.activeRepositoryId());

        try {
            services.removeRepository(old.id());
            Workspace relocated = services.addRepository(directory);
            services.config.byId(relocated.id())
                    .ifPresent(entry -> services.config.replaceRepository(entry.withName(old.displayName())));
            services.saveConfig();

            if (wasActive) {
                services.setActive(relocated.id());
            }
        } catch (RuntimeException failure) {
            McMarkingsCompanion.LOGGER.error("[mcmarkings] could not move " + old.displayName(), failure);
        }
    }

    /**
     * A sensible place to start browsing from.
     *
     * <p>The active repository's parent, since a second repository usually lives
     * beside the first. Falls back to the picker's own default when there is none.
     */
    private Path suggestedStart() {
        return services.active()
                .map(workspace -> workspace.entry().root())
                .map(Path::getParent)
                .orElse(null);
    }
}
