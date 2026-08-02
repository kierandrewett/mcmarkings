package dev.kierandrewett.mcmarkings.gui.imgui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The interface can draw the characters this repository actually contains.
 *
 * <p>Dear ImGui's default atlas is Basic Latin and Latin-1. The sign descriptions
 * here hold three thousand nine hundred en dashes at U+2013, which is above that, so
 * every tooltip and detail pane showing one drew a missing-glyph box.
 *
 * <p>It went unnoticed because the two paths differ. The document renderer draws
 * through Java2D and had no trouble with any of it, so what gets published was always
 * right and only the interface reading it was wrong.
 *
 * <p>Checked against the repository rather than against a list I thought of. The en
 * dash is not a character I would have guessed at; it is simply what the descriptions
 * were written with.
 */
class GlyphRangeTest {

    private static boolean covers(short[] ranges, int codePoint) {
        for (int at = 0; at + 1 < ranges.length; at += 2) {
            int from = ranges[at] & 0xFFFF;
            int to = ranges[at + 1] & 0xFFFF;
            if (from == 0) {
                return false;
            }
            if (codePoint >= from && codePoint <= to) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("every character in this repository's metadata can be drawn")
    void theRepositorysOwnCharactersAreCovered() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(root.resolve("signs")),
                "run from a checkout with the images present");

        short[] ranges = ImGuiFonts.glyphRanges();
        java.util.SortedSet<Integer> missing = new TreeSet<>();

        try (Stream<Path> files = Files.walk(root, 3)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json"))
                    .filter(path -> !path.toString().contains("companion"))
                    .filter(path -> !path.toString().contains("node_modules"))
                    .toList()) {
                for (int codePoint : Files.readString(file, StandardCharsets.UTF_8).codePoints().toArray()) {
                    if (codePoint >= 0x20 && !covers(ranges, codePoint)) {
                        missing.add(codePoint);
                    }
                }
            }
        }

        List<String> named = new ArrayList<>();
        missing.stream().limit(10).forEach(codePoint -> named.add(
                String.format("U+%04X %s", codePoint, Character.getName(codePoint))));
        assertTrue(missing.isEmpty(),
                () -> "the interface cannot draw " + missing.size()
                        + " characters this repository uses: " + named);
    }

    /**
     * The specific one, named. A count that drifts to zero because the metadata was
     * rewritten would leave this test passing and say nothing about the range.
     */
    @Test
    @DisplayName("the en dash the descriptions are written with is covered")
    void theEnDashIsCovered() {
        assertTrue(covers(ImGuiFonts.glyphRanges(), 0x2013), "en dash");
        assertTrue(covers(ImGuiFonts.glyphRanges(), 0x2014), "em dash");
    }

    /**
     * Welsh, because this is a British sign set and there are Wales variants of most
     * of them. The circumflexed w and y sit above Latin-1, unlike the French and
     * German accents, so a range that stops there covers half of Europe and not this.
     */
    @Test
    @DisplayName("Welsh circumflexes are covered, not only the Latin-1 accents")
    void welshIsCovered() {
        short[] ranges = ImGuiFonts.glyphRanges();
        assertTrue(covers(ranges, 0x0175), "w with circumflex");
        assertTrue(covers(ranges, 0x0177), "y with circumflex");
        assertTrue(covers(ranges, 0x00F4), "o with circumflex, which was already fine");
    }

    @Test
    @DisplayName("the range is the shape Dear ImGui reads")
    void theRangeIsWellFormed() {
        short[] ranges = ImGuiFonts.glyphRanges();

        assertEquals(0, ranges[ranges.length - 1], "the array must end in a zero or it is read past");
        assertTrue(ranges.length % 2 == 1, "pairs, then the terminator");

        for (int at = 0; at + 1 < ranges.length - 1; at += 2) {
            int from = ranges[at] & 0xFFFF;
            int to = ranges[at + 1] & 0xFFFF;
            assertTrue(from > 0 && to >= from,
                    "range " + at / 2 + " runs backwards or starts at zero: " + from + ".." + to);
        }
    }
}
