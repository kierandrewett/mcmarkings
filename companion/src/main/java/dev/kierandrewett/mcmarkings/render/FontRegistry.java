package dev.kierandrewett.mcmarkings.render;

import dev.kierandrewett.mcmarkings.McMarkingsCompanion;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Every font this machine can offer, addressable by name.
 *
 * <p>Deliberately opinionless about which fonts matter. Which typeface a sign
 * should be set in belongs to whoever is designing the sign, not to this mod, so
 * nothing here is special-cased, required, or warned about by name. The job is to
 * find what is installed, say what it found, and resolve whatever a generator asks
 * for.
 *
 * <p>Names are matched loosely, because one font is known by several. A file called
 * {@code Foo Heavy.ttf} may report family "Foo" with face name "Foo Heavy", or
 * family "Foo Heavy" outright. Filename, family, face and PostScript names are all
 * indexed with case and punctuation stripped, so any of them work.
 */
public final class FontRegistry {

    /** Used when a generator asks for nothing in particular. */
    public static final String DEFAULT_FONT = "sans-serif";

    private final List<String> searchPaths;

    /** Normalised name to font. Insertion-ordered so the first registered wins. */
    private final Map<String, Font> byName = new LinkedHashMap<>();

    /** Where a font came from, for callers that need the file rather than the Font. */
    private final Map<String, Path> fileByName = new LinkedHashMap<>();

    /** Display names, sorted, for showing the user what is available. */
    private final TreeSet<String> families = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    private final List<String> warnings = new ArrayList<>();

    private boolean scanned;

    public FontRegistry(List<String> searchPaths) {
        this.searchPaths = searchPaths == null ? List.of() : List.copyOf(searchPaths);
    }

    /** Every font family available, sorted. Suitable for a dropdown. */
    public synchronized List<String> availableFamilies() {
        ensureScanned();
        return List.copyOf(families);
    }

    public synchronized int count() {
        ensureScanned();
        return families.size();
    }

    /** Resolves a font by any of its names, or empty when nothing matches. */
    public synchronized Optional<Font> find(String name) {
        ensureScanned();
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }

        String key = normalise(name);
        Font exact = byName.get(key);
        if (exact != null) {
            return Optional.of(exact);
        }

