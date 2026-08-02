package dev.kierandrewett.mcmarkings.core;

import java.util.List;

/**
 * Fitting a list of problems into one line.
 *
 * <p>Three places had written this out independently: opening a document, opening a
 * placed map, and rendering in the editor. Two of them had already drifted apart
 * once and were pulled together; the third turned up later, which is the argument
 * for a home rather than another merge.
 *
 * <p>The count is the part that matters. "unknown layer kind: arc" reads as one
 * stray layer and "unknown layer kind: arc (+3 more)" reads as a document from a
 * newer build, and those lead to different decisions. A caller that can show the
 * rest, in a tooltip or a detail line, should: a count with no way to see what it
 * counts is a number, not information.
 *
 * <p>Truncation lives here too, because the two implementations disagreed. One cut
 * to the limit including the ellipsis and the other cut to the limit and then added
 * it, so the same number meant two different widths depending on which screen you
 * were looking at.
 */
public final class Summary {

    private Summary() {
    }

    /**
     * Shortens to at most {@code limit} characters, ellipsis included.
     *
     * <p>Including it is what makes the limit mean a width, which is the only reason
     * a caller picks a number at all.
     */
    public static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, Math.max(1, limit - 3)) + "...";
    }

    /**
     * The first problem, shortened, and how many others there are.
     *
     * <p>Empty for an empty list, so a caller can use it as the test for whether
     * there is anything to say.
     */
    public static String of(List<String> problems, int limit) {
        if (problems == null || problems.isEmpty()) {
            return "";
        }
        String more = problems.size() > 1 ? " (+" + (problems.size() - 1) + " more)" : "";
        return truncate(problems.getFirst(), limit) + more;
    }
}
