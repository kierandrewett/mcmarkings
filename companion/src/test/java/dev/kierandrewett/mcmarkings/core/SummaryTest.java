package dev.kierandrewett.mcmarkings.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One line out of a list of problems.
 *
 * <p>Written out independently in three places: opening a document, opening a
 * placed map, and rendering in the editor. Two had already drifted and been pulled
 * together, and the third turned up afterwards.
 */
class SummaryTest {

    @Test
    @DisplayName("one problem is not counted, several are")
    void countsWhatItIsNotShowing() {
        assertEquals("bad colour", Summary.of(List.of("bad colour"), 70));
        assertEquals("bad colour (+2 more)", Summary.of(List.of("bad colour", "a", "b"), 70));
    }

    @Test
    void saysNothingWhenThereIsNothingToSay() {
        assertEquals("", Summary.of(List.of(), 70));
        assertEquals("", Summary.of(null, 70));
    }

    /**
     * The reason truncation moved here as well. One implementation cut to the limit
     * including the ellipsis and the other cut to the limit and then added it, so
     * the same number meant two different widths depending on the screen.
     */
    @Test
    @DisplayName("the limit is a width, ellipsis included")
    void theLimitIncludesTheEllipsis() {
        String shortened = Summary.truncate("x".repeat(200), 40);
        assertEquals(40, shortened.length(), () -> "got " + shortened.length() + ": " + shortened);
        assertTrue(shortened.endsWith("..."), shortened);
    }

    @Test
    void shortTextIsLeftAlone() {
        assertEquals("fine", Summary.truncate("fine", 40));
        assertEquals("", Summary.truncate(null, 40));
    }

    /**
     * The count has to survive the shortening. Truncating the whole line instead of
     * the first problem would cut "(+3 more)" off the end, which loses the one part
     * that changes what someone does next.
     */
    @Test
    @DisplayName("shortening the problem never eats the count")
    void theCountSurvivesTruncation() {
        String line = Summary.of(List.of("x".repeat(200), "a", "b", "c"), 40);
        assertTrue(line.endsWith("(+3 more)"), line);
        assertFalse(line.contains("..." + "(+"), "the ellipsis should shorten the problem, not the line: " + line);
    }
}
