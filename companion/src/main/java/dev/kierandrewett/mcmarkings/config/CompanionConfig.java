package dev.kierandrewett.mcmarkings.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * User settings, stored as JSON next to the other Fabric mod configs so they are
 * readable and diffable on disk.
 *
 * <p>Everything here is editable from the GUI. The file is the storage format, not
 * the interface: nobody should have to open it to use the mod.
 */
public class CompanionConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Every repository the mod knows about, in the order they appear in the GUI. */
    public List<RepositoryEntry> repositories = new ArrayList<>();

    /** Which repository the screens act on. Blank means the first one. */
    public String activeRepositoryId = "";

    /** Command root without the slash; servers often rebind imageframe to frame. */
    public String commandAlias = "imageframe";

    /** Directories searched for the Transport typeface, which is not in the repo. */
    public List<String> fontSearchPaths = new ArrayList<>(defaultFontSearchPaths());

    /**
     * Where installed fonts live, per platform.
     *
     * <p>Paths are built through {@link Path} rather than by joining strings, so the
     * separator is right everywhere. Directories that do not exist are harmless: the
     * font registry skips them.
     */
    public static List<String> defaultFontSearchPaths() {
        Path home = Path.of(System.getProperty("user.home", "."));
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        if (os.contains("win")) {
            String windows = System.getenv().getOrDefault("WINDIR", "C:\\Windows");
            return List.of(
                    Path.of(windows, "Fonts").toString(),
                    home.resolve(Path.of("AppData", "Local", "Microsoft", "Windows", "Fonts")).toString());
        }

        if (os.contains("mac") || os.contains("darwin")) {
            return List.of(
                    home.resolve(Path.of("Library", "Fonts")).toString(),
                    Path.of("/Library/Fonts").toString(),
                    Path.of("/System/Library/Fonts").toString());
        }

        return List.of(
                home.resolve(Path.of(".local", "share", "fonts")).toString(),
                home.resolve(".fonts").toString(),
                Path.of("/usr/share/fonts").toString(),
                Path.of("/usr/local/share/fonts").toString());
    }

    /**
     * Export resolution per map frame. Vanilla maps are 128px, but ImageFrameClient
     * renders full colour at higher detail, so 256 is a better default.
     */
    public int exportPixelsPerFrame = 256;

    /** Invisible frames requested as glowing rather than plain. */
    public boolean glowingFrames = true;

    /** Commands per second sent to the server, to stay under chat rate limits. */
    public double commandsPerSecond = 2.0;

    /**
     * Folder names never walked when scanning for images.
     *
     * <p>Anything beginning with a dot is skipped regardless. These are the common
     * build and dependency folders, which hold no images worth showing and are
     * expensive to walk. Editable, because one repository's junk is another's
     * content.
     */
    public List<String> ignoredDirectories =
            new ArrayList<>(dev.kierandrewett.mcmarkings.repo.RepoScanner.DEFAULT_IGNORED_DIRECTORIES);

    /** Directory generated PNGs are written into, relative to a repository root. */
    public String generatedDirectory = "generated";

    /** Directory generator scripts are read from, relative to a repository root. */
    public String generatorDirectory = "generators";

    /**
     * Single-repository settings from before multi-repository support.
     *
     * <p>Kept only so an existing config migrates instead of silently losing the
     * folder someone already set up. Cleared once carried across.
     */
    public String repoPath = "";
    public String branch = "";
    public String githubSlug = "";

    public Optional<RepositoryEntry> active() {
        if (repositories.isEmpty()) {
            return Optional.empty();
        }
        return repositories.stream()
                .filter(entry -> entry.id().equals(activeRepositoryId))
                .findFirst()
                .or(() -> Optional.of(repositories.getFirst()));
    }

    public Optional<RepositoryEntry> byId(String id) {
        return repositories.stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    /**
     * Adds a repository, or returns the existing entry when the folder is already
     * known. Adding the same folder twice is a misclick, not a request for a
     * duplicate.
     */
    public RepositoryEntry addRepository(Path directory) {
        RepositoryEntry candidate = RepositoryEntry.of(directory);

        // Matched on the folder itself rather than the id. Ids are derived, and
        // matching on a derived value would let a change in how they are derived
        // quietly add a second entry for a folder that is already here.
        Path target = directory.toAbsolutePath().normalize();
        Optional<RepositoryEntry> existing = repositories.stream()
                .filter(entry -> entry.root().toAbsolutePath().normalize().equals(target))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        repositories.add(candidate);
        if (activeRepositoryId.isBlank()) {
            activeRepositoryId = candidate.id();
        }
        return candidate;
    }

    public void removeRepository(String id) {
        repositories.removeIf(entry -> entry.id().equals(id));
        if (activeRepositoryId.equals(id)) {
            activeRepositoryId = repositories.isEmpty() ? "" : repositories.getFirst().id();
        }
    }

    public void replaceRepository(RepositoryEntry updated) {
        for (int index = 0; index < repositories.size(); index++) {
            if (repositories.get(index).id().equals(updated.id())) {
                repositories.set(index, updated);
                return;
            }
        }
    }

    /** Carries a pre-multi-repository config across, then forgets the old fields. */
    void migrate() {
        if (repositories.isEmpty() && repoPath != null && !repoPath.isBlank()) {
            RepositoryEntry migrated = RepositoryEntry.of(
                    Path.of(repoPath),
                    Path.of(repoPath).getFileName().toString(),
                    branch == null || branch.isBlank() ? "main" : branch);
            if (githubSlug != null && !githubSlug.isBlank()) {
                migrated = new RepositoryEntry(migrated.id(), migrated.name(), migrated.path(),
                        migrated.branch(), githubSlug, migrated.rawUrlTemplate());
            }
            repositories.add(migrated);
            activeRepositoryId = migrated.id();
            McMarkingsCompanion.LOGGER.info("[mcmarkings] migrated {} into the repository list", repoPath);
        }
        repoPath = "";
        branch = "";
        githubSlug = "";
    }

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(McMarkingsCompanion.MOD_ID + ".json");
    }

    public static CompanionConfig load() {
        Path path = configPath();
        if (!Files.exists(path)) {
            CompanionConfig fresh = new CompanionConfig();
            fresh.migrate();
            fresh.save();
            return fresh;
        }
        try {
            CompanionConfig loaded = GSON.fromJson(Files.readString(path), CompanionConfig.class);
            if (loaded == null) {
                loaded = new CompanionConfig();
            }
            if (loaded.repositories == null) {
                loaded.repositories = new ArrayList<>();
            }
            if (loaded.fontSearchPaths == null) {
                loaded.fontSearchPaths = new ArrayList<>();
            }
            loaded.migrate();
            return loaded;
        } catch (IOException | RuntimeException exception) {
            McMarkingsCompanion.LOGGER.error("[mcmarkings] could not read config, using defaults", exception);
            return new CompanionConfig();
        }
    }

    /**
     * Writes the config.
     *
     * <p>Through a temporary file and a move, as the recovery snapshot and templates
     * already were. Writing in place truncates first, so an interrupted write leaves
     * a half-written file, and the one thing that must survive a crash is the list of
     * where someone's repositories are. Losing that turns a crash into a setup.
     */
    public void save() {
        try {
            Path path = configPath();
            Files.createDirectories(path.getParent());

            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(this));
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicNotSupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            McMarkingsCompanion.LOGGER.error("[mcmarkings] could not write config", exception);
        }
    }
}