        // Shortest containing match, so a request for "foo" lands on "Foo" rather
        // than "Foo Condensed Italic" when both are installed.
        return byName.entrySet().stream()
                .filter(entry -> entry.getKey().contains(key))
                .min((left, right) -> Integer.compare(left.getKey().length(), right.getKey().length()))
                .map(Map.Entry::getValue);
    }

    /**
     * Resolves a font, falling back to a stock sans rather than failing.
     *
     * <p>A font that is not installed should cost the intended lettering, not the
     * whole sign, so a generator naming something unavailable still renders and the
     * substitution is recorded for the UI to mention.
     */
    public synchronized Font get(String name) {
        return find(name).orElseGet(() -> {
            if (name != null && !name.isBlank() && !DEFAULT_FONT.equalsIgnoreCase(name)) {
                noteOnce("Font \"" + name + "\" is not installed, so a default was used instead.");
            }
            return new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        });
    }

    /** True when the named font is genuinely available, without falling back. */
    public synchronized boolean has(String name) {
        return find(name).isPresent();
    }

    /**
     * Any readable scalable font file, preferring a plain sans.
     *
     * <p>A last resort for callers that need some font file and do not care which.
     */
    /**
     * Faces that are not the one to read an interface in.
     *
     * <p>The preference list matches on a prefix, so "liberationsans" matched
     * LiberationSans-BoldItalic just as happily as LiberationSans-Regular, and which
     * one came back was whichever the map handed over first. On this machine that was
     * the bold italic, so the entire interface was set in bold italic and the first
     * thing anyone said about it was that the font is hard to read.
     *
     * <p>Weight and slant both, because a bold face at interface size is only
     * slightly better than an italic one and neither is what a body font should be.
     */
    private static final List<String> NOT_A_BODY_FACE = List.of(
            "italic", "oblique", "bold", "black", "heavy", "light", "thin", "medium",
            "semibold", "demi", "condensed", "narrow", "extended");

    /**
     * Monospace is rejected when it was not asked for, and kept when it was.
     *
     * <p>The fault this came from was "dejavusans" matching "DejaVu Sans Mono for
     * Powerline", so a family asked for by name must not pick up a monospace cousin.
     * But a family whose name is itself monospace, Monocraft being the one actually
     * wanted here, has to survive the same filter.
     */
    private static boolean isBodyFace(Path path, String wantedFamily) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".ttf")) {
            return false;
        }
        if (name.contains("mono") && !wantedFamily.contains("mono")) {
            return false;
        }
        return NOT_A_BODY_FACE.stream().noneMatch(name::contains);
    }

    public synchronized Optional<Path> anyReadableFontFile() {
        ensureScanned();
        // Monocraft first, because it is the Minecraft look as an actual scalable
        // font. Minecraft's own is a bitmap in ascii.png and unicode pages, which
        // addFontFromFileTTF cannot load, so this is the way to have it without
        // writing an atlas builder. Absent on a machine that has not installed it,
        // and then the ordinary sans faces follow as before.
        for (String preferred : List.of("monocraft", "dejavusans", "liberationsans", "notosans",
                "arial", "helvetica", "roboto", "segoeui", "cantarell", "ubuntu")) {
            // The plain face first. Failing that, any face of this family at all, which
            // is still a better answer than the next family down.
            Optional<Path> plain = fileByName.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(preferred))
                    .map(Map.Entry::getValue)
                    .filter(path -> isBodyFace(path, preferred))
                    .sorted(java.util.Comparator.comparingInt(path -> path.toString().length()))
                    .findFirst();
            if (plain.isPresent()) {
                return plain;
            }
        }
        return fileByName.values().stream()
                .filter(path -> isBodyFace(path, ""))
                .sorted(java.util.Comparator.comparingInt(path -> path.toString().length()))
                .findFirst();
    }

    public synchronized List<String> warnings() {
        return List.copyOf(warnings);
    }

    /**
     * Indexes the configured directories plus whatever the platform already knows
     * about, so system-installed fonts work without configuring anything.
     */
    private void ensureScanned() {
        if (scanned) {
            return;
        }
        scanned = true;

        for (String path : searchPaths) {
            indexDirectory(Path.of(path));
        }
        indexPlatformFonts();

        McMarkingsCompanion.LOGGER.info("[mcmarkings] {} font family(ies) available", families.size());
    }

    private void indexDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> files = Files.walk(directory, 4)) {
            files.filter(Files::isRegularFile)
                    .filter(FontRegistry::isFontFile)
                    .forEach(this::indexFile);
        } catch (IOException | RuntimeException exception) {
            McMarkingsCompanion.LOGGER.debug("[mcmarkings] could not scan fonts in {}", directory, exception);
        }
    }

    private void indexFile(Path file) {
        try {
            Font font = Font.createFont(Font.TRUETYPE_FONT, file.toFile());

            for (String name : List.of(stripExtension(file.getFileName().toString()),
                    font.getFamily(Locale.ROOT), font.getFontName(Locale.ROOT), font.getPSName())) {
                register(name, font);
                if (name != null && !name.isBlank()) {
                    fileByName.putIfAbsent(normalise(name), file);
                }
            }

            families.add(font.getFamily(Locale.ROOT));
        } catch (Exception exception) {
            // One font that will not parse is not worth failing the scan over.
            McMarkingsCompanion.LOGGER.debug("[mcmarkings] skipped unreadable font {}", file, exception);
        }
    }

    /**
     * Adds the fonts the JVM already resolves, so anything installed the normal way
     * on any platform is available without configuring a search path.
     */
    private void indexPlatformFonts() {
        try {
            GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
            for (Font font : environment.getAllFonts()) {
                register(font.getFamily(Locale.ROOT), font);
                register(font.getFontName(Locale.ROOT), font);
                register(font.getPSName(), font);
                families.add(font.getFamily(Locale.ROOT));
            }
        } catch (Throwable error) {
            // Headless, or a broken font configuration. The directory scan still
            // stands, so this is a smaller catalogue rather than a failure.
            noteOnce("Could not read the system font list: " + error.getMessage());
        }
    }

    private void register(String name, Font font) {
        if (name == null || name.isBlank()) {
            return;
        }
        // Font.createFont hands back a 1pt font, which renders as nothing at all if
        // a caller forgets to derive it to a real size.
        byName.putIfAbsent(normalise(name), font.deriveFont(12.0f));
    }

    private void noteOnce(String message) {
        if (!warnings.contains(message)) {
            warnings.add(message);
        }
    }

    private static boolean isFontFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".ttf") || name.endsWith(".otf") || name.endsWith(".ttc");
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String normalise(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
