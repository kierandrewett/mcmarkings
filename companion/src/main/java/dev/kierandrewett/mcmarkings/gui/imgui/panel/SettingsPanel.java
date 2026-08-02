package dev.kierandrewett.mcmarkings.gui.imgui.panel;

import dev.kierandrewett.mcmarkings.CompanionServices;
import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.gui.imgui.ImGuiScreens;
import dev.kierandrewett.mcmarkings.gui.imgui.Notice;
import imgui.ImGui;
import imgui.type.ImInt;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Settings, in the window rather than instead of it.
 *
 * <p>Grouped by what someone is trying to do rather than by which field lives next
 * to which in the config file. Every control says what it affects, because a setting
 * whose effect you have to discover by trying it is a setting people leave alone.
 *
 * <p>Writing the config file is IO, so it never happens on a keystroke: edits are
 * written when a field is left, on a worker, and repeated saves collapse into one.
 */
public final class SettingsPanel implements Panel {

    private static final int TEXT_BUFFER = 256;

    /** Below this, commands outrun the server's own rate limit and get dropped. */
    private static final double MINIMUM_RATE = 0.2;

    private static final double MAXIMUM_RATE = 20.0;

    private static final int MINIMUM_PIXELS = 32;

    /** One map is 128 pixels, so this is eight maps of detail per frame. */
    private static final int MAXIMUM_PIXELS = 1024;

    private final CompanionServices services;

    private final DirectoryPicker picker = new DirectoryPicker("settings-picker");

    private final ImString alias = new ImString("", TEXT_BUFFER);

    private final ImString generatedDirectory = new ImString("", TEXT_BUFFER);

    private final ImString generatorDirectory = new ImString("", TEXT_BUFFER);

    private final ImString fontPath = new ImString("", TEXT_BUFFER);

    private final ImString ignoredName = new ImString("", TEXT_BUFFER);

    private final float[] rate = new float[1];

    private final ImInt pixelsPerFrame = new ImInt();

    /**
     * Which font folders actually exist, worked out on a worker.
     *
     * <p>Checked once per change rather than per frame. Asking the filesystem while
     * drawing is what froze the game the first time round, and a stale network mount
     * can block for seconds on a question as small as "is this a directory".
     */
    private volatile Set<String> presentFontPaths = Set.of();

    /** True while the reload is asking whether unsaved work should be abandoned. */
    private boolean confirmingReload;

    private String note = "";

    private boolean noteIsWarning;

    public SettingsPanel(CompanionServices services) {
        this.services = services;
        readFromConfig();
        refreshFontPathChecks();
    }

    @Override
    public String title() {
        return "Settings";
    }

    @Override
    public void draw() {
        try {
            drawBody();
        } finally {
            picker.draw();
        }
    }

