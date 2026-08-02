package dev.kierandrewett.mcmarkings;

import dev.kierandrewett.mcmarkings.config.CompanionConfig;
import dev.kierandrewett.mcmarkings.config.RepositoryEntry;
import dev.kierandrewett.mcmarkings.core.RepoImage;
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

    /** Thumbnails are drawn into a small cell, so decoding beyond this is wasted. */
    private static final int THUMBNAIL_EDGE = 128;

    /** Comfortably more than a screenful, well short of exhausting VRAM. */
    private static final int MAX_RESIDENT_THUMBNAILS = 512;

    public final CompanionConfig config;
    public final MapRegistry registry;
    public final CommandSink commands;
    public final ThumbnailCache thumbnails;
    public final FontRegistry fonts;
    public final ImageComposer composer;

    private final ClientCommandSink commandSink;

    /** Insertion-ordered so the GUI list matches the configured order. */
    private final Map<String, Workspace> workspaces = new LinkedHashMap<>();

    /** Problems worth telling the user about once, on the first screen they open. */
    private final List<String> startupNotes = new ArrayList<>();

    public CompanionServices(CompanionConfig config, Consumer<String> onDroppedCommand) {
        this.config = config;

        this.fonts = new FontRegistry(config.fontSearchPaths);
        startupNotes.addAll(fonts.warnings());

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

        this.thumbnails = new RuntimeTextureCache(this::thumbnailFor, MAX_RESIDENT_THUMBNAILS);

        openConfiguredRepositories();
    }

    /** Drives the throttled command queue; call once per client tick. */
    public void tick(net.minecraft.client.Minecraft client) {
        commandSink.tick(client);
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
        Workspace workspace = open(entry);
        workspaces.put(entry.id(), workspace);
        return workspace;
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

    private void openConfiguredRepositories() {
        for (RepositoryEntry entry : List.copyOf(config.repositories)) {
            workspaces.put(entry.id(), open(entry));
        }
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
    private BufferedImage thumbnailFor(RepoImage image) {
        for (Workspace workspace : workspaces.values()) {
            Path candidate = workspace.repo().resolve(image.path());
            if (java.nio.file.Files.isRegularFile(candidate)) {
                try {
                    return composer.thumbnail(candidate, THUMBNAIL_EDGE);
                } catch (IOException exception) {
                    throw new IllegalStateException("could not read " + candidate, exception);
                }
            }
        }
        throw new IllegalStateException("no repository holds " + image.path());
    }
}
