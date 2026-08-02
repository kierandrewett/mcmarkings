package dev.kierandrewett.mcmarkings.gui.imgui.panel;

import dev.kierandrewett.mcmarkings.CompanionServices;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.Workspace;
import dev.kierandrewett.mcmarkings.config.RepositoryEntry;
import dev.kierandrewett.mcmarkings.gui.imgui.ImGuiScreens;
import dev.kierandrewett.mcmarkings.gui.imgui.Notice;
import dev.kierandrewett.mcmarkings.gui.imgui.Persist;
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

    private final CompanionServices services;

    private final DirectoryPicker picker = new DirectoryPicker("repositories-picker");

    private final ImString renameBuffer = new ImString("", NAME_BUFFER);

    private final Persist persist;

    /** Which row is being renamed, by id. Empty when none is. */
    private String renaming = "";

    /** Which row has asked to be removed, so the confirm is per row rather than modal. */
    private String confirmingRemoval = "";

    public RepositoriesPanel(CompanionServices services) {
        this.services = services;
        this.persist = new Persist("the config", services.config::save);
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

        ImGui.popID();
        ImGui.separator();
    }

    private void drawRenameField(RepositoryEntry entry) {
        ImGui.setNextItemWidth(ImGui.getFontSize() * 16.0f);
        boolean submitted = ImGui.inputText("##rename", renameBuffer);
        ImGui.sameLine();
        if (ImGui.button("Save##rename-save") || submitted) {
            String name = renameBuffer.get().trim();
            if (!name.isEmpty()) {
                services.config.replaceRepository(entry.withName(name));
                persist.request();
            }
            renaming = "";
        }
        ImGui.sameLine();
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

        ImGui.sameLine();
        if (ImGui.button("Rescan")) {
            // Walks the folder, so it is explicitly the async one.
            services.reloadAsync(workspace.id());
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Read the folder again, after adding or changing images outside the game.");
        }

        ImGui.sameLine();
        if (ImGui.button("Moved...")) {
            picker.open("Where is " + entry.displayName() + " now?", entry.root(),
                    directory -> relocate(workspace, directory));
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Point this repository at a new folder, keeping its name.");
        }

        ImGui.sameLine();
        if (confirmingRemoval.equals(workspace.id())) {
            // Inline rather than a modal, and it says what it does not do. Removing a
            // repository from a list sounds like it might delete the folder.
            Notice.warning("Remove from the list?");
            ImGui.sameLine();
            if (ImGui.button("Remove")) {
                confirmingRemoval = "";
                services.removeRepository(workspace.id());
            }
            ImGui.sameLine();
            if (ImGui.button("Keep")) {
                confirmingRemoval = "";
            }
            ImGui.textDisabled("Nothing on disk is touched.");
        } else if (ImGui.button("Remove...")) {
            confirmingRemoval = workspace.id();
            renaming = "";
        }
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
            persist.request();

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
