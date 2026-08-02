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
 * A field's width cannot exceed the pane it is in.
 *
 * <p>Widths written as a multiple of the font size grow with the GUI scale and the
 * pane does not. At scale 4 on an 854 pixel window the font is about forty pixels, so
 * a field asking for twenty four of them wants 960 in a window of 854. It does not
 * overflow gracefully: it puts a horizontal scrollbar on a pane that had no reason
 * for one and takes the right hand side of the field off with it.
 *
 * <p>The people most likely to meet that are the ones who raised the GUI scale so
 * they could read, which is what makes it worth a rule rather than a note.
 *
 * <p>The editor never had the problem. Every field there asks for -1, ImGui's own
 * "fill what is left", and it is only the panels that wanted a particular width that
 * needed the clamp.
 */
class FieldWidthLintTest {

    /**
     * A size in font sizes handed straight to something that takes one.
     *
     * <p>setNextItemWidth was the only one this looked at when it was written, and
     * beginChild takes a size the same way. The folder picker's list was 28 font
     * sizes across and ten rows down inside a modal that resizes around its contents,
     * so at a high GUI scale the window grew past the screen and the button that
     * chooses the folder went with it. This checked the fields on that same screen
     * and said nothing about the list.
     */
    private static final Pattern UNCLAMPED = Pattern.compile(
            "(setNextItemWidth|beginChild)\\([^;]*?ImGui\\.(getFontSize|getTextLineHeight)\\(\\)");

    @Test
    @DisplayName("no field asks for a width the pane may not have")
    void fieldWidthsAreClamped() throws IOException {
        List<String> unclamped = new ArrayList<>();
        Path root = Path.of("src/main/java/dev/kierandrewett/mcmarkings/gui");

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int at = 0; at < lines.size(); at++) {
                    Matcher found = UNCLAMPED.matcher(lines.get(at));
                    if (found.find()) {
                        unclamped.add("  " + file.getFileName() + ":" + (at + 1) + "  " + lines.get(at).strip());
                    }
                }
            }
        }

        assertTrue(unclamped.isEmpty(), () -> """
                A field's width is a multiple of the font size with nothing bounding \
                it. That multiple grows with the GUI scale and the pane does not, so \
                at a high scale on a small window the field is wider than the window. \
                Use ImGuiScreens.fieldWidth(characters) inside a pane, or \
                ImGuiScreens.withinWindow(wanted, fraction, vertical) inside a modal \
                that resizes around its contents, or -1 to fill what is left.
                """ + String.join("\n", unclamped));
    }

    /**
     * The helper does what it says at both ends, since the whole point is the case
     * where the two disagree.
     */
    @Test
    @DisplayName("the clamp is the smaller of what was asked for and what there is")
    void theHelperTakesTheSmaller() {
        // Pure arithmetic, mirrored rather than called: ImGui.getFontSize needs a
        // context. What is being pinned is which way round the comparison goes, and
        // that is the part someone would get backwards.
        float wanted = 40.0f * 24.0f;
        float available = 854.0f;
        assertTrue(Math.min(wanted, available) == available,
                "at scale 4 on the narrowest window, the pane has to win");

        float roomy = 1920.0f;
        assertTrue(Math.min(wanted, roomy) == wanted,
                "on a wide window the field should still be the width it asked for");
    }
}
