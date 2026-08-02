package dev.kierandrewett.mcmarkings.repo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ProcessGitServiceTest {

    @Test
    void parsesHttpsRemotes() {
        assertEquals("example-owner/example-repo",
                ProcessGitService.parseSlug("https://github.com/example-owner/example-repo.git"));
        assertEquals("example-owner/example-repo",
                ProcessGitService.parseSlug("https://github.com/example-owner/example-repo"));
        assertEquals("example-owner/example-repo",
                ProcessGitService.parseSlug("https://github.com/example-owner/example-repo/"));
        assertEquals("example-owner/example-repo",
                ProcessGitService.parseSlug("  https://github.com/example-owner/example-repo.git\n"));
    }

    @Test
    void parsesScpStyleSshRemotes() {
        assertEquals("example-owner/example-repo",
                ProcessGitService.parseSlug("git@github.com:example-owner/example-repo.git"));
        assertEquals("example-owner/example-repo",
                ProcessGitService.parseSlug("git@github.com:example-owner/example-repo"));
    }

    @Test
    void parsesSshUrlAndTokenRemotes() {
        assertEquals("example-owner/example-repo",
                ProcessGitService.parseSlug("ssh://git@github.com/example-owner/example-repo.git"));
        assertEquals("example-owner/example-repo",
                ProcessGitService.parseSlug("https://token@github.com/example-owner/example-repo.git"));
    }

    @Test
    void rejectsRemotesWithNoOwnerAndRepo() {
        assertNull(ProcessGitService.parseSlug(null));
        assertNull(ProcessGitService.parseSlug("   "));
        assertNull(ProcessGitService.parseSlug("https://github.com/"));
        assertNull(ProcessGitService.parseSlug("git@github.com:mcmarkings.git"));
    }

    @Test
    void refusesADirectoryThatIsNotARepository(@TempDir Path directory) {
        ProcessGitService git = new ProcessGitService(directory);

        GitException exception = assertThrows(GitException.class, git::head);

        assertTrue(exception.getMessage().toLowerCase().contains("not a git repository")
                        || exception.getMessage().toLowerCase().contains("not a git work tree"),
                "expected git's own wording, got: " + exception.getMessage());
    }

    @Test
    void refusesAMissingDirectory(@TempDir Path directory) {
        ProcessGitService git = new ProcessGitService(directory.resolve("nowhere"));

        GitException exception = assertThrows(GitException.class, git::head);

        assertTrue(exception.output().contains("not a directory"), exception.output());
    }

    @Test
    void readsHeadBranchAndRemoteFromARealRepository(@TempDir Path directory) throws Exception {
        assumeTrue(gitAvailable(), "git is not on PATH");
        Path repository = newRepository(directory, "sandbox");
        assumeTrue(commit(repository, "first"), "no git identity available for a test commit");
        run(repository, "remote", "add", "origin", "git@github.com:example-owner/example-repo.git");

        ProcessGitService git = new ProcessGitService(repository);

        assertEquals(40, git.head().length());
        assertFalse(git.currentBranch().isBlank());
        assertEquals("example-owner/example-repo", git.remoteSlug());
        assertTrue(git.isClean());
    }

    @Test
    void reportsADirtyTree(@TempDir Path directory) throws Exception {
        assumeTrue(gitAvailable(), "git is not on PATH");
        Path repository = newRepository(directory, "sandbox");
        assumeTrue(commit(repository, "first"), "no git identity available for a test commit");

        Files.writeString(repository.resolve("dirty.txt"), "unstaged", StandardCharsets.UTF_8);

        assertFalse(new ProcessGitService(repository).isClean());
    }

    @Test
    void pullWithNothingToFetchLeavesHeadAlone(@TempDir Path directory) throws Exception {
        assumeTrue(gitAvailable(), "git is not on PATH");
        Path origin = newRepository(directory, "origin");
        assumeTrue(commit(origin, "first"), "no git identity available for a test commit");

        Path clone = directory.resolve("clone");
        run(directory, "clone", origin.toString(), clone.toString());

        ProcessGitService git = new ProcessGitService(clone);
        PullResult result = git.pull();

        assertFalse(result.changed());
        assertEquals(result.oldHead(), result.newHead());
        assertTrue(result.changedPaths().isEmpty());
    }

    @Test
    void pullReportsOnlyChangedPngs(@TempDir Path directory) throws Exception {
        assumeTrue(gitAvailable(), "git is not on PATH");
        Path origin = newRepository(directory, "origin");
        assumeTrue(commit(origin, "first"), "no git identity available for a test commit");

        Path clone = directory.resolve("clone");
        run(directory, "clone", origin.toString(), clone.toString());

        Files.write(origin.resolve("zebra.png"), new byte[] { 1, 2, 3 });
        Files.writeString(origin.resolve("notes.txt"), "not an image", StandardCharsets.UTF_8);
        run(origin, "add", "--", "zebra.png", "notes.txt");
        assumeTrue(commit(origin, "add an image"), "no git identity available for a test commit");

        PullResult result = new ProcessGitService(clone).pull();

        assertTrue(result.changed());
        assertNotEquals(result.oldHead(), result.newHead());
        assertEquals(List.of("zebra.png"), result.changedPaths());
    }

    @Test
    void commitAndPushStagesOnlyTheGivenFiles(@TempDir Path directory) throws Exception {
        assumeTrue(gitAvailable(), "git is not on PATH");
        Path origin = newBareRepository(directory, "origin.git");
        Path clone = directory.resolve("clone");
        run(directory, "clone", origin.toString(), clone.toString());
        Files.writeString(clone.resolve("seed.txt"), "seed", StandardCharsets.UTF_8);
        run(clone, "add", "--", "seed.txt");
        assumeTrue(commit(clone, "seed"), "no git identity available for a test commit");
        run(clone, "push", "-u", "origin", "HEAD");

        Files.write(clone.resolve("wanted.png"), new byte[] { 9 });
        Files.writeString(clone.resolve("unrelated.txt"), "leave me alone", StandardCharsets.UTF_8);

        ProcessGitService git = new ProcessGitService(clone);
        String head = git.commitAndPush(List.of(Path.of("wanted.png")), "feat(test): add an image");

        assertEquals(40, head.length());
        assertTrue(status(clone).contains("unrelated.txt"), "the unrelated file should still be untracked");
        assertFalse(status(clone).contains("wanted.png"), "the requested file should have been committed");
    }

    @Test
    void commitAndPushRejectsAnEmptyFileList(@TempDir Path directory) throws Exception {
        assumeTrue(gitAvailable(), "git is not on PATH");
        Path repository = newRepository(directory, "sandbox");
        assumeTrue(commit(repository, "first"), "no git identity available for a test commit");

        ProcessGitService git = new ProcessGitService(repository);

        assertThrows(GitException.class, () -> git.commitAndPush(List.of(), "message"));
        assertThrows(GitException.class, () -> git.commitAndPush(List.of(Path.of("a.png")), "  "));
    }

    private static Path newRepository(Path parent, String name) throws Exception {
        Path repository = Files.createDirectories(parent.resolve(name));
        run(parent, "init", "--initial-branch=main", repository.toString());
        return repository;
    }

    private static Path newBareRepository(Path parent, String name) throws Exception {
        Path repository = parent.resolve(name);
        run(parent, "init", "--bare", "--initial-branch=main", repository.toString());
        return repository;
    }

    /**
     * Commits, returning false when git has no identity to commit with. The suite
     * never writes git configuration, so a machine without a global identity simply
     * skips the tests that need real commits.
     */
    private static boolean commit(Path repository, String message) throws Exception {
        Files.writeString(repository.resolve(message.replace(' ', '_') + ".txt"), message, StandardCharsets.UTF_8);
        run(repository, "add", "--all");
        return runAllowingFailure(repository, "commit", "-m", message) == 0;
    }

    private static String status(Path repository) throws Exception {
        return run(repository, "status", "--porcelain");
    }

    private static boolean gitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version").start();
            return process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException exception) {
            return false;
        }
    }

    private static String run(Path workingDirectory, String... arguments) throws Exception {
        StringBuilder output = new StringBuilder();
        int exitCode = execute(workingDirectory, output, arguments);
        if (exitCode != 0) {
            throw new IllegalStateException("git " + String.join(" ", arguments) + " failed: " + output);
        }
        return output.toString();
    }

    private static int runAllowingFailure(Path workingDirectory, String... arguments) throws Exception {
        return execute(workingDirectory, new StringBuilder(), arguments);
    }

    private static int execute(Path workingDirectory, StringBuilder output, String... arguments) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));

        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        output.append(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        process.waitFor(60, TimeUnit.SECONDS);
        return process.exitValue();
    }
}
