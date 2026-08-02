package dev.kierandrewett.mcmarkings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Noticing that the mod's own file changed, before a class fails to load.
 *
 * <p>Somebody hit the stale jar three times in one afternoon: in the window, on a
 * pull, and typing in the search box. Each one is explained now, and explaining it
 * afterwards is the worse half of the job. The file's timestamp knows before anything
 * has broken.
 *
 * <p>Outside a running game there is no mod container to find, so the watch has
 * nothing to watch. What is checked here is that it says nothing rather than
 * throwing, guessing, or crying wolf, which is the behaviour every test run and every
 * development launch depends on.
 */
class InstallWatchTest {

    @Test
    @DisplayName("with no mod file to watch it stays quiet")
    void withoutALoaderItSaysNothing() {
        assertFalse(InstallWatch.replaced(System.currentTimeMillis()),
                "warned about a replacement with no file to compare against");
    }

    @Test
    @DisplayName("asking repeatedly is cheap and still quiet")
    void repeatedAsksAreHarmless() {
        long now = System.currentTimeMillis();
        for (int at = 0; at < 500; at++) {
            assertFalse(InstallWatch.replaced(now + at * 10L));
        }
    }

    /**
     * The wording, because it is the whole of what this is for. Naming the file or
     * the class helps nobody: what someone needs is the sentence that tells them to
     * restart, and why, in that order.
     */
    @Test
    @DisplayName("the warning says what happened and what to do")
    void theWarningIsActionable() {
        String warning = InstallWatch.warning();

        assertTrue(warning.contains("Restart Minecraft"), warning);
        assertTrue(warning.toLowerCase().contains("replaced"), warning);
        assertTrue(warning.length() < 200, "too long to read on a banner: " + warning.length());
    }
}
