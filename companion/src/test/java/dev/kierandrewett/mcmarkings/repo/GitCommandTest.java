package dev.kierandrewett.mcmarkings.repo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape of the git invocation, which differs inside a Flatpak sandbox.
 *
 * <p>Worth pinning because both details fail in ways that are hard to read: a
 * misplaced {@code -C} makes git complain about an unknown option to a subcommand,
 * and a missing one silently operates on whatever directory the process happened
 * to start in.
 */
class GitCommandTest {

    private static final Path ROOT = Path.of("/srv/example-repo");

    @Test
    @DisplayName("outside a sandbox git is invoked directly")
    void plainInvocation() {
        List<String> command = ProcessGitService.buildCommand(false, ROOT, "rev-parse", "HEAD");

        assertEquals(List.of("git", "-C", ROOT.toString(), "rev-parse", "HEAD"), command);
    }

    @Test
    @DisplayName("inside a sandbox the call is handed to the host")
    void sandboxedInvocation() {
        List<String> command = ProcessGitService.buildCommand(true, ROOT, "rev-parse", "HEAD");

        assertEquals(
                List.of("flatpak-spawn", "--host", "git", "-C", ROOT.toString(), "rev-parse", "HEAD"),
                command);
    }

    @Test
    @DisplayName("-C comes before the subcommand, never after")
    void repositoryOptionPrecedesTheSubcommand() {
        for (boolean sandboxed : new boolean[] { false, true }) {
            List<String> command = ProcessGitService.buildCommand(sandboxed, ROOT, "status", "--porcelain");

            int dashC = command.indexOf("-C");
            int subcommand = command.indexOf("status");
            assertTrue(dashC >= 0, "-C missing, git would run against the wrong directory");
            assertTrue(dashC < subcommand,
                    "-C is a git option, not a subcommand argument; sandboxed=" + sandboxed);
            assertEquals(ROOT.toString(), command.get(dashC + 1));
        }
    }

    @Test
    @DisplayName("arguments are passed through untouched, never through a shell")
    void argumentsArePassedThroughVerbatim() {
        // Commit messages are generated from sign descriptions and can hold quotes,
        // spaces and semicolons. These stay single argv entries or this becomes a
        // command injection.
        String message = "feat(generated): add \"give way\"; rm -rf /";
        List<String> command = ProcessGitService.buildCommand(true, ROOT, "commit", "-m", message);

        assertEquals(message, command.getLast());
        assertFalse(command.contains("sh"), "no shell should ever appear in the command");
        assertFalse(command.contains("-c"), "no shell should ever appear in the command");
    }

    @Test
    @DisplayName("a bare invocation still targets the repository")
    void bareInvocationStillCarriesTheRepository() {
        List<String> command = ProcessGitService.buildCommand(true, ROOT, "--version");

        assertEquals(
                List.of("flatpak-spawn", "--host", "git", "-C", ROOT.toString(), "--version"),
                command);
    }
}
