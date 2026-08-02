package dev.kierandrewett.mcmarkings.gui.imgui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The font atlas is built once and never cleared again.
 *
 * <p>Clearing it takes the game down. Not an exception, not a crash report: the JVM
 * aborts with free(): invalid pointer, because clearing an atlas frees the font data
 * ImGui was given and that data was not ImGui's to free. There is nothing in the log
 * except the line before it, which is why this is written down rather than left to be
 * worked out again.
 *
 * <p>It went unnoticed for as long as it did because nothing ever cleared the atlas.
 * The only thing that used to change the size was the game's own GUI scale, and
 * almost nobody changes that in the middle of a session. Making the text size a
 * setting put a slider on a path that had never once been taken, and the first person
 * to drag it lost their game.
 *
 * <p>Nothing needs rebuilding anyway. Dear ImGui 1.92 rasterises a font at whatever
 * size it is asked for, so the size belongs in a pushFont at the top of the frame,
 * which is where it now is.
 */
class FontAtlasLintTest {

    private static final Path FONTS =
            Path.of("src/main/java/dev/kierandrewett/mcmarkings/gui/imgui/ImGuiFonts.java");

    private static final Path SHELL =
            Path.of("src/main/java/dev/kierandrewett/mcmarkings/gui/imgui/ImGuiShell.java");

    @Test
    @DisplayName("the atlas is cleared in exactly one place, behind a build-once guard")
    void theAtlasIsClearedOnce() throws IOException {
        String source = Files.readString(FONTS, StandardCharsets.UTF_8);

        assertEquals(1, source.split("atlas\\.clear\\(\\)", -1).length - 1,
                "atlas.clear() appears more than once. Every call after the first aborts the "
                        + "JVM with free(): invalid pointer, with no stack trace and nothing in "
                        + "the log to say where it happened.");

        assertTrue(source.contains("if (builtForPixels > 0.0f) {"),
                "the guard that stops the atlas being built a second time is gone. Without it "
                        + "any change of text size or GUI scale clears the atlas and kills the "
                        + "game natively.");
    }

    /**
     * And that the size still reaches the frame, since the guard above would be
     * satisfied by simply never applying the setting at all.
     */
    @Test
    @DisplayName("the wanted size is pushed for the frame")
    void theSizeIsPushedInstead() throws IOException {
        String shell = Files.readString(SHELL, StandardCharsets.UTF_8);

        assertTrue(shell.contains("ImGui.pushFont(face, pixels)"),
                "nothing pushes the wanted text size, so the setting moves a slider, saves a "
                        + "number and changes nothing on screen");
        assertTrue(shell.contains("ImGui.popFont()"),
                "the font is pushed and never popped, so the stack grows by one every frame");
        assertTrue(shell.contains("} finally {"),
                "the pop is not in a finally, so a throw while drawing leaves the font pushed "
                        + "and it grows by one on every frame that fails");
    }
}
