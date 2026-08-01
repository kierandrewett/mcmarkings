package dev.kierandrewett.mcmarkings.repo;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.core.RepoImage;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Walks the local clone for PNGs and merges what the sign metadata files know
 * about them.
 *
 * <p>The repository holds well over a thousand images, so the scan never decodes
 * pixel data: dimensions come straight out of the PNG IHDR chunk, which is a
 * 24-byte read per file rather than a full inflate. ImageIO is only reached for
 * when a file does not look like a normal PNG.
 *
 * <p>The cached image list is replaced wholesale on {@link #rescan()} and only
 * ever handed out as an immutable copy, so a scan running off the render thread
 * cannot tear a list someone else is iterating.
 */
public class RepoScanner implements RepoService {

    /** Directories that never contain repository images and are expensive to walk. */
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", "companion", "build", "node_modules");

    /** Metadata sidecars, as directory to JSON file. Both share the same shape. */
    private static final Map<String, String> METADATA_SOURCES = Map.of(
            "signs", "signs/signs.json",
            "iso", "iso/iso.json");

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n',
    };

    /** Signature (8) + chunk length (4) + chunk type (4) + width (4) + height (4). */
    private static final int IHDR_HEADER_BYTES = 24;

    private static final Gson GSON = new Gson();

    private final Path root;

    /**
     * Both views of the last scan behind one reference, so a reader can never catch
     * a fresh image list paired with the previous index.
     */
    private volatile Snapshot snapshot = new Snapshot(List.of(), Map.of());

    public RepoScanner(Path root) {
        this.root = root.toAbsolutePath().normalize();
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

        Map<String, Metadata> metadata = readMetadata();
        List<RepoImage> found = new ArrayList<>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                String name = directory.getFileName() == null ? "" : directory.getFileName().toString();
                if (directory.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                if (SKIPPED_DIRECTORIES.contains(name) || name.startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (!attributes.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                String fileName = file.getFileName().toString();
                if (!fileName.toLowerCase(Locale.ROOT).endsWith(".png")) {
                    return FileVisitResult.CONTINUE;
                }

                String repoPath = toRepoPath(root, file);
                int[] size = readDimensions(file);
                if (size == null) {
                    McMarkingsCompanion.LOGGER.warn("[mcmarkings] skipping unreadable png {}", repoPath);
                    return FileVisitResult.CONTINUE;
                }

                String name = fileName.substring(0, fileName.length() - ".png".length());
                Metadata entry = metadata.get(repoPath);
                found.add(new RepoImage(
                        repoPath,
                        name,
                        size[0],
                        size[1],
                        entry == null ? null : entry.description(),
                        entry == null ? null : entry.diagram()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) {
                McMarkingsCompanion.LOGGER.warn("[mcmarkings] could not read {}: {}", file, exception.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        found.sort(Comparator.comparing(RepoImage::path));

        Map<String, RepoImage> index = new LinkedHashMap<>();
        for (RepoImage image : found) {
            index.put(image.path(), image);
        }

        this.snapshot = new Snapshot(List.copyOf(found), Map.copyOf(index));
        McMarkingsCompanion.LOGGER.info("[mcmarkings] scanned {} images under {}", found.size(), root);
    }

    /**
     * Reads width and height from a file's PNG header, falling back to an ImageIO
     * reader when the header is not a well-formed IHDR. The fallback still only
     * asks the reader for dimensions, which does not decode the image data.
     *
     * @return {@code {width, height}}, or null when the file cannot be read at all
     */
    static int[] readDimensions(Path file) {
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
     * file name and diagram code, which is what people actually type.
     *
     * <p>File names are snake_case throughout the repository but nobody types
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
        String diagram = image.diagram() == null ? "" : image.diagram().toLowerCase(Locale.ROOT);
        if (name.equals(query) || spaced.equals(query) || diagram.equals(query)) {
            return 0;
        }
        if (name.startsWith(query) || spaced.startsWith(query) || diagram.startsWith(query)) {
            return 1;
        }
        if (name.contains(query) || spaced.contains(query)) {
            return 2;
        }
        return 3;
    }

    /** Repo path to the metadata the sidecar JSON files carry for it. */
    private Map<String, Metadata> readMetadata() {
        Map<String, Metadata> merged = new HashMap<>();
        for (Map.Entry<String, String> source : METADATA_SOURCES.entrySet()) {
            Path file = root.resolve(source.getValue());
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                readMetadataInto(merged, source.getKey(), json);
            } catch (IOException | JsonSyntaxException | IllegalStateException exception) {
                McMarkingsCompanion.LOGGER.warn(
                        "[mcmarkings] could not read metadata {}: {}", source.getValue(), exception.getMessage());
            }
        }
        return merged;
    }

    /**
     * Reads one sidecar document. Both sidecars are an object with a {@code signs}
     * array whose {@code file} field is a basename inside the sidecar's directory;
     * ISO calls its reference a {@code code} rather than a {@code diagram}.
     */
    static void readMetadataInto(Map<String, Metadata> target, String directory, String json) {
        JsonElement parsed = GSON.fromJson(json, JsonElement.class);
        if (parsed == null || !parsed.isJsonObject()) {
            return;
        }
        JsonElement signs = parsed.getAsJsonObject().get("signs");
        if (signs == null || !signs.isJsonArray()) {
            return;
        }

        JsonArray array = signs.getAsJsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject sign = element.getAsJsonObject();
            String file = string(sign, "file");
            if (file == null || file.isBlank()) {
                continue;
            }
            String diagram = string(sign, "diagram");
            if (diagram == null) {
                diagram = string(sign, "code");
            }
            target.put(directory + "/" + file, new Metadata(string(sign, "description"), diagram));
        }
    }

    private static String string(JsonObject object, String member) {
        JsonElement value = object.get(member);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        return value.getAsString();
    }

    record Metadata(String description, String diagram) {
    }

    private record Scored(RepoImage image, int rank) {
    }

    private record Snapshot(List<RepoImage> images, Map<String, RepoImage> byPath) {
    }
}
