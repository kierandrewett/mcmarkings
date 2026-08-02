package dev.kierandrewett.mcmarkings.render;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The interface is not set in bold italic.
 *
 * <p>Reported by someone using it: the font is hard to read. It was "DejaVu Sans
 * Mono for Powerline". The preference list matched on a prefix, so "dejavusans"
 * matched that as happily as DejaVu Sans itself, and it came first.
 *
 * <p>Nothing failed and nothing logged. The whole interface was simply set in a face
 * meant for emphasis, which is the sort of thing that is obvious the moment somebody
 * looks and invisible to every test that checks a font was found at all.
 */
class InterfaceFontTest {

    /**
     * Mono is in this list because mono is what it actually picked.
     *
     * <p>I wrote this list from what I assumed the fault was, checked the wrong thing
     * in a shell, and told someone it was a bold italic. It was not. The prefix
     * "dejavusans" matched "DejaVu Sans Mono for Powerline", so the interface was set
     * in a monospace font with Powerline glyphs, which is why it read badly.
     *
     * <p>The bold and italic entries stay. They are the same fault waiting on a
     * machine that has those faces and not this one, and a prefix match will take
     * whichever it reaches first.
     */
    private static final List<String> WRONG_FOR_BODY_TEXT =
            List.of("italic", "oblique", "bold", "black", "heavy", "thin", "light",
                    "mono", "condensed", "narrow");

    @Test
    @DisplayName("the face chosen for the interface is a plain one")
    void theInterfaceFontIsARegularFace() {
        FontRegistry fonts = new FontRegistry(List.of(
                System.getProperty("user.home") + "/.local/share/fonts", "/usr/share/fonts"));
        Optional<Path> chosen = fonts.anyReadableFontFile();
        Assumptions.assumeTrue(chosen.isPresent(), "no scalable font on this machine");

        String name = chosen.get().getFileName().toString().toLowerCase(Locale.ROOT);
        for (String wrong : WRONG_FOR_BODY_TEXT) {
            assertTrue(!name.contains(wrong),
                    () -> "the interface would be set in " + chosen.get().getFileName()
                            + ", which is a " + wrong + " face");
        }
    }

    /**
     * That it still finds one. A filter strict enough to reject everything would
     * drop the interface back to Dear ImGui's built-in bitmap font, which is worse
     * than a bold italic and would not look like a bug.
     */
    @Test
    @DisplayName("a plain face is still found")
    void aFontIsStillChosen() {
        FontRegistry fonts = new FontRegistry(List.of(
                System.getProperty("user.home") + "/.local/share/fonts", "/usr/share/fonts"));
        Assumptions.assumeTrue(!fonts.availableFamilies().isEmpty(), "no fonts on this machine");

        assertTrue(fonts.anyReadableFontFile().isPresent(),
                "no face survived the filter, so the interface falls back to the bitmap font");
    }
}
