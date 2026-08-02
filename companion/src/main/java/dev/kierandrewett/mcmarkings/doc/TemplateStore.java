package dev.kierandrewett.mcmarkings.doc;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Saved documents in a repository, which is all a template is.
 *
 * <p>Starting from something is the difference between a tool you open and a tool
 * you use. There is no separate template format: a template is a document that was
 * saved, so anything made in the editor can become the starting point for the next
 * thing without a conversion step or a second file type to keep in step.
 *
 * <p>Templates live in the repository rather than in the mod's config, so they
 * travel with the images they refer to. A template pointing at
 * {@code signs/roundel.png} is meaningless in a repository that has no such file,
 * and committing them together keeps the two honest.
 */
public final class TemplateStore {

    /** Where templates live inside a repository. */
    public static final String DIRECTORY = "templates";

    private static final String EXTENSION = ".json";

    /** Bigger than any hand-made document, small enough to reject a stray file. */
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    private final Path root;

    public TemplateStore(Path repositoryRoot) {
        this.root = repositoryRoot.toAbsolutePath().normalize();
    }

    public Path directory() {
        return root.resolve(DIRECTORY);
    }

    /** One template on disk. */
    /**
     * A saved document.
     *
     * <p>{@code savedAtMillis} is read here rather than by whoever displays it,
     * because asking the filesystem for a modification time is IO and the only place
     * this is shown is a list being drawn. Zero when it could not be read, which the
     * caller can treat as unknown rather than as 1970.
     */
    public record Entry(String name, Path file, long savedAtMillis) {
    }

    /**
     * Display names, keyed by file identity.
     *
     * <p>The name shown has to be the one the author typed, capitals and all, and
     * that only exists inside the document; the file name has been flattened to be
     * portable. Reading it back means opening each template, so the answer is
     * cached against size and modification time. Templates are small and there are
     * tens of them, not the thousands there are images, so this stays cheap even
     * when a picker lists them every frame.
     */
    private final Map<String, String> nameCache = new HashMap<>();

    /** Every template, by display name. Missing directory is empty, not an error. */
    public List<Entry> list() {
        Path directory = directory();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }

        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(EXTENSION))
                    .forEach(file -> entries.add(new Entry(displayNameOf(file), file, modifiedAt(file))));
        } catch (IOException exception) {
            return List.of();
        }

        entries.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(entries);
    }

    /** Zero rather than throwing: a template with an unreadable time is still openable. */
    private static long modifiedAt(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException | RuntimeException unreadable) {
            return 0L;
        }
    }

    public Optional<Entry> byName(String name) {
        String wanted = fileNameFor(name);
        return list().stream()
                .filter(entry -> entry.file().getFileName().toString().equalsIgnoreCase(wanted))
                .findFirst();
    }

    public Document load(Entry entry) throws IOException {
        return load(entry.file());
    }

    public Document load(Path file) throws IOException {
        return readWithReport(file).document();
    }

    /**
     * Loads a template along with anything it could not read.
     *
     * <p>The report is the point: a template written by a newer build, or holding a
     * layer kind this one does not know, comes back short. Callers with a status line
     * should say so, because saving straight afterwards writes the shortened version
     * back over the file.
     *
     * <p>The guards live here rather than in {@code load}, so nothing can reach the
     * file by asking for the report and skip them on the way past.
     */
    public DocumentJson.Result readWithReport(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("no template at " + file.getFileName());
        }
        if (Files.size(file) > MAX_BYTES) {
            throw new IOException(file.getFileName() + " is too large to be a template");
        }
        return DocumentJson.readWithReport(Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * Saves a document as a template and returns where it landed.
     *
     * <p>Written to a temporary file and moved into place, so an interrupted save
     * cannot leave a half-written template that fails to load next time. The
     * document's own name is used, since a template someone has to name twice is a
     * template they will name badly.
     */
    public Path save(Document document) throws IOException {
        Path directory = directory();
        Files.createDirectories(directory);

        Path file = directory.resolve(fileNameFor(document.name()));
        Path temporary = directory.resolve(file.getFileName() + ".tmp");

        // Checked here as well as on the way in. Reading has always refused anything
        // over this, and writing never looked, so a document that had grown past it
        // saved without complaint and would not open again. Failing now, with the
        // file untouched, is the version of this someone can do something about.
        String json = DocumentJson.write(document);
        if (json.length() > MAX_BYTES) {
            throw new IOException(document.name() + " is too large to save as a template ("
                    + (json.length() / (1024 * 1024)) + "MB). Ungrouping or removing a few layers "
                    + "will bring it back under " + (MAX_BYTES / (1024 * 1024)) + "MB.");
        }

        Files.writeString(temporary, json, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicNotSupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }

    public void delete(Entry entry) throws IOException {
        Files.deleteIfExists(entry.file());
    }

    /**
     * A file name safe on every platform.
     *
     * <p>Templates are committed and shared, so a name that is legal on one machine
     * and not on another would break someone else's checkout rather than the author's.
     */
    /**
     * The file a name would be saved as.
     *
     * <p>Public because the save prompt has to ask the same question this answers.
     * Warning about an overwrite by comparing what someone typed is wrong: two
     * different names can flatten to one file, and then the second save replaces the
     * first with nothing having said so.
     */
    public static String fileNameFor(String name) {
        String cleaned = (name == null ? "" : name)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return (cleaned.isBlank() ? "untitled" : cleaned) + EXTENSION;
    }

    /** The author's own name for the template, or the file name if it will not parse. */
    private synchronized String displayNameOf(Path file) {
        String key;
        try {
            key = file + ":" + Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException exception) {
            return fallbackName(file);
        }

        String cached = nameCache.get(key);
        if (cached != null) {
            return cached;
        }

        String name = readName(file).orElseGet(() -> fallbackName(file));
        nameCache.put(key, name);
        return name;
    }

    /**
     * Pulls just the document's name out, without building the whole thing.
     *
     * <p>A template that will not parse still has to appear in the list, so a
     * corrupt file can be seen and deleted rather than being invisible.
     */
    private static Optional<String> readName(Path file) {
        try {
            if (Files.size(file) > MAX_BYTES) {
                return Optional.empty();
            }
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                return Optional.empty();
            }
            JsonElement name = parsed.getAsJsonObject().get("name");
            if (name == null || !name.isJsonPrimitive() || name.getAsString().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(name.getAsString());
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static String fallbackName(Path file) {
        String fileName = file.getFileName().toString();
        String stem = fileName.substring(0, Math.max(0, fileName.length() - EXTENSION.length()));
        return stem.replace('-', ' ');
    }
}
