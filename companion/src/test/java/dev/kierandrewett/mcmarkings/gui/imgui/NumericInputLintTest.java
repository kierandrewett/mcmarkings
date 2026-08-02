package dev.kierandrewett.mcmarkings.gui.imgui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A number typed into the interface is bounded before anything multiplies it.
 *
 * <p>Three separate crashes came out of this in a week, each the same shape and each
 * found on its own. The document canvas was the grid and the resolution multiplied
 * together, each range-limited in its own field and never as a product, which is
 * 68GB at the maximum the interface offered. A group renders onto a scratch image its
 * own size and the size field's maximum was Integer.MAX_VALUE, which with the guard
 * removed does not throw, it takes the JVM out with Java heap space. The position
 * field had no range at all.
 *
 * <p>ImGui makes this easier to get wrong than it looks. A drag field's range is a
 * soft limit, and a control click types straight through it, which is a gesture this
 * mod's own tooltips teach. So a range is necessary and not sufficient, and anything
 * that reaches an allocation needs checking where it lands as well.
 *
 * <p>This is the cheap half: every numeric field says what it accepts, or says here
 * why it does not need to.
 */
class NumericInputLintTest {

    private static final Pattern NUMERIC_INPUT = Pattern.compile(
            "\\b(dragInt\\d?|dragFloat\\d?|sliderInt\\d?|sliderFloat\\d?|inputInt|inputFloat)\\s*\\(");

    /**
     * Fields whose value is bounded somewhere other than the field, with where.
     *
     * <p>Named by a fragment of the call rather than a line, so moving the code does
     * not quietly empty the list.
     */
    private static final List<String> ALLOWED = List.of(
            // Clamped to MINIMUM_PIXELS..MAXIMUM_PIXELS on commit, a few lines below,
            // and written back into the field so what you see is what was kept.
            "Pixels per frame##settings-pixels",

            // A generator's own number parameters. There is no range to give: the
            // generator declares them and this panel does not know what they mean.
            // Bounded where it matters instead, in callSize, which refuses any canvas
            // past MAX_CANVAS_EDGE or MAX_CANVAS_PIXELS with the numbers in the
            // message.
            "id, field.number");

    @Test
    @DisplayName("every numeric field carries a range, or says why it does not need one")
    void numericFieldsAreBounded() throws IOException {
        List<String> unbounded = new ArrayList<>();
        Path root = Path.of("src/main/java/dev/kierandrewett/mcmarkings/gui");

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                unbounded.addAll(scan(file));
            }
        }

        assertTrue(unbounded.isEmpty(), () -> """
                A numeric field accepts anything. Give it a minimum and a maximum, and \
                if the value reaches an allocation, check it again where it lands: \
                ImGui treats a drag range as a soft limit and a control click types \
                past it. If it is bounded somewhere else, add it to ALLOWED with where.
                """ + String.join("\n", unbounded));
    }

    private static List<String> scan(Path file) throws IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        List<String> found = new ArrayList<>();
        Matcher calls = NUMERIC_INPUT.matcher(source);

        while (calls.find()) {
            String arguments = argumentsAt(source, calls.end() - 1);
            if (arguments == null) {
                continue;
            }
            if (ALLOWED.stream().anyMatch(arguments::contains)) {
                continue;
            }
            // A drag takes label, value, speed, min, max; a slider has no speed and
            // takes four. Reading five for both was this check being wrong rather
            // than the code, and it flagged two opacity sliders that are bounded to
            // nought and one exactly as they should be.
            int needed = calls.group(1).startsWith("slider") ? 4 : 5;
            if (topLevelArguments(arguments) >= needed) {
                continue;
            }
            int line = source.substring(0, calls.start()).split("\n", -1).length;
            found.add("  " + file.getFileName() + ":" + line + "  "
                    + calls.group(1) + "(" + arguments.replaceAll("\\s+", " ") + ")");
        }
        return found;
    }

    /** The text between a call's brackets, balanced, or null if they never close. */
    private static String argumentsAt(String source, int openBracket) {
        int depth = 0;
        for (int at = openBracket; at < source.length(); at++) {
            char character = source.charAt(at);
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
                if (depth == 0) {
                    return source.substring(openBracket + 1, at);
                }
            }
        }
        return null;
    }

    /** Commas that separate this call's own arguments, not a nested call's. */
    private static int topLevelArguments(String arguments) {
        if (arguments.isBlank()) {
            return 0;
        }
        int depth = 0;
        int count = 1;
        for (char character : arguments.toCharArray()) {
            if (character == '(' || character == '[' || character == '{') {
                depth++;
            } else if (character == ')' || character == ']' || character == '}') {
                depth--;
            } else if (character == ',' && depth == 0) {
                count++;
            }
        }
        return count;
    }
}
