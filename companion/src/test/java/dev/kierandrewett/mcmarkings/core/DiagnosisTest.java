package dev.kierandrewett.mcmarkings.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one failure worth explaining, explained.
 *
 * <p>Replacing the mod's jar while the game is running does not reload it. The
 * classes already in memory carry on, and the first one the mod needs that it has not
 * touched yet comes from a file that is no longer the file it was built against. What
 * surfaces is "Failed to load class file for
 * dev.kierandrewett.mcmarkings.repo.PullResult", which names a class nobody has heard
 * of and says nothing about what to do.
 *
 * <p>It came up three times in one afternoon of somebody using this while I was
 * working on it, once in the window, once on a pull and once while searching the
 * browser. Three different screens, three different error paths, and the answer is
 * the same every time.
 */
class DiagnosisTest {

    @Test
    @DisplayName("a class that will not load says to restart the game")
    void aStaleJarIsExplained() {
        String advice = Diagnosis.adviceFor(
                "Failed to load class file for 'dev.kierandrewett.mcmarkings.repo.PullResult'!");

        assertTrue(advice.contains("Restart Minecraft"), advice);
        assertTrue(advice.toLowerCase().contains("changed while the game was running"), advice);
    }

    @Test
    @DisplayName("the errors the loader itself raises are caught, not only the message")
    void theLoadersOwnFailuresAreCaught() {
        assertTrue(!Diagnosis.adviceFor(new NoClassDefFoundError("whatever")).isEmpty());
        assertTrue(!Diagnosis.adviceFor(new ClassNotFoundException("whatever")).isEmpty());

        // Wrapped, because Fabric hands some of them on inside a plain runtime one.
        assertTrue(!Diagnosis.adviceFor(
                new RuntimeException("draw failed", new NoClassDefFoundError("x"))).isEmpty());
    }

    /**
     * Silence for everything else. An explanation invented for an ordinary failure
     * is worse than the message it came with, and a note that appears on every error
     * is one nobody reads by the third time.
     */
    @Test
    @DisplayName("an ordinary failure gets no invented advice")
    void ordinaryFailuresAreLeftAlone() {
        assertEquals("", Diagnosis.adviceFor("git: could not read owner/repo from the origin remote"));
        assertEquals("", Diagnosis.adviceFor(new java.io.IOException("no such file")));
        assertEquals("", Diagnosis.adviceFor((String) null));
    }

    /**
     * A cause chain that goes round in a circle.
     *
     * <p>Both shapes, because my first guard only caught the first one. It compared
     * a cause with its own cause, which sees A pointing at A and walks forever round
     * A pointing at B pointing at A. Worth having got wrong: a diagnostic that hangs
     * turns a build into a timeout rather than a failure, and turns a game into a
     * frozen window.
     */
    @Test
    // On its own thread, so it fails rather than hangs. A same-thread timeout cannot
    // interrupt a tight loop, so without this the check for a loop is itself a loop:
    // I confirmed that by removing the bound and watching the build time out at 124
    // rather than report a failure.
    @org.junit.jupiter.api.Timeout(value = 5,
            threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("a cause chain that loops terminates, however it loops")
    void aCircularCauseTerminates() {
        RuntimeException itself = new RuntimeException("round and round") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertEquals("", Diagnosis.adviceFor(itself));

        RuntimeException first = new RuntimeException("one");
        RuntimeException second = new RuntimeException("two") {
            @Override
            public synchronized Throwable getCause() {
                return first;
            }
        };
        RuntimeException circle = new RuntimeException("start") {
            @Override
            public synchronized Throwable getCause() {
                return second;
            }
        };
        assertEquals("", Diagnosis.adviceFor(circle));
    }
}
