package dev.kierandrewett.mcmarkings;

import dev.kierandrewett.mcmarkings.config.CompanionConfig;
import dev.kierandrewett.mcmarkings.gui.imgui.Persist;
import dev.kierandrewett.mcmarkings.config.RepositoryEntry;
import dev.kierandrewett.mcmarkings.command.CommandRegistry;
import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.core.RepoImage;
import dev.kierandrewett.mcmarkings.doc.Document;
import dev.kierandrewett.mcmarkings.doc.History;
import dev.kierandrewett.mcmarkings.doc.RecoveryStore;
import dev.kierandrewett.mcmarkings.imageframe.ClientCommandSink;
import dev.kierandrewett.mcmarkings.imageframe.CommandSink;
import dev.kierandrewett.mcmarkings.js.RhinoGeneratorRuntime;
import dev.kierandrewett.mcmarkings.registry.JsonMapRegistry;
import dev.kierandrewett.mcmarkings.registry.MapRegistry;
import dev.kierandrewett.mcmarkings.render.FontRegistry;
import dev.kierandrewett.mcmarkings.render.ImageComposer;
import dev.kierandrewett.mcmarkings.repo.ProcessGitService;
import dev.kierandrewett.mcmarkings.repo.RepoScanner;
import dev.kierandrewett.mcmarkings.texture.RuntimeTextureCache;
import dev.kierandrewett.mcmarkings.texture.ThumbnailCache;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Everything the screens need, across every configured repository.
 *
 * <p>Construction never fails and never throws. Having no repositories at all is
 * the normal state on a first run, not an error, and a repository whose folder has
 * since been moved or deleted is recorded with a warning rather than taking the
 * rest of the mod down with it. The screens are expected to cope with an empty
 * list and guide the user through fixing it.
 */
public final class CompanionServices {

    /** Comfortably more than a screenful, well short of exhausting VRAM. */
    private static final int MAX_RESIDENT_THUMBNAILS = 512;

    public final CompanionConfig config;
    public final MapRegistry registry;
    public final CommandSink commands;
    public final ThumbnailCache thumbnails;
    public final FontRegistry fonts;
    public final ImageComposer composer;

    /**
     * In-progress work, kept recoverable across a crash.
     *
     * <p>Client-wide rather than per repository: there is one document being edited
     * at a time, and which repository it belongs to is recorded inside the snapshot.
     */
    public final RecoveryStore recovery;

    /**
     * Everything the user can do, so a button, a shortcut and the palette all
     * invoke the same thing rather than three copies that drift.
     */
    public final CommandRegistry actions = new CommandRegistry();

    /**
     * The document being edited, and its undo stack.
     *
     * <p>Session state rather than screen state. Minecraft discards a Screen on
     * Escape, which in a game happens constantly, so a document owned by the editor
     * panel would be lost every time someone glanced at the world. Only touched
     * from the client thread.
     */
    public final History editing = new History(
            Document.blank("untitled", new GridSize(2, 1), 256));

    /**
     * The document as it was last written to disk.
     *
     * <p>Here for the same reason the history is: a fresh window is built every time
     * the key is pressed, so anything the editor panel remembered about whether the
     * work is saved would reset the moment someone glanced at the world. It did, and
     * the effect was that unsaved work looked saved, which is the one thing that
     * indicator exists to prevent.
     *
     * <p>Null until something is saved or opened, which correctly reads as unsaved
     * for a document that has been edited and never as unsaved for a blank one.
     */
    private Document savedDocument = editing.current();

    /**
     * The two settings files, each written by exactly one saver.
     *
     * <p>Here rather than in the panels that write them. Two panels each holding
     * their own saver for the same file is not a guard at all: each one collapses
     * only its own writes, so a rename in one tab and a checkbox in another can land
     * on the file at the same moment. One per file is the whole point.
     */
    private final Persist configSaver;

    private final Persist registrySaver;

    /** Writes the config, off the client thread, collapsing repeated calls. */
    public void saveConfig() {
        configSaver.request();
    }

    /** Writes the map registry, off the client thread, collapsing repeated calls. */
    public void saveRegistry() {
        registrySaver.request();
    }

    /** Called after a successful save or open. */
    public void markSaved(Document document) {
        this.savedDocument = document;
    }

    /** True when the document has changed since it was last written. */
    public boolean hasUnsavedEdits() {
        return !editing.current().equals(savedDocument);
    }

    private final ClientCommandSink commandSink;

    /** Insertion-ordered so the GUI list matches the configured order. */
    private final Map<String, Workspace> workspaces = new LinkedHashMap<>();

    /** Problems worth telling the user about once, on the first screen they open. */
    private final List<String> startupNotes = new ArrayList<>();

    /** Run once opening finishes, so a screen already on display can refresh. */
    private final List<Runnable> readyListeners = new ArrayList<>();

    private volatile boolean loading;

    private volatile boolean flushingRecovery;

