package dev.kierandrewett.mcmarkings.registry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.core.MapEntry;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The map registry as a JSON file in the mod's config directory.
 *
 * <p>It lives beside the main config rather than inside it because the two have
 * different owners: settings are hand-edited, this file is machine-written on
 * every map creation and would drag the user's settings through each rewrite.
 *
 * <p>Writes go to a temp file and are then moved into place, so a crash halfway
 * through a save leaves the previous registry intact. Losing the registry means
 * losing the only link from a repo path back to the maps built from it, and
 * there is no way to rebuild it from the server.
 */
public class JsonMapRegistry implements MapRegistry {

    /** Bumped only if the on-disk shape changes in a way a reader has to know about. */
    private static final int FORMAT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    private final Map<String, MapEntry> entries = new ConcurrentHashMap<>();

    public JsonMapRegistry() {
        this(defaultPath());
    }

    public JsonMapRegistry(Path file) {
        this.file = file;
    }

    public static Path defaultPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(McMarkingsCompanion.MOD_ID + "-maps.json");
    }

    public Path file() {
        return file;
    }

    @Override
    public Optional<MapEntry> byName(String imageFrameName) {
        if (imageFrameName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(imageFrameName));
    }

    @Override
    public List<MapEntry> byRepoPath(String repoPath) {
        if (repoPath == null) {
            return List.of();
        }
        List<MapEntry> matches = new ArrayList<>();
        for (MapEntry entry : entries.values()) {
            if (repoPath.equals(entry.repoPath())) {
                matches.add(entry);
            }
        }
        matches.sort(Comparator.comparing(MapEntry::imageFrameName));
        return List.copyOf(matches);
    }

    @Override
    public List<MapEntry> all() {
        List<MapEntry> snapshot = new ArrayList<>(entries.values());
        snapshot.sort(Comparator.comparing(MapEntry::imageFrameName));
        return List.copyOf(snapshot);
    }

    @Override
    public void put(MapEntry entry) {
        if (entry == null || entry.imageFrameName() == null || entry.imageFrameName().isBlank()) {
            throw new IllegalArgumentException("a map entry needs an ImageFrame name");
        }
        entries.put(entry.imageFrameName(), entry);
    }

    @Override
    public void remove(String imageFrameName) {
        if (imageFrameName == null) {
            return;
        }
        entries.remove(imageFrameName);
    }

    @Override
    public void load() throws IOException {
        if (!Files.isRegularFile(file)) {
            entries.clear();
            return;
        }

        Document document;
        try {
            document = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Document.class);
        } catch (JsonParseException exception) {
            // A checked failure, so the caller has to decide whether to carry on with
            // an empty registry or stop and let the user look at the file.
            throw new IOException("[mcmarkings] map registry at " + file + " is not valid JSON", exception);
        }

        entries.clear();
        if (document == null || document.entries == null) {
            return;
        }
        for (MapEntry entry : document.entries) {
            if (entry != null && entry.imageFrameName() != null && !entry.imageFrameName().isBlank()) {
                entries.put(entry.imageFrameName(), entry);
            }
        }
        McMarkingsCompanion.LOGGER.info("[mcmarkings] loaded {} map entries from {}", entries.size(), file);
    }

    @Override
    public void save() throws IOException {
        Path target = file.toAbsolutePath();
        Path parent = target.getParent();
        Files.createDirectories(parent);

        // Sorted so the file diffs cleanly rather than reshuffling on every save.
        Document document = new Document();
        document.version = FORMAT_VERSION;
        document.entries = all();
        String json = GSON.toJson(document) + "\n";

        // The temp file has to sit in the same directory as the target, or the move
        // crosses a filesystem boundary and stops being atomic.
        Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            moveIntoPlace(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveIntoPlace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            // Some filesystems cannot promise atomicity; a plain replace is still far
            // better than writing over the live file in place.
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * On-disk shape. A wrapper rather than a bare array so a version can travel with
     * it. Plain mutable fields with a default constructor, because that is what Gson
     * can populate without resorting to Unsafe.
     */
    private static final class Document {

        private int version = FORMAT_VERSION;
        private List<MapEntry> entries = List.of();
    }
}
