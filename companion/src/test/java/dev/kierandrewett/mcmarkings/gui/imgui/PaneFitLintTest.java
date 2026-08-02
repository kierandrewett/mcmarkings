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
 * A line drawn straight into a pane is measured, not counted.
 *
 * <p>A character count is right for a message about to be wrapped, or put in a
 * tooltip, where the cap is about not flooding rather than about fitting. It is wrong
 * for a line drawn into a pane whose width comes from the window: at any size but one
 * the line either stops early or runs off the edge with nothing to say it has.
 *
 * <p>Found three times. The browser's detail pane cut a path at seventy two, the
 * placed list cut one at sixty four, and the browser's own fix was a private helper
 * that the placed list then needed and could not reach.
 */
class PaneFitLintTest {

    /** text() or textDisabled() given a counted truncation. */
    private static final Pattern COUNTED_IN_A_PANE = Pattern.compile(
            "ImGui\\.text(?:Disabled)?\\([^;]*ImGuiScreens\\.truncate\\([^;]*,\\s*\\d+\\s*\\)");

    @Test
    @DisplayName("no plain line in a pane is cut to a number of characters")
    void linesInPanesAreMeasured() throws IOException {
        List<String> counted = new ArrayList<>();
        Path root = Path.of("src/main/java/dev/kierandrewett/mcmarkings/gui");

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher found = COUNTED_IN_A_PANE.matcher(source);
                while (found.find()) {
                    int line = source.substring(0, found.start()).split("\n", -1).length;
                    counted.add("  " + file.getFileName() + ":" + line + "  "
                            + found.group().replaceAll("\\s+", " "));
                }
            }
        }

        assertTrue(counted.isEmpty(), () -> """
                A line is drawn into a pane and cut to a number of characters, which is \
                not a width. Use ImGuiScreens.fitToPane, which measures what is there. \
                A count is still right for anything wrapped or in a tooltip, where it \
                is about not flooding rather than about fitting.
                """ + String.join("\n", counted));
    }
}
