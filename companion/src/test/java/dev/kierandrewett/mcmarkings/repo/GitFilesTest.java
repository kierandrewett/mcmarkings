package dev.kierandrewett.mcmarkings.repo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These read .git as plain files, without running git once.
 *
 * <p>That is the whole point: a Flatpak Prism Launcher has no git binary in its
 * runtime and no sandbox permission can add one, so this is what keeps browsing
 * and placing signs working there.
 */
class GitFilesTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void readsHeadBranchAndSlugFromLooseRefs(@TempDir Path root) throws IOException {
        Path git = fakeRepo(root, "ref: refs/heads/main");
        Files.createDirectories(git.resolve("refs/heads"));
        Files.writeString(git.resolve("refs/heads/main"), SHA + "\n");

        GitFiles files = GitFiles.at(root).orElseThrow();

        assertEquals(SHA, files.head().orElseThrow());
        assertEquals("main", files.currentBranch().orElseThrow());
        assertEquals("example-owner/example-repo", files.remoteSlug().orElseThrow());
    }

    @Test
    void fallsBackToPackedRefsWhenTheLooseRefIsGone(@TempDir Path root) throws IOException {
        // git gc packs refs away and deletes the loose file.
        Path git = fakeRepo(root, "ref: refs/heads/main");
        Files.writeString(git.resolve("packed-refs"),
                "# pack-refs with: peeled fully-peeled sorted\n"
                        + SHA + " refs/heads/main\n"
                        + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa refs/remotes/origin/main\n");

        assertEquals(SHA, GitFiles.at(root).orElseThrow().head().orElseThrow());
    }

    @Test
    void readsDetachedHead(@TempDir Path root) throws IOException {
        fakeRepo(root, SHA);

        GitFiles files = GitFiles.at(root).orElseThrow();

        assertEquals(SHA, files.head().orElseThrow());
        assertTrue(files.currentBranch().isEmpty(), "a detached HEAD has no branch");
    }

    @Test
    void followsAGitFilePointingElsewhere(@TempDir Path root) throws IOException {
        // Worktrees and submodules keep .git as a file containing a gitdir pointer.
        Path real = root.resolve("elsewhere");
        Files.createDirectories(real.resolve("refs/heads"));
        Files.writeString(real.resolve("HEAD"), "ref: refs/heads/main\n");
        Files.writeString(real.resolve("refs/heads/main"), SHA + "\n");
        Files.writeString(real.resolve("config"), config());

        Path tree = root.resolve("tree");
        Files.createDirectories(tree);
        Files.writeString(tree.resolve(".git"), "gitdir: " + real.toAbsolutePath() + "\n");

        GitFiles files = GitFiles.at(tree).orElseThrow();

        assertEquals(SHA, files.head().orElseThrow());
        assertEquals("example-owner/example-repo", files.remoteSlug().orElseThrow());
    }

    @Test
    void parsesTheScpStyleRemoteToo(@TempDir Path root) throws IOException {
        Path git = fakeRepo(root, SHA);
        Files.writeString(git.resolve("config"),
                "[remote \"origin\"]\n\turl = git@github.com:example-owner/example-repo.git\n");

        assertEquals("example-owner/example-repo", GitFiles.at(root).orElseThrow().remoteSlug().orElseThrow());
    }

    /**
     * The pinning case. Local HEAD moving ahead of origin is the normal state of a
     * repository someone is working in, and a URL built from it is a 404 for the
     * server, so the remote-tracking ref is what counts.
     */
    @Test
    void readsTheRemoteHeadSeparatelyFromLocalHead(@TempDir Path root) throws IOException {
        String pushed = "1111111111111111111111111111111111111111";
        Path git = fakeRepo(root, "ref: refs/heads/main");
        Files.createDirectories(git.resolve("refs/heads"));
        Files.createDirectories(git.resolve("refs/remotes/origin"));
        Files.writeString(git.resolve("refs/heads/main"), SHA + "\n");
        Files.writeString(git.resolve("refs/remotes/origin/main"), pushed + "\n");

        GitFiles files = GitFiles.at(root).orElseThrow();

        assertEquals(SHA, files.head().orElseThrow(), "local HEAD");
        assertEquals(pushed, files.remoteHead("main").orElseThrow(), "should not follow local HEAD");
    }

    @Test
    void findsTheRemoteHeadInPackedRefsToo(@TempDir Path root) throws IOException {
        Path git = fakeRepo(root, "ref: refs/heads/main");
        Files.writeString(git.resolve("packed-refs"),
                "# pack-refs with: peeled fully-peeled sorted\n"
                        + SHA + " refs/heads/main\n"
                        + "2222222222222222222222222222222222222222 refs/remotes/origin/main\n");

        assertEquals("2222222222222222222222222222222222222222",
                GitFiles.at(root).orElseThrow().remoteHead("main").orElseThrow());
    }

    @Test
    void remoteHeadIsEmptyForABranchThatWasNeverPushed(@TempDir Path root) throws IOException {
        Path git = fakeRepo(root, "ref: refs/heads/main");
        Files.createDirectories(git.resolve("refs/heads"));
        Files.writeString(git.resolve("refs/heads/main"), SHA + "\n");

        assertTrue(GitFiles.at(root).orElseThrow().remoteHead("main").isEmpty());
    }

    @Test
    void returnsEmptyRatherThanGuessingWhenThereIsNoRepository(@TempDir Path root) {
        assertTrue(GitFiles.at(root).isEmpty());
    }

    @Test
    void survivesATruncatedOrGarbageRepository(@TempDir Path root) throws IOException {
        Path git = root.resolve(".git");
        Files.createDirectories(git);
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/missing\n");

        GitFiles files = GitFiles.at(root).orElseThrow();

        // The branch name is still readable, but nothing resolves to a commit and
        // that has to come back empty rather than as a bogus sha.
        assertEquals("missing", files.currentBranch().orElseThrow());
        assertTrue(files.head().isEmpty());
        assertTrue(files.remoteSlug().isEmpty());
    }

    /** The real repository this mod ships alongside, read without invoking git. */
    @Test
    void readsThisActualRepository() {
        Path repoRoot = Path.of("").toAbsolutePath().getParent();
        Optional<GitFiles> files = GitFiles.at(repoRoot);
        if (files.isEmpty()) {
            return;
        }

        String head = files.get().head().orElseThrow(() -> new AssertionError("no HEAD in " + repoRoot));
        assertEquals(40, head.length(), "HEAD should be a full sha, got " + head);
        assertFalse(files.get().remoteSlug().orElseThrow().isBlank());
    }

    private static Path fakeRepo(Path root, String headContents) throws IOException {
        Path git = root.resolve(".git");
        Files.createDirectories(git);
        Files.writeString(git.resolve("HEAD"), headContents + "\n");
        Files.writeString(git.resolve("config"), config());
        return git;
    }

    private static String config() {
        return """
                [core]
                	repositoryformatversion = 0
                [remote "origin"]
                	url = https://github.com/example-owner/example-repo
                	fetch = +refs/heads/*:refs/remotes/origin/*
                [branch "main"]
                	remote = origin
                """;
    }
}
