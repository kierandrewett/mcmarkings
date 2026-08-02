package dev.kierandrewett.mcmarkings.gui.imgui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A field's label is drawn before the field, not after it.
 *
 * <p>Reported twice. The shared helper drew the control and then the label
 * underneath, so down a column of properties every label read as belonging to the box
 * below it: the box holding the position sat under a label saying Name, and the one
 * holding the size sat under Position.
 *
 * <p>Fixing the helper fixed most of them and missed three that drew their own label
 * by hand, which is how the second report happened. Hence a rule rather than another
 * look.
 */
class LabelOrderLintTest {

    /** An input, then a bare label immediately after it, which is the wrong way round. */
    private static final Pattern INPUT = Pattern.compile(
            "ImGui\\.(inputText|inputTextMultiline|inputInt|inputFloat|dragInt\\d?|dragFloat\\d?"
                    + "|sliderInt\\d?|sliderFloat\\d?|colorEdit\\d?|combo|checkbox)\\s*\\(");

    /**
     * A label, as opposed to a line of help.
     *
     * <p>Help belongs after the field: it explains what to type once you know what
     * the box is. A label has to come first, and the two are told apart by shape, a
     * label being a short bare phrase and help being a sentence with a full stop. The
     * save prompt's "Reopening it later uses this name" is help and is right where it
     * is; "Map name" underneath the box it names is not.
     */
    private static final Pattern LABEL =
            Pattern.compile("ImGui\\.text(Disabled)?\\(\"([^\".]{1,28})\"\\s*\\)");

    /** How far past the input a trailing label still counts as belonging to it. */
    private static final int LINES_AFTER = 6;

    @Test
    @DisplayName("no field is labelled underneath itself")
    void labelsComeBeforeTheirFields() throws IOException {
        List<String> wrongWayRound = new ArrayList<>();
        Path root = Path.of("src/main/java/dev/kierandrewett/mcmarkings/gui");

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                wrongWayRound.addAll(scan(file));
            }
        }

        assertTrue(wrongWayRound.isEmpty(), () -> """
                A label is drawn after the field it names, so it reads as belonging to \
                whatever comes next. Put it before, or use the field() helper which \
                does that for you.
                """ + String.join("\n", wrongWayRound));
    }

    private static List<String> scan(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> found = new ArrayList<>();

        for (int at = 0; at < lines.size(); at++) {
            if (!INPUT.matcher(lines.get(at)).find()) {
                continue;
            }
            for (int ahead = at + 1; ahead < Math.min(at + 1 + LINES_AFTER, lines.size()); ahead++) {
                String next = lines.get(ahead).strip();
                // Another input ends the field, and so does anything that positions.
                if (INPUT.matcher(next).find() || next.startsWith("ImGui.separator")) {
                    break;
                }
                if (LABEL.matcher(next).find()) {
                    found.add("  " + file.getFileName() + ":" + (ahead + 1) + "  " + next);
                    break;
                }
            }
        }
        return found;
    }
}
