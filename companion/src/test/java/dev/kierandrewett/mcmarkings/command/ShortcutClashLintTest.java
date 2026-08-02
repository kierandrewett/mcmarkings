package dev.kierandrewett.mcmarkings.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No two commands claim the same keys.
 *
 * <p>There is already a check for this and it runs in the game, at startup, and
 * writes a line to the log. That is the right thing to do about a clash nobody
 * predicted and the wrong place to find out: a clash means a shortcut listed beside a
 * key that does nothing, and the only symptom is something not happening. By the time
 * it is in a log it has shipped.
 *
 * <p>So this asks the same question of the source, where the answer arrives before
 * anyone plays. The two are not redundant. The runtime one sees the registries as
 * they actually are, including anything built in a loop; this one sees only what is
 * written out literally, and catches it a great deal earlier.
 *
 * <p>What it cannot see is said plainly rather than papered over: the window's tab
 * shortcuts are Ctrl and a digit built from an index, and the editor's nudges are
 * built from a direction, so neither appears here. Both are covered by the runtime
 * check.
 */
class ShortcutClashLintTest {

    /** A command, up to wherever the next one starts. */
    private static final Pattern COMMAND =
            Pattern.compile("Command\\.of\\(\"([^\"]+)\"(.*?)(?=Command\\.of\\(\"|\\Z)", Pattern.DOTALL);

    /** A shortcut written out rather than computed. */
    private static final Pattern LITERAL_SHORTCUT =
            Pattern.compile("\\.shortcut\\((Shortcut\\.[a-zA-Z]+\\([^)]*\\))\\)");

    /** Below this the parse has stopped seeing most of them and proves nothing. */
    private static final int EXPECTED_AT_LEAST = 18;

    @Test
    @DisplayName("no two commands are bound to the same keys")
    void shortcutsDoNotClash() throws IOException {
        Map<String, List<String>> byKeys = new LinkedHashMap<>();
        Path root = Path.of("src/main/java/dev/kierandrewett/mcmarkings/gui");

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher commands = COMMAND.matcher(source);
                while (commands.find()) {
                    Matcher shortcut = LITERAL_SHORTCUT.matcher(commands.group(2));
                    if (shortcut.find()) {
                        byKeys.computeIfAbsent(shortcut.group(1).replaceAll("\\s+", " "),
                                ignored -> new ArrayList<>()).add(commands.group(1));
                    }
                }
            }
        }

        int found = byKeys.values().stream().mapToInt(List::size).sum();
        assertTrue(found >= EXPECTED_AT_LEAST,
                "only found " + found + " shortcuts, so this has stopped reading them properly "
                        + "and a clash would slip past");

        List<String> clashes = byKeys.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> "  " + entry.getKey() + " is claimed by " + entry.getValue())
                .toList();

        assertTrue(clashes.isEmpty(), () -> """
                Two commands answer to the same keys. Keys go to the registries in \
                order, so the first one wins and the other silently never fires, with \
                its shortcut still listed beside the key that does nothing.
                """ + String.join("\n", clashes));
    }
}
