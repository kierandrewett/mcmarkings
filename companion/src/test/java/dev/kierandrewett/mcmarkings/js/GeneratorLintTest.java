package dev.kierandrewett.mcmarkings.js;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Enforces the one language trap the generator scripts have to avoid.
 *
 * <p>Rhino does not re-initialise a {@code const} declared inside a loop body:
 * every iteration keeps the first iteration's value. It is written up in the
 * generators README, and it has still caught me out twice, most recently while
 * writing a grid search whose working values never changed after the first
 * candidate. Node runs the same code correctly, so nothing outside the game
 * disagrees with it, and the resulting output looks plausible rather than broken.
 *
 * <p>A written warning demonstrably does not prevent it. A failing build does.
 */
class GeneratorLintTest {

    private static final Pattern LOOP = Pattern.compile("\\b(for|while)\\s*\\(");

    private static final Pattern CONST_DECLARATION = Pattern.compile("^\\s*const\\s+[A-Za-z_$]");

    @Test
    @DisplayName("no generator declares a const inside a loop body")
    void noConstInsideALoopBody() throws IOException {
        Path generators = repositoryRoot().resolve("generators");
        Assumptions.assumeTrue(Files.isDirectory(generators), "generators/ not present, skipping");

        List<String> offences = new ArrayList<>();
        try (Stream<Path> scripts = Files.list(generators)) {
            for (Path script : scripts.filter(path -> path.toString().endsWith(".js")).toList()) {
                offences.addAll(scan(script));
            }
        }

        if (!offences.isEmpty()) {
            throw new AssertionError("""
                    Rhino keeps the first iteration's value for a const declared inside a loop body, \
                    so these will silently use stale values in game while behaving correctly under node. \
                    Use let instead. See generators/README.md.
                    """ + String.join("\n", offences));
        }
    }

    /**
     * Line-based rather than a real parse.
     *
     * <p>A JavaScript parser would be the correct tool and is far more than this
     * needs: the scripts are small, hand-written and consistently formatted, and a
     * check that is occasionally too eager is much cheaper than one that misses the
     * bug it exists for. Strings and comments are blanked first so a brace inside
     * either cannot throw the depth off.
     */
    private static List<String> scan(Path script) throws IOException {
        List<String> lines = blankStringsAndComments(Files.readString(script, StandardCharsets.UTF_8));
        List<String> offences = new ArrayList<>();

        // Depth of each loop body currently open, so a const is only flagged when it
        // sits inside one rather than merely after one.
        Deque<Integer> loopDepths = new ArrayDeque<>();
        int depth = 0;

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);

            if (!loopDepths.isEmpty() && CONST_DECLARATION.matcher(line).find()) {
                offences.add("  " + script.getFileName() + ":" + (index + 1) + "  " + line.strip());
            }

            boolean opensLoop = LOOP.matcher(line).find();
            int before = depth;
            depth += countOf(line, '{') - countOf(line, '}');

            if (opensLoop && depth > before) {
                loopDepths.push(before);
            }
            while (!loopDepths.isEmpty() && depth <= loopDepths.peek()) {
                loopDepths.pop();
            }
        }

        return offences;
    }

    /** Replaces string and comment contents with spaces, keeping line numbers intact. */
    private static List<String> blankStringsAndComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean inBlockComment = false;
        char stringDelimiter = 0;

        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';

            if (current == '\n') {
                out.append('\n');
                if (stringDelimiter != '`') {
                    stringDelimiter = 0;
                }
                continue;
            }

            if (inBlockComment) {
                if (current == '*' && next == '/') {
                    inBlockComment = false;
                    out.append("  ");
                    index++;
                    continue;
                }
                out.append(' ');
                continue;
            }

            if (stringDelimiter != 0) {
                if (current == '\\') {
                    out.append("  ");
                    index++;
                    continue;
                }
                if (current == stringDelimiter) {
                    stringDelimiter = 0;
                }
                out.append(' ');
                continue;
            }

            if (current == '/' && next == '*') {
                inBlockComment = true;
                out.append("  ");
                index++;
                continue;
            }
            if (current == '/' && next == '/') {
                // Blank to the end of the line; the newline itself is kept above.
                while (index < source.length() && source.charAt(index) != '\n') {
                    out.append(' ');
                    index++;
                }
                index--;
                continue;
            }
            if (current == '"' || current == '\'' || current == '`') {
                stringDelimiter = current;
                out.append(' ');
                continue;
            }

            out.append(current);
        }

        return List.of(out.toString().split("\n", -1));
    }

    private static int countOf(String text, char character) {
        int count = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == character) {
                count++;
            }
        }
        return count;
    }

    private static Path repositoryRoot() {
        return Path.of("").toAbsolutePath().getParent();
    }

    @Test
    @DisplayName("the check actually catches the pattern it exists for")
    void theCheckCatchesTheBug(@org.junit.jupiter.api.io.TempDir Path directory) throws IOException {
        // A lint nobody has seen fail is a lint nobody should trust.
        Path script = directory.resolve("sample.js");
        Files.writeString(script, """
                const fine = 1;
                for (let i = 0; i < 3; i += 1) {
                    const stale = i;
                    let ok = i;
                }
                const alsoFine = 2;
                """, StandardCharsets.UTF_8);

        List<String> offences = scan(script);

        if (offences.size() != 1 || !offences.getFirst().contains("stale")) {
            throw new AssertionError("expected exactly the const inside the loop, got " + offences);
        }
    }

    @Test
    @DisplayName("a const in a string or comment is not mistaken for code")
    void stringsAndCommentsDoNotTriggerIt(@org.junit.jupiter.api.io.TempDir Path directory) throws IOException {
        Path script = directory.resolve("sample.js");
        Files.writeString(script, """
                for (let i = 0; i < 3; i += 1) {
                    // const commented = i;
                    let text = "const quoted = 1; }";
                    let more = 'const also = 2; {';
                }
                """, StandardCharsets.UTF_8);

        List<String> offences = scan(script);

        if (!offences.isEmpty()) {
            throw new AssertionError("false positives: " + offences);
        }
    }
}
