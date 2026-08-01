package dev.kierandrewett.mcmarkings.render;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds the typefaces used to letter signs.
 *
 * <p>Transport is Crown copyright and is not redistributable, so it is deliberately
 * absent from this repository. It has to be found on the user's own machine, which
 * means the lookup has to cope with whatever the files are called: on a Linux box
 * they are usually {@code ~/.local/share/fonts/Transport Heavy.ttf} and
 * {@code Transport Medium.ttf}, but the internal names disagree with each other
 * (Heavy reports family "Transport Heavy", Medium reports family "Transport" with
 * face name "Transport Medium"). Matching therefore runs against filenames, family
 * names, face names and PostScript names, all with punctuation and case stripped.
 *
 * <p>Resolution is two-phase on purpose. The constructor only walks the directories
 * and records filenames, which is cheap; it does not parse anything. Font files are
 * parsed lazily, and the whole set is only parsed when a lookup misses the filename
 * index. That keeps the common case (Transport, named sensibly) fast while still
 * finding a font that has been renamed to something unhelpful.
 *
 * <p>Missing Transport is not an error. {@link #get(String)} always returns a usable
 * font and records a warning that the UI can show, because a sign lettered in a
 * fallback sans is still worth previewing.
 */
public final class FontRegistry {

    public static final String TRANSPORT_HEAVY = "transport-heavy";
    public static final String TRANSPORT_MEDIUM = "transport-medium";

    /** Faces that together mean "real signage lettering is available". */
    private static final List<String> TRANSPORT_FACES = List.of(TRANSPORT_HEAVY, TRANSPORT_MEDIUM);

    private static final Set<String> FONT_EXTENSIONS = Set.of(".ttf", ".otf");

    /**
     * {@code Font.createFont} hands back a 1pt font, which draws as a smear if a
     * caller forgets to derive it. Everything leaving this class is normalised to a
     * readable size; callers still call {@code deriveFont(size)} for real work.
     */
    private static final float BASE_SIZE = 12.0f;

    static {
        // Minecraft draws through GLFW and never needs AWT's display, but AWT will
        // happily attach to one if asked - on macOS that bounces a dock icon, and on
        // a headless build box it throws. Default to headless while still respecting
        // an explicit choice, so a mod that genuinely wants AWT dialogs can opt out.
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
    }

    private final List<Path> searchRoots;
    private final List<Path> fontFiles = new ArrayList<>();
    private final Map<String, Path> byFileName = new HashMap<>();
    private final Map<String, Path> byFontName = new HashMap<>();
    private final Map<String, Font> loaded = new HashMap<>();
    private final Map<String, Font> fallbacks = new HashMap<>();
    private final LinkedHashSet<String> warnings = new LinkedHashSet<>();

    private boolean fontNamesIndexed;

    public FontRegistry(List<String> searchPaths) {
        this.searchRoots = searchPaths == null
                ? List.of()
                : searchPaths.stream()
                        .filter(path -> path != null && !path.isBlank())
                        .map(Path::of)
                        .toList();
        scanFileNames();
    }

    /** The font behind a logical name, or empty if nothing on disk matches it. */
    public Optional<Font> find(String logicalName) {
        String key = normalise(logicalName);
        if (key.isEmpty()) {
            return Optional.empty();
        }

        synchronized (this) {
            Font cached = loaded.get(key);
            if (cached != null) {
                return Optional.of(cached);
            }

            Path file = resolveFile(key);
            if (file == null) {
                return Optional.empty();
            }

            Optional<Font> font = loadFont(file);
            font.ifPresent(value -> loaded.put(key, value));
            return font;
        }
    }

    /** Never null. Falls back to a system sans and records a warning if the real face is missing. */
    public Font get(String logicalName) {
        Optional<Font> found = find(logicalName);
        if (found.isPresent()) {
            return found.get();
        }

        synchronized (this) {
            String key = normalise(logicalName);
            Font existing = fallbacks.get(key);
            if (existing != null) {
                return existing;
            }

            warn(displayName(logicalName) + " not found in " + describeSearchRoots()
                    + "; using fallback, lettering will not match real signage.");

            // Heavy and bold faces carry the weight on a sign, so keep at least that
            // much of the intent when substituting.
            int style = key.contains("heavy") || key.contains("bold") ? Font.BOLD : Font.PLAIN;
            Font fallback = new Font(Font.SANS_SERIF, style, (int) BASE_SIZE);
            fallbacks.put(key, fallback);
            return fallback;
        }
    }

    /** True only when every Transport face resolves; a partial set still cannot letter a real sign. */
    public boolean hasTransport() {
        return TRANSPORT_FACES.stream().allMatch(face -> find(face).isPresent());
    }

    /** Human-readable problems worth surfacing in the UI, in the order they were found. */
    public List<String> warnings() {
        synchronized (this) {
            return List.copyOf(warnings);
        }
    }

    private void scanFileNames() {
        if (searchRoots.isEmpty()) {
            warn("no font search paths configured; Transport cannot be found.");
            return;
        }

        for (Path root : searchRoots) {
            if (!Files.isDirectory(root)) {
                warn("font search path does not exist: " + root);
                continue;
            }
            collectFrom(root);
        }

        if (fontFiles.isEmpty()) {
            warn("no .ttf or .otf files found in " + describeSearchRoots() + ".");
        }
    }

    private void collectFrom(Path root) {
        // Files.walk does not follow symlinks, so a self-referential font directory
        // cannot spin here.
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> found = walk
                    .filter(Files::isRegularFile)
                    .filter(FontRegistry::hasFontExtension)
                    .sorted()
                    .toList();

            for (Path file : found) {
                fontFiles.add(file);
                // First file wins, and the sort above makes "first" reproducible, so a
                // duplicated face does not change which one the UI ends up drawing.
                byFileName.putIfAbsent(normalise(stem(file)), file);
            }
        } catch (IOException exception) {
            warn("could not read font directory " + root + ": " + exception.getMessage());
        }
    }

    private Path resolveFile(String key) {
        Path direct = byFileName.get(key);
        if (direct != null) {
            return direct;
        }

        indexFontNames();

        Path named = byFontName.get(key);
        if (named != null) {
            return named;
        }

        // Last resort for files like "Transport Heavy Regular.ttf" or "TransportHeavy-v2".
        // Shortest match first, so the closest name wins rather than an arbitrary one.
        return Stream.concat(byFileName.entrySet().stream(), byFontName.entrySet().stream())
                .filter(entry -> entry.getKey().contains(key))
                .min(Comparator.comparingInt((Map.Entry<String, Path> entry) -> entry.getKey().length())
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    /**
     * Parses every discovered font once to index family, face and PostScript names.
     *
     * <p>This is the slow path - it is a full parse of every font on the machine - so
     * it only runs when a filename lookup has already missed, and only once.
     */
    private void indexFontNames() {
        if (fontNamesIndexed) {
            return;
        }
        fontNamesIndexed = true;

        for (Path file : fontFiles) {
            Optional<Font> font = loadFont(file);
            if (font.isEmpty()) {
                continue;
            }

            Font value = font.get();
            for (String name : List.of(
                    value.getFamily(Locale.ROOT),
                    value.getFontName(Locale.ROOT),
                    value.getPSName(),
                    value.getFamily(Locale.ROOT) + " " + styleSuffix(value))) {
                String key = normalise(name);
                if (!key.isEmpty()) {
                    byFontName.putIfAbsent(key, file);
                }
            }
        }
    }

    private Optional<Font> loadFont(Path file) {
        try {
            // TRUETYPE_FONT covers OpenType too; TYPE1_FONT is the only other format
            // createFont knows, and no signage face ships as Type 1.
            return Optional.of(Font.createFont(Font.TRUETYPE_FONT, file.toFile()).deriveFont(BASE_SIZE));
        } catch (FontFormatException | IOException exception) {
            warn("could not load font " + file + ": " + exception.getMessage());
            return Optional.empty();
        }
    }

    private void warn(String message) {
        synchronized (this) {
            warnings.add("[mcmarkings] " + message);
        }
    }

    private String describeSearchRoots() {
        if (searchRoots.isEmpty()) {
            return "(no search paths)";
        }
        return String.join(", ", searchRoots.stream().map(Path::toString).toList());
    }

    private static boolean hasFontExtension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return FONT_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static String styleSuffix(Font font) {
        if (font.isBold() && font.isItalic()) {
            return "bold italic";
        }
        if (font.isBold()) {
            return "bold";
        }
        if (font.isItalic()) {
            return "italic";
        }
        return "regular";
    }

    /** Squashes "Transport Heavy", "transport-heavy" and "TransportHeavy.ttf" onto one key. */
    private static String normalise(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (char character : value.toCharArray()) {
            if (Character.isLetterOrDigit(character)) {
                builder.append(Character.toLowerCase(character));
            }
        }
        return builder.toString();
    }

    /** "transport-heavy" reads back as "Transport Heavy" in a warning. */
    private static String displayName(String logicalName) {
        if (logicalName == null || logicalName.isBlank()) {
            return "font";
        }

        String[] words = logicalName.trim().split("[-_\\s]+");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return builder.isEmpty() ? logicalName : builder.toString();
    }
}
