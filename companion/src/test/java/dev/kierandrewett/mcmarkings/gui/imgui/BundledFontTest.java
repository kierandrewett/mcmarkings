package dev.kierandrewett.mcmarkings.gui.imgui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The interface font travels with the mod.
 *
 * <p>Minecraft's own font is a bitmap in ascii.png and its unicode pages, which
 * nothing here can load into an ImGui atlas. Monocraft is a scalable recreation of
 * the same shapes, so it is bundled rather than looked for: the alternative is
 * telling somebody to install a font before their interface reads properly, and most
 * people will not.
 *
 * <p>Under the SIL Open Font License, which permits this and requires the licence to
 * travel with it. That is checked here too, because a licence file is exactly the
 * sort of thing that gets dropped in a resource reshuffle and nothing notices.
 */
class BundledFontTest {

    private static final String FONT = "/assets/mcmarkings/font/Monocraft.ttf";

    private static final String LICENCE = "/assets/mcmarkings/font/Monocraft-LICENSE.txt";

    @Test
    @DisplayName("the font is on the classpath and is really a font")
    void theFontShips() throws IOException {
        try (InputStream stream = BundledFontTest.class.getResourceAsStream(FONT)) {
            assertNotNull(stream, "the bundled font is missing, so the interface falls back");
            byte[] bytes = stream.readAllBytes();

            assertTrue(bytes.length > 50_000, "only " + bytes.length + " bytes, which is not a font");

            // TrueType begins with 0x00010000, the version, rather than a text marker.
            // Worth checking: an error page saved under a .ttf name is nine bytes of
            // ASCII, which is exactly what the first download of this turned out to be.
            assertEquals(0x00, bytes[0]);
            assertEquals(0x01, bytes[1]);
            assertEquals(0x00, bytes[2]);
            assertEquals(0x00, bytes[3]);
        }
    }

    @Test
    @DisplayName("its licence ships with it")
    void theLicenceShips() throws IOException {
        try (InputStream stream = BundledFontTest.class.getResourceAsStream(LICENCE)) {
            assertNotNull(stream, "the font is bundled without its licence");
            String text = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

            assertTrue(text.contains("SIL OPEN FONT LICENSE"),
                    "this is not the licence the font is under");
            assertTrue(text.contains("Idrees Hassan"), "the copyright holder is not named");
        }
    }
}
