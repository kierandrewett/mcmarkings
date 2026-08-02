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
 * A row of buttons wraps rather than running off the edge.
 *
 * <p>ImGui does not wrap a run of sameLine calls. Past the edge of the pane a button
 * is still drawn and simply cannot be reached, which is worse than a toolbar two rows
 * deep by some distance: the controls that go first are the ones at the end of the
 * row, and on the placed list those were Forget and Delete.
 *
 * <p>Text is not covered by this. A caption that runs long is clipped and reads
 * short, which is a nuisance; a button that runs long cannot be pressed at all.
 *
 * <p>Fixed four times by hand over as many weeks, each time for the row in front of
 * me. The window's own top bar was named in one of those commit messages as the last
 * one left and then not touched for another fortnight, which is the argument for a
 * rule rather than another round of noticing.
 */
class ButtonRowLintTest {

    private static final Pattern SAME_LINE = Pattern.compile("ImGui\\.sameLine\\(\\s*\\)");

    private static final Pattern BUTTON = Pattern.compile("ImGui\\.button\\(");

    /** How far past a sameLine to look for the button it is holding a place for. */
    private static final int STATEMENTS_AHEAD = 4;

    @Test
    @DisplayName("no button is placed with sameLine, which cannot wrap")
    void buttonRowsWrap() throws IOException {
        List<String> unwrapped = new ArrayList<>();
        Path root = Path.of("src/main/java/dev/kierandrewett/mcmarkings/gui");

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                unwrapped.addAll(scan(file));
            }
        }

        assertTrue(unwrapped.isEmpty(), () -> """
                A button is positioned with ImGui.sameLine, which never wraps, so on a \
                narrow window it is drawn past the edge of the pane and cannot be \
                pressed. Use ImGuiScreens.flowTo("<its label>"), which keeps it on the \
                row while there is room and starts a new one when there is not.
                """ + String.join("\n", unwrapped));
    }

    private static List<String> scan(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> found = new ArrayList<>();

        for (int at = 0; at < lines.size(); at++) {
            if (!SAME_LINE.matcher(lines.get(at)).find()) {
                continue;
            }
            // Only the next real statement counts. Looking further turns every
            // sameLine in a method that happens to contain a button into an offence,
            // which is the false positive that makes a rule like this get switched off.
            for (int ahead = at + 1; ahead < Math.min(at + 1 + STATEMENTS_AHEAD, lines.size()); ahead++) {
                String next = lines.get(ahead).strip();
                if (next.isEmpty() || next.startsWith("//") || next.startsWith("*")) {
                    continue;
                }
                Matcher button = BUTTON.matcher(next);
                if (button.find()) {
                    found.add("  " + file.getFileName() + ":" + (at + 1) + "  " + next);
                }
                break;
            }
        }
        return found;
    }
}
