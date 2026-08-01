package dev.kierandrewett.mcmarkings.render;

import dev.kierandrewett.mcmarkings.config.CompanionConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FontRegistryTest {

    /** Places a font might live on this machine. Nothing here is required to exist. */
    private static final List<Path> SYSTEM_FONT_DIRECTORIES = List.of(
            Path.of(System.getProperty("user.home"), ".local", "share", "fonts"),
            Path.of("/usr/share/fonts"),
            Path.of(System.getProperty("java.home"), "lib", "fonts"));

    @Test
    @DisplayName("a nonexistent search path falls back cleanly and says so")
    void missingPathFallsBack() {
        FontRegistry registry = new FontRegistry(List.of("/definitely/not/a/font/directory"));

        assertFalse(registry.hasTransport());
        assertEquals(Optional.empty(), registry.find(FontRegistry.TRANSPORT_HEAVY));

        Font fallback = registry.get(FontRegistry.TRANSPORT_HEAVY);
        assertNotNull(fallback);
        assertTrue(fallback.isBold(), "a heavy face should fall back to something bold");
        assertTrue(fallback.getSize() > 1, "a 1pt font is unusable; it must be derived to a real size");

        assertFalse(registry.warnings().isEmpty());
        assertTrue(registry.warnings().stream().anyMatch(warning -> warning.contains("/definitely/not/a/font")),
                "the warning should name the path that was searched: " + registry.warnings());
        assertTrue(registry.warnings().stream().anyMatch(warning -> warning.contains("Transport Heavy")
                        && warning.contains("fallback")),
                "the warning should name the missing face: " + registry.warnings());
    }

    @Test
    @DisplayName("an empty search path list is not a crash")
    void noSearchPathsFallsBack() {
        FontRegistry registry = new FontRegistry(List.of());

        assertFalse(registry.hasTransport());
        assertNotNull(registry.get(FontRegistry.TRANSPORT_MEDIUM));
        assertFalse(registry.warnings().isEmpty());

        FontRegistry fromNull = new FontRegistry(null);
        assertNotNull(fromNull.get(FontRegistry.TRANSPORT_HEAVY));
    }

    @Test
    @DisplayName("the same fallback instance comes back every time, and warns once")
    void fallbackIsCachedAndWarnsOnce() {
        FontRegistry registry = new FontRegistry(List.of("/definitely/not/a/font/directory"));

        Font first = registry.get(FontRegistry.TRANSPORT_HEAVY);
        Font second = registry.get(FontRegistry.TRANSPORT_HEAVY);
        assertSame(first, second);

        long transportWarnings = registry.warnings().stream()
                .filter(warning -> warning.contains("Transport Heavy"))
                .count();
        assertEquals(1, transportWarnings, "a repeated lookup should not spam the UI: " + registry.warnings());
    }

    @Test
    @DisplayName("a font in a search directory is found by filename and by family name")
    void findsARealFont(@TempDir Path directory) throws IOException {
        Path donor = anyFontFile();
        assumeTrue(donor != null, "no .ttf on this machine to test against");

        Path copy = directory.resolve("Test Face.ttf");
        Files.copy(donor, copy, StandardCopyOption.REPLACE_EXISTING);

        FontRegistry registry = new FontRegistry(List.of(directory.toString()));

        // Filename index: the cheap path that does not parse anything up front.
        Optional<Font> byFileName = registry.find("test-face");
        assertTrue(byFileName.isPresent(), "should match the filename: " + registry.warnings());
        assertTrue(byFileName.get().getSize() > 1);
        assertSame(byFileName.get(), registry.find("Test Face").orElseThrow(), "loaded fonts should be cached");

        // Name index: the lazy full parse, exercised by asking for something the
        // filename cannot answer.
        String family = fontFamilyOf(donor);
        assumeTrue(family != null && !family.isBlank(), "could not read a family name from " + donor);
        assertTrue(registry.find(family).isPresent(), "should match the font's own family name '" + family + "'");

        assertFalse(registry.hasTransport(), "a lone test face is not Transport");
    }

    @Test
    @DisplayName("an unknown name is absent but still gets a usable font")
    void unknownNameStillReturnsAFont(@TempDir Path directory) throws IOException {
        Path donor = anyFontFile();
        assumeTrue(donor != null, "no .ttf on this machine to test against");
        Files.copy(donor, directory.resolve("Test Face.ttf"), StandardCopyOption.REPLACE_EXISTING);

        FontRegistry registry = new FontRegistry(List.of(directory.toString()));

        assertEquals(Optional.empty(), registry.find("no-such-face-at-all"));
        assertEquals(Optional.empty(), registry.find(""));
        assertEquals(Optional.empty(), registry.find(null));

        Font fallback = registry.get("no-such-face-at-all");
        assertNotNull(fallback);
        assertFalse(fallback.isBold(), "nothing in that name suggests a heavy weight");
    }

    @Test
    @DisplayName("the configured search paths either yield Transport or explain why not")
    void configuredPathsAreHonest() {
        // Transport is Crown copyright and not in the repo, so this machine may or may
        // not have it. Both outcomes are correct; silence is not.
        FontRegistry registry = new FontRegistry(new CompanionConfig().fontSearchPaths);

        if (registry.hasTransport()) {
            assertTrue(registry.find(FontRegistry.TRANSPORT_HEAVY).isPresent());
            assertTrue(registry.find(FontRegistry.TRANSPORT_MEDIUM).isPresent());
            assertNotNull(registry.get(FontRegistry.TRANSPORT_HEAVY).getFamily());
        } else {
            assertNotNull(registry.get(FontRegistry.TRANSPORT_HEAVY));
            assertFalse(registry.warnings().isEmpty(), "missing Transport has to be reported to the user");
        }
    }

    @Test
    @DisplayName("lookups never need a display")
    void staysHeadless() {
        // Loading the class is what sets the property, so touch it before looking.
        assertNotNull(new FontRegistry(List.of()).get(FontRegistry.TRANSPORT_HEAVY));
        assertEquals("true", System.getProperty("java.awt.headless"),
                "the registry should default AWT to headless");
    }

    private static Path anyFontFile() {
        for (Path directory : SYSTEM_FONT_DIRECTORIES) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(directory)) {
                Optional<Path> found = walk
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ttf"))
                        .sorted()
                        .findFirst();
                if (found.isPresent()) {
                    return found.get();
                }
            } catch (IOException exception) {
                // Unreadable font directory is not this test's problem; try the next one.
            }
        }
        return null;
    }

    private static String fontFamilyOf(Path file) {
        try {
            return Font.createFont(Font.TRUETYPE_FONT, file.toFile()).getFamily(Locale.ROOT);
        } catch (Exception exception) {
            return null;
        }
    }
}
