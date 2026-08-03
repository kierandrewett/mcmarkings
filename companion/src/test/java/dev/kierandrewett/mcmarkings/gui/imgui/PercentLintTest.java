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
 * A per cent sign in a sentence survives being drawn.
 *
 * <p>Dear ImGui's text calls are printf. A per cent in a sentence is therefore a format specifier,
 * and "Covers 79% of those frames" reached the screen as "Covers 790f those frames": the per cent,
 * the space and the o were read as an instruction rather than as three characters.
 *
 * <p>Nothing warns about it. The sentence is simply wrong, and only in the cases where a number
 * happens to be followed by the wrong letter, so it survives every reading of the code and is found
 * by somebody screenshotting the panel.
 *
 * <p>{@code ImGuiScreens.literal} doubles them, which is how printf is told to print one. Button
 * labels do not need it and must not have it: ImGui draws those verbatim, so an escaped label shows
 * two per cent signs.
 */
class PercentLintTest {

    /** A formatted text call with a per cent inside a literal in its arguments. */
    private static final Pattern FORMATTED = Pattern.compile(
            "ImGui\\.(text|textDisabled|textWrapped|textColored|setTooltip)\\(([^;]*)\\);",
            Pattern.DOTALL);

    @Test
    @DisplayName("no drawn sentence contains an unescaped per cent")
    void percentSignsAreEscaped() throws IOException {
        List<String> raw = new ArrayList<>();
        Path root = Path.of("src/main/java/dev/kierandrewett/mcmarkings/gui");

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher call = FORMATTED.matcher(source);
                while (call.find()) {
                    String arguments = call.group(2);
                    if (arguments.contains("literal(") || !arguments.contains("%")) {
                        continue;
                    }
                    // A doubled one is already correct, and a drag field's own format string is
                    // printf on purpose rather than by accident.
                    if (arguments.contains("%%")) {
                        continue;
                    }
                    raw.add("  " + file.getFileName() + ":"
                            + (source.substring(0, call.start()).split("\n", -1).length)
                            + "  ImGui." + call.group(1));
                }
            }
        }

        assertTrue(raw.isEmpty(), () -> """
                A sentence with a per cent sign in it is being drawn by a call that formats its \
                argument, so printf will eat the per cent and whatever follows it. Wrap the string \
                in ImGuiScreens.literal. Do not do this to a button label: those are drawn \
                verbatim and would show two per cent signs.
                """ + String.join("\n", raw));
    }
}