    private void drawBody() {
        drawPlacingSection();
        ImGui.spacing();
        drawExportSection();
        ImGui.spacing();
        drawFontSection();
        ImGui.spacing();
        drawIgnoredSection();
        ImGui.spacing();
        drawMaintenanceSection();

        if (!note.isEmpty()) {
            ImGui.separator();
            if (noteIsWarning) {
                Notice.warning(note);
            } else {
                ImGui.textDisabled(note);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Sections
    // -----------------------------------------------------------------------

    private void drawPlacingSection() {
        ImGui.textDisabled("PLACING MAPS");
        ImGui.separator();

        ImGui.setNextItemWidth(fieldWidth());
        ImGui.inputText("Command name##settings-alias", alias);
        boolean aliasDone = ImGui.isItemDeactivatedAfterEdit();
        help("The plugin's own command. Change it only if your server renamed ImageFrame.");
        if (aliasDone) {
            String value = alias.get().trim();
            if (value.isEmpty()) {
                warn("The command name cannot be empty, so it has been left as it was.");
                alias.set(services.config.commandAlias);
            } else {
                services.config.commandAlias = value;
                services.saveConfig();
            }
        }

        ImGui.setNextItemWidth(fieldWidth());
        if (ImGui.sliderFloat("Commands per second##settings-rate", rate,
                (float) MINIMUM_RATE, (float) MAXIMUM_RATE, "%.1f")) {
            services.config.commandsPerSecond = clamp(rate[0], MINIMUM_RATE, MAXIMUM_RATE);
        }
        boolean rateDone = ImGui.isItemDeactivatedAfterEdit();
        help("How fast commands are sent. Too fast and the server drops them; "
                + "a large sign is many commands.");
        if (rateDone) {
            // Told to the running sink, not only to the file. Until now the slider
            // saved a number nothing read until the next restart.
            services.commands.setCommandsPerSecond(services.config.commandsPerSecond);
            services.saveConfig();
        }

        if (ImGui.checkbox("Give glowing item frames##settings-glowing", services.config.glowingFrames)) {
            services.config.glowingFrames = !services.config.glowingFrames;
            services.saveConfig();
        }
        help("Glowing frames keep the image bright in the dark. Otherwise it dims like a block.");
    }

    private void drawExportSection() {
        ImGui.textDisabled("EXPORTING");
        ImGui.separator();

        ImGui.setNextItemWidth(fieldWidth());
        ImGui.inputInt("Pixels per frame##settings-pixels", pixelsPerFrame);
        boolean pixelsDone = ImGui.isItemDeactivatedAfterEdit();
        help("Detail per item frame. A map shows " + GridSize.MAP_PIXELS + ", so more than that "
                + "buys a better downsample rather than a sharper sign.");
        if (pixelsDone) {
            int value = (int) clamp(pixelsPerFrame.get(), MINIMUM_PIXELS, MAXIMUM_PIXELS);
            pixelsPerFrame.set(value);
            services.config.exportPixelsPerFrame = value;
            services.saveConfig();
        }

        ImGui.setNextItemWidth(fieldWidth());
        ImGui.inputText("Generated folder##settings-generated", generatedDirectory);
        boolean generatedDone = ImGui.isItemDeactivatedAfterEdit();
        help("Where rendered images are written inside the repository, before being committed.");
        if (generatedDone) {
            services.config.generatedDirectory = generatedDirectory.get().trim();
            services.saveConfig();
        }

        ImGui.setNextItemWidth(fieldWidth());
        ImGui.inputText("Generators folder##settings-generators", generatorDirectory);
        boolean generatorsDone = ImGui.isItemDeactivatedAfterEdit();
        help("Where the mod looks for generator scripts in the repository. "
                + "Changing it reopens every repository.");
        if (generatorsDone) {
            services.config.generatorDirectory = generatorDirectory.get().trim();
            // The script runtime is built with this path when a repository opens, so
            // saving alone would leave the Generate tab reading the old folder.
            persistAndRescan();
        }
    }

    /**
     * Folders the scanner never walks.
     *
     * <p>The last setting in the config with nowhere to change it. It decides what
     * gets scanned, so a repository with a large folder of things that are not signs
     * was slower to open and filled the browser with images nobody wanted, and the
     * only fix was editing the config by hand.
     */
    private void drawIgnoredSection() {
        ImGui.textDisabled("FOLDERS NEVER SCANNED");
        ImGui.separator();

        ImGui.textWrapped("Names skipped anywhere in a repository. Folders starting with a dot "
                + "are always skipped and do not need listing.");

        List<String> ignored = ignoredDirectories();
        for (int index = 0; index < ignored.size(); index++) {
            ImGui.pushID("ignored-" + index);
            if (ImGui.button("Remove")) {
                ignored.remove(index);
                persistAndRescan();
                ImGui.popID();
                break;
            }
            ImGui.sameLine();
            ImGui.text(ignored.get(index));
            ImGui.popID();
        }

        ImGui.setNextItemWidth(fieldWidth());
        boolean submitted = ImGui.inputTextWithHint("##settings-ignored", "node_modules", ignoredName);
        ImGuiScreens.flowTo("Add##settings-ignored-add");
        if (ImGui.button("Add##settings-ignored-add") || submitted) {
            addIgnored(ignoredName.get());
        }
    }

    private void addIgnored(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            warn("Type a folder name.");
            return;
        }

        List<String> ignored = ignoredDirectories();
        if (ignored.contains(trimmed)) {
            warn("That folder is already skipped.");
            return;
        }

        ignored.add(trimmed);
        ignoredName.set("");
        persistAndRescan();
        warn("Skipping " + trimmed + " from now on.");
    }

    /**
     * Saves and reopens every repository.
     *
     * <p>The scanner is built with this list when a repository is opened, so without
     * reopening, a change here does nothing until the next restart and looks broken.
     * It applies to all of them, not just the active one, for the same reason.
     */
    private void persistAndRescan() {
        services.saveConfig();
        services.workspaces().forEach(workspace -> services.reloadAsync(workspace.id()));
    }

    private List<String> ignoredDirectories() {
        if (services.config.ignoredDirectories == null) {
            services.config.ignoredDirectories = new ArrayList<>();
        }
        return services.config.ignoredDirectories;
    }

    private void drawFontSection() {
        ImGui.textDisabled("FONTS");
        ImGui.separator();

        ImGui.textWrapped("Every font in these folders is offered in the editor. "
                + "Your system's own font folders are searched by default.");
        ImGui.textDisabled(services.fonts.count() + " font(s) available right now.");

        List<String> paths = fontPaths();
        for (int index = 0; index < paths.size(); index++) {
            String path = paths.get(index);
            ImGui.pushID("font-path-" + index);

            if (ImGui.button("Remove")) {
                paths.remove(index);
                services.saveConfig();
                refreshFontPathChecks();
                warn("Removed. Font changes take effect after a reload.");
                ImGui.popID();
                break;
            }
            ImGui.sameLine();

            // Whether the folder is actually there is worth showing: a path typed with
            // a typo looks identical to one that works until you go looking for a font.
            boolean exists = presentFontPaths.contains(path);
            if (exists) {
                ImGui.text(ImGuiScreens.truncate(path, 64));
            } else {
                Notice.warning(
                        ImGuiScreens.truncate(path, 64) + "  (nothing there)");
            }

            ImGui.popID();
        }

        ImGui.setNextItemWidth(fieldWidth());
        boolean submitted = ImGui.inputText("##settings-font-path", fontPath);
        ImGuiScreens.flowTo("Add##settings-font-add");
        if (ImGui.button("Add##settings-font-add") || submitted) {
            addFontPath(fontPath.get());
        }
        ImGuiScreens.flowTo("Browse...##settings-font-browse");
        if (ImGui.button("Browse...##settings-font-browse")) {
            picker.open("Choose a folder of fonts", null, directory -> addFontPath(directory.toString()));
        }
    }

    private void drawMaintenanceSection() {
        ImGui.textDisabled("MAINTENANCE");
        ImGui.separator();

        if (confirmingReload) {
            // It says what it costs. Reload throws the services away, and the document
            // in the editor lives in them, so an unsaved composition goes with it.
            // A button that quietly destroys an hour of work is not one anyone should
            // press to pick up a font folder.
            Notice.warningWrapped("The editor has unsaved changes. Reloading starts a new document, "
                    + "and this one would only come back through crash recovery.");
            if (ImGui.button("Reload anyway##settings-reload-confirm")) {
                confirmingReload = false;
                reloadEverything();
            }
            ImGuiScreens.flowTo("Cancel##settings-reload-cancel");
            if (ImGui.button("Cancel##settings-reload-cancel")) {
                confirmingReload = false;
            }
        } else if (ImGui.button("Reload everything##settings-reload")) {
            if (services.hasUnsavedEdits()) {
                confirmingReload = true;
            } else {
                reloadEverything();
            }
        }
        help("Re-reads the config, the repositories and the fonts. Needed after changing font folders.");

        ImGui.textDisabled("Config file: " + ImGuiScreens.truncate(
                String.valueOf(dev.kierandrewett.mcmarkings.config.CompanionConfig.configPath()), 70));
    }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    private void addFontPath(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            warn("Type a folder, or use Browse.");
            return;
        }

        List<String> paths = fontPaths();
        if (paths.contains(trimmed)) {
            warn("That folder is already being searched.");
            return;
        }

        paths.add(trimmed);
        fontPath.set("");
        services.saveConfig();

        // Said plainly rather than hidden: adding a folder that is not there is a
        // typo most of the time, and finding out later means hunting for a font that
        // was never going to appear.
        refreshFontPathChecks();
        warn("Added. Font changes take effect after a reload.");
    }

    /**
     * Throws the services away and builds them again.
     *
     * <p>Deferred rather than done here: this replaces the screen currently being
     * drawn, and swapping it out part way through a frame is how ImGui ends up
     * submitting into a window that no longer exists.
     */
    private void reloadEverything() {
        note("Reloading...");
        Minecraft.getInstance().execute(() -> {
            try {
                // Deliberately the blocking save, not the shared asynchronous one.
                // The services are about to be thrown away, and a save still queued
                // when that happens is a save that never lands.
                services.config.save();

                // Written before the services holding it are discarded, so even if
                // someone reloads over unsaved work it is offered back rather than
                // gone. Without this the last fifteen seconds of it would be.
                services.flushRecoveryNow();

                // Frees the textures and stops the decode pool, both of which belong
                // to the services being discarded.
                McMarkingsCompanion.reset();

                Minecraft.getInstance().setScreen(
                        new dev.kierandrewett.mcmarkings.gui.imgui.ImGuiShell(McMarkingsCompanion.services()));
            } catch (RuntimeException failure) {
                McMarkingsCompanion.LOGGER.error("[mcmarkings] reload failed", failure);
            }
        });
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void readFromConfig() {
        alias.set(services.config.commandAlias);
        generatedDirectory.set(services.config.generatedDirectory);
        generatorDirectory.set(services.config.generatorDirectory);
        rate[0] = (float) services.config.commandsPerSecond;
        pixelsPerFrame.set(services.config.exportPixelsPerFrame);
    }

    private List<String> fontPaths() {
        if (services.config.fontSearchPaths == null) {
            services.config.fontSearchPaths = new ArrayList<>();
        }
        return services.config.fontSearchPaths;
    }

    /** A dim line under the control it explains, indented so it reads as attached to it. */
    private static void help(String text) {
        ImGui.indent();
        ImGui.pushTextWrapPos(ImGui.getContentRegionMaxX());
        ImGui.textDisabled(text);
        ImGui.popTextWrapPos();
        ImGui.unindent();
    }

    private static float fieldWidth() {
        return ImGuiScreens.fieldWidth(16.0f);
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    /**
     * Re-checks which font folders exist, on a worker.
     *
     * <p>Cheap to call: it copies the paths on the client thread so the worker never
     * reads a list that is being edited underneath it.
     */
    private void refreshFontPathChecks() {
        List<String> snapshot = List.copyOf(fontPaths());
        Thread.ofVirtual().name("mcmarkings-font-paths").start(() -> {
            Set<String> present = new HashSet<>();
            for (String path : snapshot) {
                try {
                    if (Files.isDirectory(Path.of(path))) {
                        present.add(path);
                    }
                } catch (RuntimeException unusable) {
                    // Not a usable path, so it is simply not present.
                }
            }
            presentFontPaths = Set.copyOf(present);
        });
    }

    private void note(String text) {
        this.note = text;
        this.noteIsWarning = false;
    }

    private void warn(String text) {
        this.note = text;
        this.noteIsWarning = true;
    }
}
