package dev.kierandrewett.mcmarkings.gui.imgui.panel;

import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.gui.imgui.ImGuiScreens;
import dev.kierandrewett.mcmarkings.gui.imgui.Notice;
import imgui.ImGui;
import imgui.flag.ImGuiHoveredFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Choosing a folder, without leaving the window you were in.
 *
 * <p>Reusable rather than a screen, because the same job comes up in at least three
 * places: adding a repository, moving one that has been moved on disk, and adding a
 * folder to search for fonts. Each of those used to be its own full screen, which
 * meant losing sight of what you were setting up in order to answer a question about
 * it.
 *
 * <p>Nothing here assumes an operating system. The starting places come from
 * {@code user.home} and the actual filesystem roots, so it works the same on a
 * Windows drive letter, a mac home directory and a Linux mount point. Typing a path
 * is always available as well, which matters for anywhere the browser cannot reach:
 * network shares, hidden folders, and the host filesystem when the game is running
 * inside a sandbox.
 *
 * <p>Every listing happens on a worker. A directory on a slow disk, a stale network
 * mount or a folder with thousands of entries would otherwise freeze the game
 * mid-frame, and the whole point of this being a modal is that the rest of the
 * interface stays alive behind it.
 */
public final class DirectoryPicker {

    private static final int PATH_BUFFER = 512;

    /**
     * How many subfolders are read from one directory.
     *
     * <p>There are places on a real machine with tens of thousands of entries in
     * them: a package store, a cache, a node_modules. Reading all of them means a
     * stat per entry and two more to see whether it is a repository, so a folder
     * like that would take a minute to open and then submit thirty thousand rows on
     * every frame. Nobody is finding their project by scrolling that anyway, and the
     * path box above it goes anywhere directly.
     */
    private static final int MAX_ENTRIES = 1000;

    /** A screenful. Beyond this, filtering is the only way anyone is finding it. */
    private static final int MAX_SHOWN = 100;

    /**
     * What one directory listing came back with. Replaced wholesale, never mutated.
     *
     * <p>{@code truncated} is whether the folder had more than was read, so the
     * interface can say so rather than quietly showing part of a folder as if it
     * were all of it.
     */
    private record Listing(Path directory, List<Entry> entries, String problem, boolean truncated) {
    }

    /**
     * One row. Whether it is a repository is worked out during the listing, because
     * asking per frame would be file IO on the render thread.
     */
    private record Entry(Path path, String name, boolean repository) {
    }

    private final String id;

    private final ImString typedPath = new ImString("", PATH_BUFFER);

    private final ImString filter = new ImString("", PATH_BUFFER);

    private Consumer<Path> onChosen;

    private String prompt = "Choose a folder";

    private boolean openRequested;

    /** Written from a worker, read every frame. */
    private volatile Listing listing;

    private volatile boolean loading;

    /** Where the browser currently is, whether or not its listing has arrived. */
    private volatile Path here;

    public DirectoryPicker(String id) {
        this.id = id;
    }

    /**
     * Opens the picker.
     *
     * @param prompt    what the caller is asking for, shown at the top
     * @param start     where to begin; the home directory when null
     * @param onChosen  run on the client thread with the chosen folder
     */
    public void open(String prompt, Path start, Consumer<Path> onChosen) {
        this.prompt = prompt;
        this.onChosen = onChosen;
        this.openRequested = true;

        // Deliberately not checked here. Asking whether the folder exists is file IO,
        // and open() runs on the render thread; a stale network mount can block on a
        // question that small. navigateTo reads it on a worker anyway, and a start
        // that turns out to be gone shows the same "no folder at that path" as any
        // other bad path rather than being a special case.
        Path from = start != null ? start : home();
        typedPath.set(from.toString());
        navigateTo(from);
    }

