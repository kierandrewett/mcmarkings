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
 * Interface text does not spell out a key it does not read.
 *
 * <p>A tooltip naming a shortcut is right until the day the shortcut moves, and then
 * it is wrong in the one place somebody looks to find out what the key is. Nothing
 * fails, nothing logs, and the only way to notice is to press it.
 *
 * <p>{@code Shortcut.display()} builds the name from the binding, so the answer is
 * always available; the mistake is typing it instead of asking for it. This makes
 * that mistake fail rather than merely be absent, which is the difference between a
 * property that holds and one that happens to hold today.
 */
class ShortcutTextLintTest {

    /**
     * A Java string literal, escapes included.
     *
     * <p>Matching the key name directly between quotes is the obvious way and it is
     * wrong: it happily starts at one string's closing quote, runs through the
     * comment after it, and ends at the next string's opening quote, so ordinary
     * prose about shortcuts fails the check. Whole literals first, then look inside.
     */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    /** Key names that would be a shortcut if written by hand. */
    private static final Pattern KEY_NAME = Pattern.compile("Ctrl\\+|Shift\\+|Alt\\+");

    /**
     * Text that names a key nothing binds, so there is nothing to derive it from.
     *
     * <p>Alt suspends snapping by being held during a drag rather than by being a
     * registered shortcut, so the code reads the modifier directly and the sentence
     * has to say so. Verified against that code rather than assumed.
     */
    private static final List<String> ALLOWED = List.of(
            "Hold Alt to suspend it for one drag");

    @Test
    @DisplayName("no interface string hardcodes a shortcut")
    void shortcutsAreDerivedNotTyped() throws IOException {
        List<String> offences = new ArrayList<>();
        Path root = Path.of("src/main/java/dev/kierandrewett/mcmarkings/gui");

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                // The class whose job is turning a binding into text is the one place
                // these strings belong.
                if (file.getFileName().toString().equals("Shortcut.java")) {
                    continue;
                }
                offences.addAll(scan(file));
            }
        }

        assertTrue(offences.isEmpty(), () -> """
                Interface text spells out a shortcut. Ask the command for it instead, \
                through Shortcut.display(), so it follows the binding; or add it to \
                ALLOWED with a reason if nothing binds that key.
                """ + String.join("\n", offences));
    }

    private static List<String> scan(Path file) throws IOException {
        List<String> found = new ArrayList<>();
        String source = Files.readString(file, StandardCharsets.UTF_8);
        Matcher literals = STRING_LITERAL.matcher(source);

        while (literals.find()) {
            String text = literals.group();
            if (KEY_NAME.matcher(text).find() && ALLOWED.stream().noneMatch(text::contains)) {
                found.add("  " + file.getFileName() + ": " + text);
            }
        }
        return found;
    }

    @Test
    @DisplayName("the check would catch a typed shortcut")
    void theCheckCatchesWhatItExistsFor() {
        // A lint nobody has seen fail is a lint nobody should trust.
        Matcher literals = STRING_LITERAL.matcher("""
                ImGui.setTooltip("Open the palette (Ctrl+P)");
                """);

        assertTrue(literals.find() && KEY_NAME.matcher(literals.group()).find(),
                "the scan missed an obvious one");
    }
}
