package dev.kierandrewett.mcmarkings.gui.imgui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Marks drawn over the transparency chequerboard go through a helper that survives it.
 *
 * <p>Giving the board a genuinely light tone, so that dark artwork could be seen at
 * all, broke six things drawn on top of it across as many days: the canvas edge, the
 * frame grid, the selection box, the snap guides, the browser caption, the keyboard
 * focus ring, the resize handles, the marquee outline, and finally the cell border,
 * which my own fix for the focus ring pushed onto the panel where it measured 1.1:1.
 * Each was found separately and several only after shipping the one before.
 *
 * <p>The pattern was never that the change was wrong. It was that a colour is not a
 * local edit: everything drawn on top of it inherits the change, and I kept checking
 * the piece in front of me. So this enumerates instead, and anything new has to say
 * which it is.
 *
 * <p>Deliberately narrow. It reads only the two panels that draw a chequerboard, and
 * there are five allowed calls, so it is a list someone can actually read rather than
 * a rule that fires constantly and gets silenced.
 */
class ChequerboardDrawLintTest {

    private static final List<Path> PANELS = List.of(
            Path.of("src/main/java/dev/kierandrewett/mcmarkings/gui/imgui/panel/ImageBrowserPanel.java"),
            Path.of("src/main/java/dev/kierandrewett/mcmarkings/gui/imgui/panel/EditorPanel.java"),
            // Added when the generator preview started drawing the sign inside the
            // frames it is going onto, which put a chequerboard behind it.
            Path.of("src/main/java/dev/kierandrewett/mcmarkings/gui/imgui/panel/GeneratorPanel.java"));

    /**
     * Draws that are right as they are, each with the reason.
     *
     * <p>Matched on a distinctive fragment of the call rather than a line number, so
     * moving the code does not silently empty the list.
     */
    private static final List<String> ALLOWED = List.of(
            // The caption's own band, which is the thing making the text readable.
            "Theme.red(Theme.CAPTION_BACKING)",

            // Drawn on that band, not on the board.
            "ImGuiScreens.truncate(image.captionFor(",

            // Both borders sit on the chequerboard rather than the panel, where
            // near-black reads at about 11:1 against a light square. The cell one is
            // inset to clear the focus ring's gutter.
            "y + cell - FOCUS_GUTTER, ImGui.getColorU32(ImGuiCol.Border)",
            "x + boxWidth, y + boxHeight, ImGui.getColorU32(ImGuiCol.Border)",

            // A translucent wash under the marquee outline. The outline carries the
            // edge and goes through overlayRect; this is only a tint.
            "Theme.red(Theme.SELECTION)");

    @Test
    @DisplayName("nothing new is drawn over the chequerboard without saying how it survives it")
    void drawsOverTheChequerboardAreAccountedFor() throws IOException {
        List<String> unaccounted = new ArrayList<>();

        for (Path panel : PANELS) {
            for (String statement : statements(panel)) {
                if (!statement.contains("drawList.add")) {
                    continue;
                }
                if (ALLOWED.stream().anyMatch(statement::contains)) {
                    continue;
                }
                unaccounted.add("  " + panel.getFileName() + "  " + statement.trim());
            }
        }

        assertTrue(unaccounted.isEmpty(), () -> """
                A raw draw call was added to a panel that shows a chequerboard. Over a \
                light square a single pale mark measures about 1.2:1 and is not there \
                at all. Use ImGuiScreens.overlayLine, overlayRect, overlayMarker or \
                overlayText, which carry a dark halo, or add it to ALLOWED with the \
                reason it does not need one.
                """ + String.join("\n", unaccounted));
    }

    /** Whole statements, since a draw call is regularly split across lines. */
    private static List<String> statements(Path file) throws IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        List<String> found = new ArrayList<>();
        for (String statement : source.split(";")) {
            found.add(statement.replaceAll("\\s+", " "));
        }
        return found;
    }
}
