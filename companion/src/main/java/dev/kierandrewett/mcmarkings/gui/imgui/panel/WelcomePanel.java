package dev.kierandrewett.mcmarkings.gui.imgui.panel;

import dev.kierandrewett.mcmarkings.CompanionServices;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.Workspace;
import dev.kierandrewett.mcmarkings.gui.imgui.Notice;
import imgui.ImGui;

import java.nio.file.Path;
import java.util.List;

/**
 * First run, in the same window as everything else.
 *
 * <p>Having nothing set up is where everyone starts, so this explains what the mod
 * wants and gives one obvious button, rather than reporting a missing folder and
 * leaving someone to find a config file.
 *
 * <p>Drawn in place of the tabs rather than as a separate screen. Adding a folder
 * then puts the tabs there on the next frame, with no screen change and nothing
 * flashing: the window you started in is the window you carry on in. The old
 * first-run screen handed you off to a picker, which handed you to a browser, and
 * three different-looking things in a row is a poor first thirty seconds.
 */
public final class WelcomePanel {

    private final CompanionServices services;

    private final DirectoryPicker picker = new DirectoryPicker("welcome-picker");

    /** Set when a chosen folder was not usable, so the reason survives the picker closing. */
    private String problem = "";

    /** Set when a folder was added but is not really a repository yet. */
    private String caution = "";

    public WelcomePanel(CompanionServices services) {
        this.services = services;
    }

    public void draw() {
        try {
            drawBody();
        } finally {
            picker.draw();
        }
    }

    private void drawBody() {
        Notice.heading("Welcome to MCMarkings");
        ImGui.spacing();

        ImGui.textWrapped("Put your own images on walls in game, without typing commands.");
        ImGui.spacing();
        ImGui.textWrapped("Point it at a folder of images and you can search them in game, "
                + "see how many item frames each one needs, compose new ones, and place them "
                + "in a couple of clicks.");

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        Notice.heading("To get started");
        bullet("Choose a folder of PNGs. A clone of a git repository works best, because the "
                + "server fetches the images over the internet rather than from your machine.");
        bullet("Add as many folders as you like, and switch between them whenever you want.");
        bullet("Nothing is written to that folder until you choose to place or save something.");

        ImGui.spacing();
        if (ImGui.button("Choose a folder##welcome-choose")) {
            problem = "";
            caution = "";
            picker.open("Choose a folder of images", null, this::adopt);
        }
        ImGui.sameLine();
        ImGui.textDisabled("Folders already tracked by git are marked in the list.");

        drawNotes();
    }

    private void drawNotes() {
        if (!problem.isEmpty()) {
            ImGui.spacing();
            Notice.errorWrapped(problem);
        }
        if (!caution.isEmpty()) {
            ImGui.spacing();
            Notice.warningWrapped(caution);
        }

        List<String> notes = services.startupNotes();
        if (notes.isEmpty()) {
            return;
        }

        ImGui.spacing();
        ImGui.separator();
        for (String note : notes) {
            Notice.warningWrapped(note);
        }
    }

    /**
     * Takes on a chosen folder.
     *
     * <p>A folder that opened with a warning still counts. Refusing anything that is
     * not already a perfect git repository would be the mod deciding how someone has
     * to organise their files, and the warning belongs in the interface rather than
     * in the way of it.
     *
     * <p>The workspace handed back here is a placeholder: scanning the folder happens
     * on a worker, and until it lands the placeholder carries "Opening..." as its
     * warning. Reading that as a real problem would report one on every single add,
     * and checking the folder here instead would put the scan on the client thread,
     * which is the thing the placeholder exists to avoid. So the real state is picked
     * up once, afterwards, through whenReady.
     */
    private void adopt(Path directory) {
        try {
            Workspace added = services.addRepository(directory);
            if (services.workspaces().size() == 1) {
                services.setActive(added.id());
            }

            String id = added.id();
            services.whenReady(() -> services.byId(id)
                    .filter(Workspace::hasWarning)
                    .ifPresent(workspace -> caution = workspace.warning()));
        } catch (RuntimeException failure) {
            McMarkingsCompanion.LOGGER.error("[mcmarkings] could not add " + directory, failure);
            problem = "That folder could not be opened: " + failure.getMessage();
        }
    }

    private static void bullet(String text) {
        ImGui.bullet();
        ImGui.pushTextWrapPos(ImGui.getContentRegionMaxX());
        ImGui.textWrapped(text);
        ImGui.popTextWrapPos();
    }
}
