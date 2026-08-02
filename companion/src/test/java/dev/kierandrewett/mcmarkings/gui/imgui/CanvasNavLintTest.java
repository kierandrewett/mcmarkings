package dev.kierandrewett.mcmarkings.gui.imgui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The canvas refuses keyboard navigation, so the arrows stay the editor's.
 *
 * <p>Keyboard navigation is on everywhere now, including the editor, because the
 * properties panel could not be reached without a mouse. Navigation and a canvas want
 * the same four arrow keys, and the thing that lets them share is one flag on one
 * child window.
 *
 * <p>Take it away and the bug is not that navigation stops working. With nothing
 * focused, the first arrow press is spent by navigation picking a control, and from
 * then on a control is focused so the editor declines the arrows entirely: the layer
 * moves one pixel and then never moves again. That is far worse than the problem the
 * navigation change was made to fix, and it would arrive from deleting a flag that
 * looks like a detail of how the canvas scrolls.
 */
class CanvasNavLintTest {

    private static final Path EDITOR =
            Path.of("src/main/java/dev/kierandrewett/mcmarkings/gui/imgui/panel/EditorPanel.java");

    @Test
    @DisplayName("the canvas child is marked NoNavInputs")
    void theCanvasKeepsTheArrows() throws IOException {
        String source = Files.readString(EDITOR, StandardCharsets.UTF_8);
        Matcher canvas = Pattern.compile("beginChild\\(\"##editor-canvas\"(.*?)\\);", Pattern.DOTALL)
                .matcher(source);

        assertTrue(canvas.find(),
                "the canvas child is not created the way this expects, so nothing here is "
                        + "checking that navigation still leaves the arrows alone");
        assertTrue(canvas.group(1).contains("NoNavInputs"), () -> """
                The editor's canvas accepts keyboard navigation. Navigation will spend \
                the first arrow press focusing a control, and every press after that \
                goes to the form because a control is now focused, so nudging moves a \
                layer once and then stops. Put ImGuiWindowFlags.NoNavInputs back on \
                the canvas child.""");
    }

    /**
     * And that the editor still hands those keys over when the form has them.
     *
     * <p>The other half. The flag keeps the arrows on the canvas; this keeps them off
     * it once somebody has tabbed into the properties panel, where a nudge firing
     * alongside a focus change would edit the layer while they were only trying to
     * reach a field.
     */
    @Test
    @DisplayName("the key handler yields the shared keys to a focused control")
    void theFormGetsThemBack() throws IOException {
        String source = Files.readString(EDITOR, StandardCharsets.UTF_8);

        assertTrue(source.contains("NAVIGATION_KEYS"),
                "the set of keys navigation and the canvas share is gone, so the editor is "
                        + "handling Tab and the arrows even while a field has the keyboard");
        assertTrue(source.contains("navOnAControl && !control && NAVIGATION_KEYS.contains(keyCode)"),
                "the editor no longer declines the shared keys while a control is focused, so "
                        + "tabbing through the properties panel nudges the layer at the same time");
    }
}
