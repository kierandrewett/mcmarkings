package dev.kierandrewett.mcmarkings.js;

import dev.kierandrewett.mcmarkings.render.FontRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a generator says reaches the person who typed the parameters.
 *
 * <p>console.warn went only to the log. That is right for the mod's own diagnostics
 * and exactly wrong for these: a generator's warnings are addressed to whoever set
 * the parameters, and "legend measures 214px wider than the sign allows; shorten the
 * text or lower the x-height" says what to do next. It was being said into a file
 * nobody has open, so the sign came back looking wrong with nothing on screen
 * explaining why.
 *
 * <p>Found by rendering real signs and looking at them rather than by reading the
 * code. Passing a colour scheme the generator does not have produced a green sign
 * with no complaint anywhere in the interface, and the complaint existed the whole
 * time.
 */
class GeneratorNoticesTest {

    private static RhinoGeneratorRuntime runtime;

    @BeforeAll
    static void load() throws GeneratorException {
        Path repoRoot = Path.of("").toAbsolutePath().getParent();
        Assumptions.assumeTrue(repoRoot != null && Files.isDirectory(repoRoot.resolve("generators")),
                "generators/ not present, skipping");
        FontRegistry fonts = new FontRegistry(List.of(
                System.getProperty("user.home") + "/.local/share/fonts", "/usr/share/fonts"));
        runtime = new RhinoGeneratorRuntime(repoRoot, "generators", fonts);
        runtime.reload();
    }

    @Test
    @DisplayName("a scheme the generator does not have is reported, not silently swapped")
    void unknownSchemeIsReported() throws Exception {
        runtime.render("plate", Map.of("lines", "NO WAITING", "scheme", "chartreuse"));
        List<String> notices = runtime.drainNotices();

        assertTrue(notices.stream().anyMatch(notice -> notice.contains("chartreuse")),
                () -> "the generator's own complaint never came back: " + notices);
    }

    @Test
    @DisplayName("a clean render says nothing")
    void goodParametersAreQuiet() throws Exception {
        runtime.render("plate", Map.of("lines", "NO WAITING", "scheme", "green"));
        assertEquals(List.of(), runtime.drainNotices(),
                "a sign that came out right should not be commented on");
    }

    /**
     * The bit that would rot quietly. A notice left over from an earlier render
     * would be attached to a preview it has nothing to do with, which is worse than
     * saying nothing: it describes a problem that is not on screen.
     */
    @Test
    @DisplayName("notices belong to one render and do not carry into the next")
    void noticesDoNotCarryOver() throws Exception {
        runtime.render("plate", Map.of("lines", "NO WAITING", "scheme", "chartreuse"));
        assertTrue(runtime.drainNotices().size() >= 1, "expected the first render to complain");

        runtime.render("plate", Map.of("lines", "NO WAITING", "scheme", "green"));
        assertEquals(List.of(), runtime.drainNotices(), "the second render inherited the first's warning");
    }

    /**
     * The case the clear at the start of render actually exists for.
     *
     * <p>Not every caller drains. Publishing renders the sign again without asking
     * for notices, so without clearing on entry those warnings sit in the list until
     * something else drains and shows them against a preview they have nothing to do
     * with. My first version of this test drained after every render, which meant it
     * passed with the clear removed: draining clears too, so the two guards looked
     * like one. Rendering twice without draining is what tells them apart.
     */
    @Test
    @DisplayName("a render nobody asked about does not leave its warnings for the next one")
    void anUndrainedRenderDoesNotLeakIntoTheNext() throws Exception {
        runtime.drainNotices();

        runtime.render("plate", Map.of("lines", "NO WAITING", "scheme", "chartreuse"));
        // Deliberately not drained, the way publishing renders.
        runtime.render("plate", Map.of("lines", "NO WAITING", "scheme", "green"));

        assertEquals(List.of(), runtime.drainNotices(),
                "a warning from an undrained render was shown against a later, clean one");
    }

    @Test
    @DisplayName("draining twice does not repeat what was already shown")
    void drainingClears() throws Exception {
        runtime.render("plate", Map.of("lines", "NO WAITING", "scheme", "chartreuse"));
        assertTrue(runtime.drainNotices().size() >= 1);
        assertEquals(List.of(), runtime.drainNotices());
    }
}
