package dev.kierandrewett.mcmarkings.repo;

import dev.kierandrewett.mcmarkings.core.RepoImage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a grid cell can say in the room it has.
 *
 * <p>Measured against the real set, because the fault is a property of the metadata
 * rather than of the code. The cells used to caption themselves with the description,
 * which reads well in a detail pane and is the wrong string for a label: 84% of them
 * are too long for a cell, and the part that survives is the part the neighbours
 * share. Four hundred and sixty four begin "UK traffic sign".
 */
class CaptionLabelTest {

    /** About what a cell holds at a typical window size. */
    private static final int CELL_CHARACTERS = 21;

    private static List<RepoImage> images;

    @BeforeAll
    static void scan() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(root.resolve("signs")),
                "run from a checkout with the images present");
        RepoScanner scanner = new RepoScanner(root, List.of("node_modules", "build", "companion"));
        scanner.rescan();
        images = scanner.search("", 4000);
    }

    private static String asShown(String label) {
        return label.length() <= CELL_CHARACTERS ? label : label.substring(0, CELL_CHARACTERS);
    }

    /**
     * The measure that matters is not length, it is whether two cells can be told
     * apart. A caption every neighbour also carries is worse than none: it takes the
     * room and answers nothing.
     */
    @Test
    @DisplayName("captions distinguish neighbouring cells far better than descriptions would")
    void captionsAreDistinguishing() {
        long distinctShort = images.stream().map(image -> asShown(image.shortName())).distinct().count();
        long distinctDescription = images.stream().map(image -> asShown(image.displayName())).distinct().count();

        // The measured figures are 1077 against 679, a shade under sixty per cent more.
        // I asserted double first, from an impression rather than a count, and it
        // failed: the gain is real and it is not that large. Stated as numbers so a
        // change here shows up as a figure moving rather than a threshold I picked to
        // fit whatever happened to pass.
        assertTrue(distinctShort >= 1000,
                () -> "short names give only " + distinctShort + " distinct captions across "
                        + images.size() + " images");
        assertTrue(distinctDescription <= 750,
                () -> "descriptions now give " + distinctDescription + " distinct captions, so this "
                        + "repository's metadata has changed shape and the comparison is stale");
        assertTrue(distinctShort > distinctDescription * 1.4,
                () -> distinctShort + " against " + distinctDescription + " is no longer worth the change");
    }

    @Test
    @DisplayName("a caption is never the same boilerplate across hundreds of images")
    void noCaptionSwampsTheGrid() {
        java.util.Map<String, Long> counts = new java.util.HashMap<>();
        for (RepoImage image : images) {
            counts.merge(asShown(image.shortName()), 1L, Long::sum);
        }
        long worst = counts.values().stream().mapToLong(Long::longValue).max().orElse(0);

        assertTrue(worst < 20, () -> "one caption covers " + worst + " images: "
                + counts.entrySet().stream().filter(e -> e.getValue() == worst).findFirst().orElseThrow());
    }

    @Test
    @DisplayName("the description is still what a tooltip and detail pane get")
    void proseIsStillAvailable() {
        RepoImage described = images.stream()
                .filter(image -> !image.displayName().equals(image.shortName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no image in this repository carries a description"));

        assertTrue(described.displayName().length() > 0);
        assertEquals(described.name().replace('_', ' '), described.shortName());
    }

    /**
     * That the caption uses it, not merely that it would be better if it did.
     *
     * <p>Written after putting the call site back to the description and watching
     * every test above stay green. They compare two strings as data, which is a fact
     * about the metadata and not about the interface, and the drawing needs an ImGui
     * context no test here has. This is the third time this session that a check of
     * the calculation left the call site unguarded, so it gets the same structural
     * treatment as the overlay halos.
     */
    @Test
    @DisplayName("the grid caption is drawn from the short name")
    void theCaptionActuallyUsesIt() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/kierandrewett/mcmarkings/gui/imgui/panel/ImageBrowserPanel.java"));

        int start = source.indexOf("private void drawCaption(");
        assertTrue(start > 0, "drawCaption has gone");
        String body = source.substring(start, source.indexOf("\n    }", start));

        assertTrue(body.contains("image.shortName()"),
                "the caption is not using shortName, so the grid is captioned with prose again");
        assertTrue(!body.contains("image.displayName()"),
                "the caption is back on the description, which does not fit a cell");
    }
}