    /**
     * Draws the modal. Call unconditionally from the owner's frame.
     *
     * <p>An ImGui popup only exists while whoever opened it is still submitting, so
     * a conditional call here closes the picker the moment the owner takes a
     * different branch.
     */
    public void draw() {
        String popup = prompt + "###" + id;
        if (openRequested) {
            ImGui.openPopup(popup);
            openRequested = false;
        }
        if (!ImGui.beginPopupModal(popup, ImGuiWindowFlags.AlwaysAutoResize)) {
            return;
        }

        drawLocationRow();
        ImGui.separator();
        drawList();
        ImGui.separator();
        drawActions();

        ImGui.endPopup();
    }

    private void drawLocationRow() {
        Path current = here;
        ImGui.textDisabled(current == null ? "" : ImGuiScreens.truncate(current.toString(), 70));

        ImGui.setNextItemWidth(ImGui.getFontSize() * 24.0f);
        boolean submitted = ImGui.inputText("##" + id + "-path", typedPath);
        ImGui.sameLine();
        if (ImGui.button("Go##" + id) || submitted) {
            goToTypedPath();
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Type or paste a path. Use this for anywhere the list below cannot reach.");
        }

        if (ImGui.button("Home##" + id)) {
            navigateTo(home());
        }
        ImGui.sameLine();
        // The action is guarded by the same condition as the disabling, so it cannot
        // run inside the block even though it is written inside it. Left as it is
        // rather than restructured: the shape reads oddly next to the collect-then-act
        // pattern used elsewhere, and the reason it is safe is worth writing down once
        // instead of being rediscovered by whoever checks next.
        boolean atTop = current == null || current.getParent() == null;
        if (atTop) {
            ImGui.beginDisabled();
        }
        if (ImGui.button("Up##" + id) && !atTop) {
            navigateTo(current.getParent());
        }
        if (atTop) {
            ImGui.endDisabled();
        }

        for (Path root : roots()) {
            ImGui.sameLine();
            if (ImGui.button(root.toString() + "##" + id + "-root")) {
                navigateTo(root);
            }
        }

        ImGui.setNextItemWidth(ImGui.getFontSize() * 24.0f);
        ImGui.inputTextWithHint("##" + id + "-filter", "Filter this folder", filter);
    }

    private void drawList() {
        float rowHeight = ImGui.getFrameHeightWithSpacing();
        float width = ImGui.getFontSize() * 28.0f;

        if (!ImGui.beginChild("##" + id + "-list", width, rowHeight * 10.0f, true)) {
            ImGui.endChild();
            return;
        }

        Listing current = listing;
        if (loading || current == null) {
            ImGui.textDisabled("Reading...");
        } else if (current.problem() != null) {
            // Not being allowed in somewhere is ordinary, so it reads as a fact about
            // the folder rather than as an error the person has caused.
            Notice.warning(current.problem());
        } else if (current.entries().isEmpty()) {
            ImGui.textDisabled("No folders in here.");
        } else {
            drawEntries(current);
        }

        ImGui.endChild();
    }

    private void drawEntries(Listing current) {
        String text = filter.get().trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        int matched = 0;

        for (Entry entry : current.entries()) {
            if (!text.isEmpty() && !entry.name().toLowerCase(Locale.ROOT).contains(text)) {
                continue;
            }
            matched++;
            if (shown >= MAX_SHOWN) {
                continue;
            }
            shown++;

            if (ImGui.selectable(entry.name() + "##" + id + "-" + entry.name())) {
                navigateTo(entry.path());
            }
            if (entry.repository()) {
                ImGui.sameLine();
                ImGui.textDisabled("git");
            }
        }

        if (matched == 0) {
            ImGui.textDisabled("Nothing in here matches that.");
        } else if (matched > shown) {
            ImGui.textDisabled((matched - shown) + " more; type above to narrow it down");
        }
        if (current.truncated()) {
            // Said plainly. Showing part of a folder as if it were all of it is how
            // someone concludes their project is not there and gives up.
            ImGui.textDisabled("This folder has more than " + MAX_ENTRIES
                    + " subfolders; type the path above to go straight there.");
        }
    }