    /**
     * Constructed on the client thread, so it does no work that can block one.
     *
     * <p>Opening repositories walks thousands of files, parses their metadata and
     * compiles the generator scripts, and enumerating fonts is slow on its own.
     * Doing any of that here froze the game for as long as it took, because this is
     * reached straight from a keypress. It all happens on a background thread
     * instead, and {@link #isLoading()} is true until it lands.
     */
    public CompanionServices(CompanionConfig config, Consumer<String> onDroppedCommand) {
        this.config = config;

        // FontRegistry only enumerates on first use, so constructing it is free.
        // Nothing here may call into it.
        this.fonts = new FontRegistry(config.fontSearchPaths);

        this.composer = new ImageComposer();
        this.commandSink = new ClientCommandSink(config.commandsPerSecond, onDroppedCommand);
        this.commands = commandSink;

        JsonMapRegistry loadedRegistry = new JsonMapRegistry();
        try {
            loadedRegistry.load();
        } catch (IOException exception) {
            startupNotes.add("Could not read the map registry, starting empty: " + exception.getMessage());
        }
        this.registry = loadedRegistry;

        // After the fields they write, which is what makes them final rather than
        // lazily created on first use.
        this.configSaver = new Persist("the config", config::save);
        this.registrySaver = new Persist("the map registry", this.registry::save);

        this.thumbnails = new RuntimeTextureCache(this::thumbnailFor, MAX_RESIDENT_THUMBNAILS);
        this.recovery = new RecoveryStore(
                FabricLoader.getInstance().getConfigDir().resolve(McMarkingsCompanion.MOD_ID + "-recovery.json"));

        this.loading = !config.repositories.isEmpty();
        Thread.ofVirtual().name("mcmarkings-open").start(this::openInBackground);
    }

    /**
     * True while repositories are still being opened.
     *
     * <p>Distinct from having none configured: a screen should show progress in the
     * first case and offer setup in the second, and it cannot tell them apart from
     * an empty workspace list alone.
     */
    public boolean isLoading() {
        return loading;
    }

    /** True when a repository is configured, whether or not it has finished opening. */
    public boolean hasConfiguredRepositories() {
        return !config.repositories.isEmpty();
    }

    /**
     * Runs {@code listener} on the client thread once opening finishes, or straight
     * away when it already has, so a screen opened at any moment sees the result.
     */
    public void whenReady(Runnable listener) {
        synchronized (readyListeners) {
            if (!loading) {
                Minecraft.getInstance().execute(listener);
                return;
            }
            readyListeners.add(listener);
        }
    }

    private void openInBackground() {
        List<String> notes = new ArrayList<>();
        Map<String, Workspace> opened = new LinkedHashMap<>();

        try {
            // Touching the registry is what makes it enumerate, so it has to happen
            // here rather than in the constructor.
            notes.addAll(fonts.warnings());

            for (RepositoryEntry entry : List.copyOf(config.repositories)) {
                opened.put(entry.id(), open(entry));
            }
        } catch (RuntimeException exception) {
            McMarkingsCompanion.LOGGER.error("[mcmarkings] failed while opening repositories", exception);
            notes.add("Could not finish opening the repositories: " + exception.getMessage());
        }

        Minecraft.getInstance().execute(() -> {
            workspaces.putAll(opened);
            startupNotes.addAll(notes);
            loading = false;

            List<Runnable> pending;
            synchronized (readyListeners) {
                pending = List.copyOf(readyListeners);
                readyListeners.clear();
            }
            pending.forEach(Runnable::run);
        });
    }

    /** Drives the throttled command queue and the recovery snapshot, once per tick. */
    public void tick(Minecraft client) {
        commandSink.tick(client);
        flushRecoveryIfDue();
    }

    /**
     * Writes the recovery snapshot when it is due, on a background thread.
     *
     * <p>The tick itself must stay free of file IO, and the in-flight flag matters:
     * without it a slow disk would have every tick starting another writer, which is
     * twenty threads a second all writing the same file.
     */
    private void flushRecoveryIfDue() {
        if (flushingRecovery || !recovery.hasUnsavedChanges()) {
            return;
        }
        flushingRecovery = true;
        Thread.ofVirtual().name("mcmarkings-recovery").start(() -> {
            try {
                recovery.flushIfDue(System.currentTimeMillis());
            } catch (IOException exception) {
                // Losing a snapshot is not worth interrupting anyone over; the work
                // is still on screen, and the next flush will try again.
                McMarkingsCompanion.LOGGER.debug("[mcmarkings] could not write the recovery snapshot", exception);
            } finally {
                flushingRecovery = false;
            }
        });
    }

    public List<String> startupNotes() {
        return List.copyOf(startupNotes);
    }

    public void clearStartupNotes() {
        startupNotes.clear();
    }

    public boolean hasRepositories() {
        return !workspaces.isEmpty();
    }

    public List<Workspace> workspaces() {
        return List.copyOf(workspaces.values());
    }

    public Optional<Workspace> active() {
        return config.active().map(entry -> workspaces.get(entry.id()))
                .or(() -> workspaces.values().stream().findFirst());
    }

    public Optional<Workspace> byId(String id) {
        return Optional.ofNullable(workspaces.get(id));
    }

