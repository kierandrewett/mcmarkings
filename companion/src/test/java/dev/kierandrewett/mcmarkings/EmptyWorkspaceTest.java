package dev.kierandrewett.mcmarkings;

import dev.kierandrewett.mcmarkings.js.GeneratorException;
import dev.kierandrewett.mcmarkings.repo.GitException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What happens before anything is set up.
 *
 * <p>A fresh install has no repository, and every screen can be opened in that
 * state. These pin the promise that nothing throws a null pointer out of a screen
 * that cannot explain itself: reads come back empty, and the operations that
 * genuinely cannot work refuse with a message a person can act on.
 */
class EmptyWorkspaceTest {

    @Test
    @DisplayName("an unconfigured workspace reads as empty rather than failing")
    void readsAreEmpty() {
        Workspace workspace = EmptyWorkspace.create();

        assertTrue(workspace.repo().images().isEmpty());
        assertTrue(workspace.repo().search("give way", 10).isEmpty());
        assertTrue(workspace.repo().byPath("signs/anything.png").isEmpty());
        assertTrue(workspace.generators().generators().isEmpty());
        assertTrue(workspace.generators().byId("plate").isEmpty());

        // Rescanning nothing is a no-op, not an error.
        assertDoesNotThrow(() -> workspace.repo().rescan());
        assertDoesNotThrow(() -> workspace.generators().reload());
    }

    @Test
    @DisplayName("it says it is unconfigured rather than pretending to be a repository")
    void carriesAWarning() {
        Workspace workspace = EmptyWorkspace.create();

        assertTrue(workspace.hasWarning());
        assertFalse(workspace.warning().isBlank());
        // The message has to tell someone what to actually do about it.
        assertTrue(workspace.warning().toLowerCase().contains("repositor"),
                "warning should mention repositories, got: " + workspace.warning());
    }

    @Test
    @DisplayName("git operations refuse with a message rather than a null pointer")
    void gitRefusesClearly() {
        Workspace workspace = EmptyWorkspace.create();

        for (GitCall call : List.of(
                GitCall.of("head", () -> workspace.git().head()),
                GitCall.of("pinnableCommit", () -> workspace.git().pinnableCommit()),
                GitCall.of("currentBranch", () -> workspace.git().currentBranch()),
                GitCall.of("remoteSlug", () -> workspace.git().remoteSlug()),
                GitCall.of("remoteUrl", () -> workspace.git().remoteUrl()),
                GitCall.of("rawUrls", workspace::rawUrls),
                GitCall.of("isClean", () -> workspace.git().isClean()),
                GitCall.of("pull", () -> workspace.git().pull()),
                GitCall.of("commitAndPush", () -> workspace.git().commitAndPush(List.of(Path.of("a.png")), "m")))) {

            GitException thrown = assertThrows(GitException.class, call.action()::run,
                    call.name() + " should refuse rather than return nothing");
            assertFalse(thrown.output().isBlank(), call.name() + " refused without saying why");
        }
    }

    @Test
    @DisplayName("rendering a generator refuses with a message")
    void generatorRefusesClearly() {
        Workspace workspace = EmptyWorkspace.create();

        GeneratorException thrown = assertThrows(GeneratorException.class,
                () -> workspace.generators().render("plate", Map.of()));
        assertFalse(thrown.getMessage().isBlank());
    }

    private record GitCall(String name, ThrowingRunnable action) {

        static GitCall of(String name, ThrowingRunnable action) {
            return new GitCall(name, action);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {

        void run() throws Exception;
    }
}
