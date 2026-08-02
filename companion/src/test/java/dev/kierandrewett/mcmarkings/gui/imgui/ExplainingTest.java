package dev.kierandrewett.mcmarkings.gui.imgui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tooltip belongs to the thing you are pointing at.
 *
 * <p>Making tooltips answer to keyboard focus as well as the mouse broke them for the
 * mouse. An item keeps focus after it has been clicked, so a button somebody pressed
 * carried on explaining itself while the mouse moved away, and whichever of the two
 * called setTooltip later won the frame. Reported as tooltips not updating as you
 * move along a row, which is exactly what that looks like.
 *
 * <p>The rule is that the mouse wins whenever it is doing anything. Focus only speaks
 * when the pointer is over nothing at all, which is when the keyboard is driving and
 * a tooltip is the only way to find out what a control does.
 *
 * <p>Checked in the source. The condition needs a running ImGui context, and what
 * went wrong was the shape of the condition rather than any arithmetic.
 */
class ExplainingTest {

    @Test
    @DisplayName("a focused control stays quiet while the mouse is over something")
    void focusDoesNotSpeakOverTheMouse() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/kierandrewett/mcmarkings/gui/imgui/ImGuiScreens.java"));

        int at = source.indexOf("public static boolean explaining()");
        assertTrue(at > 0, "the shared tooltip condition has gone");
        String body = source.substring(at, source.indexOf("\n    }", at));

        assertTrue(body.contains("isItemFocused()"),
                "focus no longer shows a tooltip, so the keyboard has no way to read a control");
        assertTrue(body.contains("!ImGui.isAnyItemHovered()"),
                "a focused control can speak while the mouse is over something else, which is "
                        + "a tooltip that will not change as you move along a row");
        assertTrue(body.contains("AllowWhenDisabled"),
                "a disabled control can no longer say why it is disabled");
    }
}
