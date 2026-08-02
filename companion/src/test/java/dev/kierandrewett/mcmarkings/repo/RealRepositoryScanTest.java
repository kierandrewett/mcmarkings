package dev.kierandrewett.mcmarkings.repo;

import dev.kierandrewett.mcmarkings.core.RepoImage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scanner against this repository's own two metadata files.
 *
 * <p>Everything else about metadata reading is tested against small files written by
 * the test, which proves the parser and proves nothing about whether it fits the
 * shapes real repositories use. These two are real and they differ: the road signs
 * key their catalogue code as "diagram" and the safety signs as "code", and both sit
 * under a "signs" array beside a pile of fields the scanner ignores.
 *
 * <p>Skipped when the images are not beside the mod, so a bare checkout of the
 * companion alone still builds.
 */
class RealRepositoryScanTest {

    private static Path root;

    @BeforeAll
    static void findRepository() {
        root = Path.of("..").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(root.resolve("signs")),
                "the image sets are not beside the mod, skipping");
    }

    private static RepoScanner scanned() throws IOException {
        RepoScanner scanner = new RepoScanner(root, List.of("node_modules", "build", "companion"));
        scanner.rescan();
        return scanner;
    }

    @Test
    void readsBothMetadataShapes() throws IOException {
        RepoScanner scanner = scanned();

        // A road sign: catalogue code under "diagram", licence "OGL v1.0".
        RepoImage sign = scanner.images().stream()
                .filter(image -> image.path().startsWith("signs/"))
                .filter(image -> image.reference() != null && !image.reference().isBlank())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no road sign carried a catalogue code"));

        assertTrue(sign.description() != null && !sign.description().isBlank(),
                sign.path() + " has a code but no description");
        assertTrue(sign.licence() != null && !sign.licence().isBlank(),
                sign.path() + " has no licence, so attribution would not be shown");

        // A safety sign: the same fields under a different key, "code".
        RepoImage safety = scanner.images().stream()
                .filter(image -> image.path().startsWith("iso/"))
                .filter(image -> image.reference() != null && !image.reference().isBlank())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ISO sign carried a catalogue code, "
                        + "so \"code\" is no longer being read as a reference"));

        assertTrue(safety.licence() != null && !safety.licence().isBlank());
    }

    @Test
    void metadataReachesNearlyEveryImage() throws IOException {
        // The point of the whole mechanism. A key list that quietly stopped matching
        // would leave the browser showing file names and nothing else, and every one
        // of these assertions would still pass on one lucky entry.
        RepoScanner scanner = scanned();
        List<RepoImage> catalogued = scanner.images().stream()
                .filter(image -> image.path().startsWith("signs/") || image.path().startsWith("iso/"))
                .toList();

        Assumptions.assumeTrue(catalogued.size() > 100, "not enough catalogued images to judge");

        long described = catalogued.stream().filter(image -> image.description() != null).count();
        long licensed = catalogued.stream().filter(image -> image.licence() != null).count();

        assertTrue(described > catalogued.size() * 0.9,
                described + " of " + catalogued.size() + " images have a description");
        assertTrue(licensed > catalogued.size() * 0.9,
                licensed + " of " + catalogued.size() + " images have a licence");
    }

    @Test
    void searchFindsAnImageByItsCatalogueCode() throws IOException {
        // Documented in the interface as something you can do, and it depends on the
        // reference reaching the index rather than merely being parsed.
        RepoScanner scanner = scanned();
        RepoImage any = scanner.images().stream()
                .filter(image -> image.reference() != null && image.reference().length() > 3)
                .findFirst()
                .orElseThrow();

        List<RepoImage> found = scanner.search(any.reference(), 50);

        assertTrue(found.stream().anyMatch(image -> image.path().equals(any.path())),
                "searching for " + any.reference() + " did not find " + any.path());
    }

    @Test
    void everyImageHasUsableDimensions() throws IOException {
        // The header reader runs over fourteen hundred real PNGs here, which is a
        // better test of it than any file a test could write.
        RepoScanner scanner = scanned();

        List<RepoImage> broken = scanner.images().stream()
                .filter(image -> image.width() <= 0 || image.height() <= 0)
                .toList();

        assertEquals(List.of(), broken, "these images came back with no size");
    }

    @Test
    void categoriesAreReadAndSearchable() throws IOException {
        // Both sets group their images and use different keys for it, "class" for the
        // road signs and "category" for the safety ones. Eleven hundred signs is
        // exactly the size where narrowing to the warnings matters.
        RepoScanner scanner = scanned();

        long categorised = scanner.images().stream()
                .filter(image -> image.path().startsWith("signs/") || image.path().startsWith("iso/"))
                .filter(image -> image.category() != null)
                .count();
        assertTrue(categorised > 1000, "only " + categorised + " images carry a category");

        // The point of indexing it: typing the group finds its members.
        List<RepoImage> warnings = scanner.search("warning", 500);
        assertTrue(warnings.size() > 50,
                "searching for a category found " + warnings.size() + " images");
    }

    @Test
    void anUnderscoredCategoryIsFoundByTypingSpaces() throws IOException {
        // The repository writes safe_condition and nobody types that.
        RepoScanner scanner = scanned();
        Assumptions.assumeTrue(scanner.images().stream()
                .anyMatch(image -> "safe_condition".equals(image.category())),
                "no safe_condition images here");

        assertTrue(scanner.search("safe condition", 200).size() > 10,
                "an underscored category should be findable with spaces");
    }
}