    /**
     * The active workspace, or a stand-in that behaves like an empty repository.
     *
     * <p>Screens should still check {@link #hasRepositories()} and offer setup, but
     * nothing has to null-guard every call to avoid a crash on a fresh install.
     */
    public Workspace current() {
        return active().orElseGet(EmptyWorkspace::create);
    }

    public dev.kierandrewett.mcmarkings.repo.RepoService repo() {
        return current().repo();
    }

    public dev.kierandrewett.mcmarkings.repo.GitService git() {
        return current().git();
    }

    public dev.kierandrewett.mcmarkings.js.GeneratorRuntime generators() {
        return current().generators();
    }

    /** Root of the active repository, for writing generated output into. */
    public Path repoRoot() {
        return current().repo().root();
    }

    /** How the active repository's raw file URLs are built, and which forge serves them. */
    public dev.kierandrewett.mcmarkings.repo.RawUrls.Target rawUrls()
            throws dev.kierandrewett.mcmarkings.repo.GitException {
        return current().rawUrls();
    }

    public String activeRepositoryId() {
        return active().map(Workspace::id).orElse("");
    }

    public void setActive(String id) {
        if (workspaces.containsKey(id)) {
            config.activeRepositoryId = id;
            config.save();
        }
    }

    /**
     * Adds a folder and opens it. Returns the workspace so the caller can show
     * whatever went wrong, since a repository that opened with a warning is still
     * added rather than silently rejected.
     */
    public Workspace addRepository(Path directory) {
        RepositoryEntry entry = config.addRepository(directory);
        config.save();
        // Opening scans the folder, so it must not run on the client thread. The
        // caller gets the entry immediately and the scan lands through whenReady.
        Workspace blank = EmptyWorkspace.create();
        Workspace placeholder = new Workspace(entry, blank.repo(), blank.git(), blank.generators(),
                "Opening...");
        workspaces.put(entry.id(), placeholder);
        reloadAsync(entry.id());
        return placeholder;
    }

    /** Re-opens one repository on a background thread, refreshing screens when done. */
    public void reloadAsync(String id) {
        Optional<RepositoryEntry> entry = config.byId(id);
        if (entry.isEmpty()) {
            return;
        }
        synchronized (readyListeners) {
            loading = true;
        }
        Thread.ofVirtual().name("mcmarkings-reload").start(() -> {
            Workspace reopened = open(entry.get());
            Minecraft.getInstance().execute(() -> {
                workspaces.put(id, reopened);
                loading = false;
                List<Runnable> pending;
                synchronized (readyListeners) {
                    pending = List.copyOf(readyListeners);
                    readyListeners.clear();
                }
                pending.forEach(Runnable::run);
            });
        });
    }

    public void removeRepository(String id) {
        workspaces.remove(id);
        config.removeRepository(id);
        config.save();
    }

    /** Re-scans one repository, picking up files added outside the game. */
    public Workspace reload(String id) {
        Optional<RepositoryEntry> entry = config.byId(id);
        if (entry.isEmpty()) {
            return null;
        }
        Workspace reopened = open(entry.get());
        workspaces.put(id, reopened);
        return reopened;
    }


    /**
     * Opens one repository, degrading rather than throwing.
     *
     * <p>A folder that has been moved or deleted since it was configured still
     * produces a workspace, carrying the reason, so the repositories screen can
     * offer to locate or remove it.
     */
    private Workspace open(RepositoryEntry entry) {
        RepositoryCheck check = RepositoryCheck.inspect(entry.root());

        RepoScanner repo = new RepoScanner(entry.root(), config.ignoredDirectories);
        String warning = null;

        if (!check.usable()) {
            warning = String.join(" ", check.notes());
        } else {
            try {
                repo.rescan();
            } catch (IOException | RuntimeException exception) {
                warning = "Could not read this folder: " + exception.getMessage();
            }
            if (warning == null && !check.notes().isEmpty()) {
                warning = String.join(" ", check.notes());
            }
        }

        RhinoGeneratorRuntime generators =
                new RhinoGeneratorRuntime(entry.root(), config.generatorDirectory, fonts);
        if (check.usable() && check.hasGenerators()) {
            try {
                generators.reload();
            } catch (Exception exception) {
                // A broken script must not cost the browser, which needs nothing
                // from the generator runtime.
                String note = "Generators failed to load: " + exception.getMessage();
                warning = warning == null ? note : warning + " " + note;
            }
        }

        return new Workspace(entry, repo, new ProcessGitService(entry.root()), generators, warning);
    }

    /**
     * Thumbnails are resolved against whichever repository holds the image, so the
     * browser can show more than one repository's contents at once.
     */
    private BufferedImage thumbnailFor(RepoImage image, int maxEdge) {
        for (Workspace workspace : workspaces.values()) {
            Path candidate = workspace.repo().resolve(image.path());
            if (java.nio.file.Files.isRegularFile(candidate)) {
                try {
                    return composer.thumbnail(candidate, maxEdge);
                } catch (IOException exception) {
                    throw new IllegalStateException("could not read " + candidate, exception);
                }
            }
        }
        throw new IllegalStateException("no repository holds " + image.path());
    }
}
