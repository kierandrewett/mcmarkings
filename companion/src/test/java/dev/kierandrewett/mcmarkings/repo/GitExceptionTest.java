package dev.kierandrewett.mcmarkings.repo;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * What a git failure tells someone.
 *
 * <p>Every one of these reaches a status line verbatim, so the only real requirement
 * is that there is always something there. Three of the four places that report one
 * used to append git's output and nothing else, which is empty exactly when the
 * failure is not git speaking, and that is the most likely failure on a machine that
 * has no git at all.
 */
class GitExceptionTest {

    @Test
    void prefersWhatGitPrinted() {
        GitException failure = new GitException("push", 1, "rejected: non-fast-forward");

        assertEquals("rejected: non-fast-forward", failure.describe());
    }

    @Test
    void usesTheCausesOwnWordsWhenGitDidNotRun() {
        // A failed process rather than git disagreeing with you. I expected the
        // wrapped sentence here and it is the cause's own message: the constructor
        // already copies it into the output, so describe finds it there and returns
        // it without the "git pull could not be run" preamble. Better than what I
        // assumed, and worth pinning now that I know which it is.
        GitException failure = new GitException("pull", new IOException("Cannot run program \"git\""));

        assertEquals("Cannot run program \"git\"", failure.describe());
    }

    @Test
    void neverEmpty() {
        // The case the fallback exists for: no output and no message either.
        GitException silent = new GitException("status", 1, "");
        GitException causeless = new GitException("status", new IOException());

        assertFalse(silent.describe().isBlank(), "a blank message tells nobody anything");
        assertFalse(causeless.describe().isBlank(), "and neither does this one");
    }
}
