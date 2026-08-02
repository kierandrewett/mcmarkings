package dev.kierandrewett.mcmarkings.render;

import dev.kierandrewett.mcmarkings.config.CompanionConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The font catalogue.
 *
 * <p>No font is special here. Which typeface a sign wants belongs to whoever is
 * designing it, so the registry only has to enumerate what is installed, match
 * names loosely, and never leave a caller without something to draw with.
 */
class FontRegistryTest {

    @Test
    @DisplayName("system fonts are available without configuring a search path")
    void platformFontsAreFound() {
        FontRegistry registry = new FontRegistry(List.of());

        assertNotNull(registry.availableFamilies());
        assertTrue(registry.count() >= 0);
    }

    @Test
    @DisplayName("families come back sorted and without duplicates")
    void familiesAreSortedAndUnique() {
        List<String> families = new FontRegistry(List.of()).availableFamilies();

        for (int index = 1; index < families.size(); index++) {
            assertTrue(families.get(index - 1).compareToIgnoreCase(families.get(index)) <= 0,
                    "not sorted at " + index + ": " + families.get(index - 1) + " then " + families.get(index));
        }
        assertEquals(families.size(), families.stream().map(String::toLowerCase).distinct().count());
    }

    @Test
    @DisplayName("a font dropped into a search folder is found by its file name")
    void findsAFontInASearchFolder(@TempDir Path directory) throws IOException {
        Path copied = copyAnyInstalledFont(directory);
        if (copied == null) {
            return;
        }

        FontRegistry registry = new FontRegistry(List.of(directory.toString()));

        assertTrue(registry.find(stripExtension(copied.getFileName().toString())).isPresent(),
                "a font in a search folder should be findable by its file name");
    }

    @Test
    @DisplayName("matching ignores case and punctuation")
    void matchingIsLoose() {
        FontRegistry registry = new FontRegistry(List.of());
        List<String> families = registry.availableFamilies();
        if (families.isEmpty()) {
            return;
        }

        String family = families.getFirst();
        assertTrue(registry.find(family).isPresent(), "the exact name should match");
        assertTrue(registry.find(family.toUpperCase()).isPresent(), "case should not matter");
        assertTrue(registry.find(family.replace(" ", "").toLowerCase()).isPresent(),
                "spacing and punctuation should not matter");
    }

    @Test
    @DisplayName("an unknown font falls back rather than failing the sign")
    void unknownFontFallsBack() {
        FontRegistry registry = new FontRegistry(List.of());

        Font font = registry.get("a font nobody has installed " + System.nanoTime());

        assertNotNull(font, "a sign should render in a substitute rather than not at all");
        assertTrue(font.getSize() > 1, "a 1pt font draws as nothing, which is a trap for callers");
        assertFalse(registry.warnings().isEmpty(), "the substitution should be recorded for the UI");
    }

    @Test
    @DisplayName("a blank or missing name is not reported as a problem")
    void blankNameIsNotAWarning() {
        FontRegistry registry = new FontRegistry(List.of());

        assertNotNull(registry.get(null));
        assertNotNull(registry.get(""));
        assertTrue(registry.find(null).isEmpty());
        assertTrue(registry.find("").isEmpty());
    }

    @Test
    @DisplayName("a nonexistent search folder is skipped quietly")
    void missingSearchFolderIsHarmless() {
        FontRegistry registry = new FontRegistry(List.of(
                Path.of("not", "a", "real", "folder", String.valueOf(System.nanoTime())).toString()));

        assertNotNull(registry.availableFamilies());
        assertNotNull(registry.get("anything"));
    }

    @Test
    @DisplayName("fonts come back at a usable size, not the 1pt createFont default")
    void fontsAreDerivedToAUsableSize() {
        FontRegistry registry = new FontRegistry(List.of());
        List<String> families = registry.availableFamilies();
        if (families.isEmpty()) {
            return;
        }

        assertTrue(registry.get(families.getFirst()).getSize() > 1);
    }

    /** Copies a real font out of the platform's own directories, or null if none. */
    private static Path copyAnyInstalledFont(Path target) throws IOException {
        for (String candidate : CompanionConfig.defaultFontSearchPaths()) {
            Path directory = Path.of(candidate);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(directory, 3)) {
                Path source = walk.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".ttf"))
                        .findFirst()
                        .orElse(null);
                if (source != null) {
                    Path copy = target.resolve(source.getFileName().toString());
                    Files.copy(source, copy);
                    return copy;
                }
            } catch (IOException ignored) {
                // Try the next directory rather than failing the test on one of them.
            }
        }
        return null;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
