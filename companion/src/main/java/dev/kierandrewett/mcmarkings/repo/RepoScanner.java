package dev.kierandrewett.mcmarkings.repo;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.core.RepoImage;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Walks a local clone for PNGs and merges whatever metadata the repository keeps
 * alongside them.
 *
 * <p>Nothing here knows what the images are of. A repository is a folder of PNGs,
 * optionally described by JSON sidecars that are recognised by their shape rather
 * than by their name, so a set of road signs, safety symbols, album art or map
 * tiles all work the same way.
 *
 * <p>A repository holds well over a thousand images, so the scan never decodes
 * pixel data: dimensions come straight out of the PNG IHDR chunk, which is a
 * 24-byte read per file rather than a full inflate. ImageIO is only reached for
 * when a file does not look like a normal PNG.
 *
 * <p>The cached image list is replaced wholesale on {@link #rescan()} and only
 * ever handed out as an immutable copy, so a scan running off the render thread
 * cannot tear a list someone else is iterating.
 */
public class RepoScanner implements RepoService {

    /**
     * Folder names skipped when no caller supplies a list.
     *
     * <p>The editable copy lives in the config; this is only the fallback for
     * callers that have no config to hand, such as tests. Anything beginning with
     * a dot is skipped regardless of this list.
     */
    public static final List<String> DEFAULT_IGNORED_DIRECTORIES =
            List.of("node_modules", "build", "target", "out", "dist");

    /**
     * Largest JSON the scan will open looking for metadata.
     *
     * <p>Every JSON near the images is a candidate, so a repository that happens to
     * contain a huge lockfile or data dump must not drag the scan down. Sidecars for
     * a few thousand images run to well under a megabyte, so this is generous.
     */
    static final long MAX_METADATA_BYTES = 8L * 1024L * 1024L;

    /**
     * Entry fields consulted for the image a metadata entry describes, in
     * preference order. Highest priority first.
     */
    private static final List<String> FILE_KEYS = List.of("file", "filename", "path", "image", "src");

    /** Entry fields consulted for the human-readable label, in preference order. */
    private static final List<String> DESCRIPTION_KEYS =
            List.of("description", "desc", "title", "caption", "summary");

    /** Entry fields consulted for a catalogue code, in preference order. */
    private static final List<String> REFERENCE_KEYS = List.of("reference", "diagram", "code", "id", "ref");

    /** Entry fields consulted for the group an image belongs to. */
    private static final List<String> CATEGORY_KEYS =
            List.of("category", "class", "group", "kind", "type");

    /** Entry fields consulted for where the file came from. */
    private static final List<String> SOURCE_KEYS =
            List.of("source_url", "source", "url", "origin", "attribution");

    /** Entry fields consulted for a licence. Both spellings, since both are written. */
    private static final List<String> LICENCE_KEYS =
            List.of("licence", "license", "copyright", "rights");

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n',
    };

    /** Signature (8) + chunk length (4) + chunk type (4) + width (4) + height (4). */
    private static final int IHDR_HEADER_BYTES = 24;

    private static final String PNG_SUFFIX = ".png";

    private static final String JSON_SUFFIX = ".json";

    private final Path root;

    /** Lowercased, because a folder called Build should be skipped as readily as build. */
    private final Set<String> ignoredDirectories;

    /**
     * Both views of the last scan behind one reference, so a reader can never catch
     * a fresh image list paired with the previous index.
     */
    private volatile Snapshot snapshot = new Snapshot(List.of(), Map.of());

    public RepoScanner(Path root) {
        this(root, DEFAULT_IGNORED_DIRECTORIES);
    }

    public RepoScanner(Path root, Collection<String> ignoredDirectories) {
        this.root = root.toAbsolutePath().normalize();

        Set<String> ignored = new HashSet<>();
        if (ignoredDirectories != null) {
            for (String name : ignoredDirectories) {
                if (name != null && !name.isBlank()) {
                    ignored.add(name.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        this.ignoredDirectories = Set.copyOf(ignored);
    }

    @Override
    public Path root() {
        return root;
    }

    @Override
    public Path resolve(String repoPath) {
        return root.resolve(repoPath);
    }

    @Override
    public List<RepoImage> images() {
        return snapshot.images();
    }

    @Override
    public Optional<RepoImage> byPath(String repoPath) {
        if (repoPath == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.byPath().get(repoPath));
    }

    @Override
    public List<RepoImage> search(String query, int limit) {
        if (limit <= 0) {
            return List.of();
        }

        List<RepoImage> pool = snapshot.images();
        String normalised = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalised.isEmpty()) {
            return List.copyOf(pool.subList(0, Math.min(limit, pool.size())));
        }

        String[] tokens = normalised.split("\\s+");
        List<Scored> hits = new ArrayList<>();
        for (RepoImage image : pool) {
            int rank = rank(image, normalised, tokens);
            if (rank >= 0) {
                hits.add(new Scored(image, rank));
            }
        }

        // Rank first, then path, so a repeated search returns the same order.
        hits.sort(Comparator.comparingInt(Scored::rank).thenComparing(scored -> scored.image().path()));

        List<RepoImage> results = new ArrayList<>(Math.min(limit, hits.size()));
        for (Scored hit : hits) {
            if (results.size() == limit) {
                break;
            }
            results.add(hit.image());
        }
        return List.copyOf(results);
    }

    @Override
    public void rescan() throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("[mcmarkings] repository root is not a directory: " + root);
        }

        List<Scanned> found = new ArrayList<>();
        Set<Path> imageDirectories = new HashSet<>();
        Map<Path, List<Path>> jsonByDirectory = new HashMap<>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (directory.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                if (isIgnored(directory)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (!attributes.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);

                if (fileName.endsWith(JSON_SUFFIX)) {
                    // Filtered on size here rather than later so an oversized file
                    // costs one stat that the walk has already paid for.
                    if (attributes.size() > 0 && attributes.size() <= MAX_METADATA_BYTES) {
                        jsonByDirectory.computeIfAbsent(file.getParent(), key -> new ArrayList<>()).add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
                if (!fileName.endsWith(PNG_SUFFIX)) {
                    return FileVisitResult.CONTINUE;
                }

                String repoPath = toRepoPath(root, file);
                int[] size = readDimensions(file);
                if (size == null) {
                    McMarkingsCompanion.LOGGER.warn("[mcmarkings] skipping unreadable png {}", repoPath);
                    return FileVisitResult.CONTINUE;
                }

                String name = file.getFileName().toString();
                name = name.substring(0, name.length() - PNG_SUFFIX.length());
                found.add(new Scanned(repoPath, name, size[0], size[1]));
                imageDirectories.add(file.getParent());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) {
                McMarkingsCompanion.LOGGER.warn("[mcmarkings] could not read {}: {}", file, exception.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        Map<String, Metadata> metadata = readMetadata(imageDirectories, jsonByDirectory);

        List<RepoImage> images = new ArrayList<>(found.size());
        for (Scanned scanned : found) {
            Metadata entry = metadata.get(scanned.repoPath());
            images.add(new RepoImage(
                    scanned.repoPath(),
                    scanned.name(),
                    scanned.width(),
                    scanned.height(),
                    entry == null ? null : entry.description(),
                    entry == null ? null : entry.reference(),
                    entry == null ? null : entry.licence(),
                    entry == null ? null : entry.category(),
                    entry == null ? null : entry.source()));
        }
        images.sort(Comparator.comparing(RepoImage::path));

        Map<String, RepoImage> index = new LinkedHashMap<>();
        for (RepoImage image : images) {
            index.put(image.path(), image);
        }

        this.snapshot = new Snapshot(List.copyOf(images), Map.copyOf(index));
        McMarkingsCompanion.LOGGER.info("[mcmarkings] scanned {} images under {}", images.size(), root);
    }

    /** Whether a directory should not be walked. Dotted folders go regardless of the list. */
    private boolean isIgnored(Path directory) {
        Path name = directory.getFileName();
        if (name == null) {
            return false;
        }
        String text = name.toString();
        return text.startsWith(".") || ignoredDirectories.contains(text.toLowerCase(Locale.ROOT));
    }

    /**
     * Repo path to whatever the sidecars say about it.
     *
     * <p>Sidecars are found by position rather than by name: a JSON file counts as a
     * candidate when it sits in a directory holding images, or anywhere above one on
     * the way back to the repository root. That covers a sidecar next to its images
     * and a manifest at the root describing the whole tree, without either being a
     * special case. A repository with no images parses no JSON at all.
     */
    private Map<String, Metadata> readMetadata(Set<Path> imageDirectories, Map<Path, List<Path>> jsonByDirectory) {
        if (imageDirectories.isEmpty() || jsonByDirectory.isEmpty()) {
            return Map.of();
        }

        Set<Path> searched = new HashSet<>();
        List<Path> candidates = new ArrayList<>();
        for (Path directory : imageDirectories) {
            for (Path current = directory; current != null && searched.add(current); current = current.getParent()) {
                List<Path> files = jsonByDirectory.get(current);
                if (files != null) {
                    candidates.addAll(files);
                }
                if (current.equals(root)) {
                    break;
                }
            }
        }

        // Deepest first, so a sidecar sitting with the images beats a root manifest
        // that also mentions them. Ties break on the path so two scans of the same
        // tree agree with each other.
        candidates.sort(Comparator.comparingInt(Path::getNameCount).reversed().thenComparing(Path::toString));

        Map<String, Metadata> merged = new HashMap<>();
        for (Path candidate : candidates) {
            readSidecarFile(candidate, merged);
        }
        return merged;
    }

    /**
     * Reads one candidate JSON, treating anything that is not metadata as a
     * non-event. Most repositories carry JSON that has nothing to do with images, so
     * a config, a package manifest or a half-written file must cost a rejection and
     * nothing more.
     */
    private void readSidecarFile(Path file, Map<String, Metadata> target) {
        Path directory = file.getParent();
        if (directory == null) {
            return;
        }
        // Caught wide on purpose. This is the boundary where a file nobody promised
        // was metadata gets parsed, so bad encoding, a truncated document and a
        // number where a string was expected all mean the same thing: not for us.
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            readEntries(reader, root, directory, target);
        } catch (IOException | RuntimeException exception) {
            // Debug, not warn: being handed a JSON that is not metadata is the
            // normal case, and a repository owner should not see a log line for it.
            McMarkingsCompanion.LOGGER.debug(
                    "[mcmarkings] not usable as metadata: {} ({})", file, exception.toString());
        }
    }

    /**
     * Reads one sidecar document held in memory. The seam tests use, so a document
     * shape can be checked without writing a file.
     */
    static void readSidecar(Map<String, Metadata> target, Path root, Path directory, String json) {
        try (Reader reader = new StringReader(json)) {
            readEntries(reader, root.toAbsolutePath().normalize(), directory.toAbsolutePath().normalize(), target);
        } catch (IOException | RuntimeException exception) {
            // Same reasoning as readSidecarFile: rubbish in, nothing out.
            McMarkingsCompanion.LOGGER.debug("[mcmarkings] not usable as metadata ({})", exception.toString());
        }
    }

    /**
     * Pulls metadata entries out of one JSON document, if it holds any.
     *
     * <p>Metadata is recognised by shape, never by file name, so a repository can
     * call its sidecar whatever it likes. The shape is an array of objects that name
     * a file, which is either the whole document or the first top-level property
     * holding one.
     *
     * <p>Read as a token stream rather than as a tree. A package.json or a lockfile
     * is then rejected by walking past its top-level values instead of building an
     * object graph for a document that was never going to match, and a real sidecar
     * stops being read the moment its array runs out.
     */
    private static void readEntries(Reader source, Path root, Path directory, Map<String, Metadata> target)
            throws IOException {
        try (JsonReader json = new JsonReader(source)) {
            JsonToken token = json.peek();
            if (token == JsonToken.BEGIN_ARRAY) {
                readArray(json, root, directory, target);
                return;
            }
            if (token != JsonToken.BEGIN_OBJECT) {
                return;
            }

            json.beginObject();
            while (json.hasNext()) {
                json.nextName();
                if (json.peek() != JsonToken.BEGIN_ARRAY) {
                    json.skipValue();
                    continue;
                }
                // An array that yields nothing was the wrong array, so keep looking.
                // That is more forgiving than taking the first array of objects: a
                // "files": ["a.txt"] sitting above the real entries does not win.
                if (readArray(json, root, directory, target) > 0) {
                    return;
                }
            }
        }
    }

    /** @return how many entries the array contributed */
    private static int readArray(JsonReader json, Path root, Path directory, Map<String, Metadata> target)
            throws IOException {
        int added = 0;
        json.beginArray();
        while (json.hasNext()) {
            if (json.peek() != JsonToken.BEGIN_OBJECT) {
                json.skipValue();
                continue;
            }
            if (readEntry(json, root, directory, target)) {
                added++;
            }
        }
        json.endArray();
        return added;
    }

    /**
     * Reads one entry object.
     *
     * <p>Field names arrive in document order but are wanted in preference order, so
     * each candidate carries the rank of the alias that supplied it and only a better
     * rank replaces it.
     */
    private static boolean readEntry(JsonReader json, Path root, Path directory, Map<String, Metadata> target)
            throws IOException {
        String file = null;
        String description = null;
        String reference = null;
        String licence = null;
        String category = null;
        String source = null;
        int fileRank = Integer.MAX_VALUE;
        int descriptionRank = Integer.MAX_VALUE;
        int referenceRank = Integer.MAX_VALUE;
        int licenceRank = Integer.MAX_VALUE;
        int categoryRank = Integer.MAX_VALUE;
        int sourceRank = Integer.MAX_VALUE;

        json.beginObject();
        while (json.hasNext()) {
            String key = json.nextName().toLowerCase(Locale.ROOT);
            JsonToken token = json.peek();
            // Numbers are read as text so a numeric catalogue code still works.
            if (token != JsonToken.STRING && token != JsonToken.NUMBER) {
                json.skipValue();
                continue;
            }
            String value = json.nextString();

            int rank = FILE_KEYS.indexOf(key);
            if (rank >= 0 && rank < fileRank) {
                fileRank = rank;
                file = value;
            }
            rank = DESCRIPTION_KEYS.indexOf(key);
            if (rank >= 0 && rank < descriptionRank) {
                descriptionRank = rank;
                description = value;
            }
            rank = REFERENCE_KEYS.indexOf(key);
            if (rank >= 0 && rank < referenceRank) {
                referenceRank = rank;
                reference = value;
            }
            rank = LICENCE_KEYS.indexOf(key);
            if (rank >= 0 && rank < licenceRank) {
                licenceRank = rank;
                licence = value;
            }
            rank = CATEGORY_KEYS.indexOf(key);
            if (rank >= 0 && rank < categoryRank) {
                categoryRank = rank;
                category = value;
            }
            rank = SOURCE_KEYS.indexOf(key);
            if (rank >= 0 && rank < sourceRank) {
                sourceRank = rank;
                source = value;
            }
        }
        json.endObject();

        if (file == null || file.isBlank()) {
            return false;
        }
        if (description == null && reference == null && licence == null
                && category == null && source == null) {
            return false;
        }

        String repoPath = toRepoPath(root, directory, file);
        if (repoPath == null) {
            return false;
        }
        target.putIfAbsent(repoPath, new Metadata(description, reference, licence, category, source));
        return true;
    }

    /**
     * Turns an entry's file field into a repo path.
     *
     * <p>Resolved against the JSON's own directory, which makes a bare name in a
     * sidecar and a repo-relative path in a root manifest the same rule. Separators
     * are normalised because a manifest written on Windows should still read here.
     *
     * @return the repo-relative path, or null when the entry points outside the repo
     */
    private static String toRepoPath(Path root, Path directory, String file) {
        Path resolved;
        try {
            resolved = directory.resolve(file.replace('\\', '/')).normalize();
        } catch (RuntimeException exception) {
            return null;
        }
        if (!resolved.startsWith(root)) {
            return null;
        }
        return toRepoPath(root, resolved);
    }

    /**
     * Reads width and height from a file's PNG header, falling back to an ImageIO
     * reader when the header is not a well-formed IHDR. The fallback still only
     * asks the reader for dimensions, which does not decode the image data.
     *
     * @return {@code {width, height}}, or null when the file cannot be read at all
     */
    public static int[] readDimensions(Path file) {
        byte[] header = new byte[IHDR_HEADER_BYTES];
        int read = 0;
        try (InputStream stream = Files.newInputStream(file)) {
            while (read < header.length) {
                int count = stream.read(header, read, header.length - read);
                if (count < 0) {
                    break;
                }
                read += count;
            }
        } catch (IOException exception) {
            McMarkingsCompanion.LOGGER.warn("[mcmarkings] could not open {}: {}", file, exception.getMessage());
            return null;
        }

        int[] parsed = parseIhdr(header, read);
        if (parsed != null) {
            return parsed;
        }
        return readDimensionsWithImageIo(file);
    }

    /**
     * Parses the IHDR chunk of a PNG header.
     *
     * @param header the first bytes of the file
     * @param length how many of those bytes were actually read
     * @return {@code {width, height}}, or null when this is not a valid PNG header
     */
    static int[] parseIhdr(byte[] header, int length) {
        if (header == null || length < IHDR_HEADER_BYTES) {
            return null;
        }
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (header[index] != PNG_SIGNATURE[index]) {
                return null;
            }
        }
        // The first chunk of a PNG must be IHDR, so width and height sit at fixed offsets.
        if (header[12] != 'I' || header[13] != 'H' || header[14] != 'D' || header[15] != 'R') {
            return null;
        }

        int width = readInt(header, 16);
        int height = readInt(header, 20);
        if (width <= 0 || height <= 0) {
            return null;
        }
        return new int[] { width, height };
    }

    private static int[] readDimensionsWithImageIo(Path file) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(file.toFile())) {
            if (stream == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                return new int[] { reader.getWidth(0), reader.getHeight(0) };
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            McMarkingsCompanion.LOGGER.warn("[mcmarkings] imageio could not size {}: {}", file, exception.getMessage());
            return null;
        }
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    static String toRepoPath(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /**
     * Ranks an image against a query. Lower is better, negative means no match.
     *
     * <p>Every token has to appear somewhere in the search key, so "give way line"
     * narrows rather than widens. Ranking then favours whole-query matches on the
     * file name and reference code, which is what people actually type.
     *
     * <p>File names are snake_case in most repositories but nobody types
     * underscores, so the name is also compared with underscores read as spaces.
     * Without that, "give way" would rank no better than any incidental match.
     */
    static int rank(RepoImage image, String query, String[] tokens) {
        String key = image.searchKey();
        for (String token : tokens) {
            if (!key.contains(token)) {
                return -1;
            }
        }

        String name = image.name().toLowerCase(Locale.ROOT);
        String spaced = name.replace('_', ' ');
        String reference = image.reference() == null ? "" : image.reference().toLowerCase(Locale.ROOT);
        if (name.equals(query) || spaced.equals(query) || reference.equals(query)) {
            return 0;
        }
        if (name.startsWith(query) || spaced.startsWith(query) || reference.startsWith(query)) {
            return 1;
        }
        if (name.contains(query) || spaced.contains(query)) {
            return 2;
        }
        return 3;
    }

    record Metadata(String description, String reference, String licence, String category,
            String source) {
    }

    /** One PNG as the walk found it, before any metadata is known. */
    private record Scanned(String repoPath, String name, int width, int height) {
    }

    private record Scored(RepoImage image, int rank) {
    }

    private record Snapshot(List<RepoImage> images, Map<String, RepoImage> byPath) {
    }
}