    private void drawActions() {
        Path current = here;
        boolean choosable = current != null && !loading;

        if (!choosable) {
            ImGui.beginDisabled();
        }
        if (ImGui.button("Choose this folder##" + id) && choosable) {
            Consumer<Path> callback = onChosen;
            ImGui.closeCurrentPopup();
            if (callback != null) {
                callback.accept(current);
            }
        }
        if (!choosable) {
            ImGui.endDisabled();
        }
        if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
            ImGui.setTooltip(choosable
                    ? "Use this folder."
                    : "Still reading this folder.");
        }

        ImGui.sameLine();
        if (ImGui.button("Cancel##" + id)) {
            ImGui.closeCurrentPopup();
        }
    }

    private void goToTypedPath() {
        String text = typedPath.get().trim();
        if (text.isEmpty()) {
            return;
        }
        try {
            navigateTo(Path.of(text));
        } catch (InvalidPathException invalid) {
            listing = new Listing(here, List.of(), "That is not a usable path on this system.", false);
        }
    }

    /**
     * Moves the browser, and starts reading the new folder on a worker.
     *
     * <p>{@code here} is updated straight away rather than when the listing arrives,
     * so the path shown and the folder that "Choose this folder" would pick never
     * disagree, even while it is still reading.
     */
    private void navigateTo(Path directory) {
        Path target = directory.toAbsolutePath().normalize();
        here = target;
        typedPath.set(target.toString());
        filter.set("");
        loading = true;

        Thread.ofVirtual().name("mcmarkings-directory-picker").start(() -> {
            try {
                listing = read(target);
            } catch (RuntimeException failure) {
                McMarkingsCompanion.LOGGER.warn("[mcmarkings] could not read " + target, failure);
                listing = new Listing(target, List.of(), "Could not read this folder.", false);
            } finally {
                loading = false;
            }
        });
    }

    private static Listing read(Path directory) {
        if (!Files.isDirectory(directory)) {
            return new Listing(directory, List.of(), "There is no folder at that path.", false);
        }

        List<Entry> entries = new ArrayList<>();
        boolean truncated = false;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child) || isHidden(child)) {
                    continue;
                }
                if (entries.size() >= MAX_ENTRIES) {
                    truncated = true;
                    break;
                }
                String name = String.valueOf(child.getFileName());
                entries.add(new Entry(child, name, looksLikeRepository(child)));
            }
        } catch (IOException | RuntimeException denied) {
            return new Listing(directory, List.of(), "This folder cannot be opened from here.", false);
        }

        entries.sort(Comparator.comparing(entry -> entry.name().toLowerCase(Locale.ROOT)));
        return new Listing(directory, List.copyOf(entries), null, truncated);
    }

    /**
     * Hidden folders are left out, except a repository's own.
     *
     * <p>A leading dot is the convention everywhere the game runs, and dotfiles are
     * almost never what someone is looking for when picking a project folder.
     */
    private static boolean isHidden(Path path) {
        String name = String.valueOf(path.getFileName());
        return name.startsWith(".");
    }

    /**
     * Whether this looks like a git repository.
     *
     * <p>A worktree has a {@code .git} file rather than a directory, so both count.
     * This is a hint in the list, not a filter: someone may well be picking a folder
     * that is about to become a repository.
     */
    private static boolean looksLikeRepository(Path directory) {
        try {
            Path marker = directory.resolve(".git");
            return Files.isDirectory(marker) || Files.isRegularFile(marker);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private static Path home() {
        String property = System.getProperty("user.home");
        if (property != null && !property.isBlank()) {
            try {
                return Path.of(property);
            } catch (InvalidPathException ignored) {
                // Falls through to a filesystem root below.
            }
        }
        List<Path> roots = roots();
        return roots.isEmpty() ? Path.of(".").toAbsolutePath() : roots.getFirst();
    }

    /**
     * The filesystem roots, so drive letters and mount points are reachable.
     *
     * <p>Capped, because a machine with a long list of mapped drives would otherwise
     * push every other control off the row.
     */
    private static List<Path> roots() {
        List<Path> roots = new ArrayList<>();
        for (Path root : java.nio.file.FileSystems.getDefault().getRootDirectories()) {
            roots.add(root);
            if (roots.size() == 4) {
                break;
            }
        }
        return roots;
    }
}
