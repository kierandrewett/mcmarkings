package dev.kierandrewett.mcmarkings;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the folder picker tells someone before they commit to adding a folder.
 *
 * <p>The split that matters is {@code usable()} against {@code readyToPlace()}.
 * Being strict at the point of adding would turn "I have not generated anything
 * into it yet" into a refusal, so only a folder that is not there gets turned
 * away, and everything else is explained through the notes instead.
 */
class RepositoryCheckTest {

    @Test
    @DisplayName("a folder that is not there is refused, and says so")
    void aMissingFolderIsNotUsable(@TempDir Path root) {
        RepositoryCheck check = RepositoryCheck.inspect(root.resolve("never-created"));

        assertFalse(check.exists());
        assertFalse(check.readable());
        assertFalse(check.usable());
        assertFalse(check.readyToPlace());
        assertTrue(noteContaining(check, "does not exist"), check.notes().toString());
    }

    @Test
    @DisplayName("no folder at all is refused rather than throwing")
    void aNullPathIsRefused() {
        RepositoryCheck check = RepositoryCheck.inspect(null);

        assertFalse(check.usable());
        assertTrue(noteContaining(check, "does not exist"), check.notes().toString());
    }

    @Test
    @DisplayName("picking a file instead of a folder is caught and named")
    void aFileIsNotAFolder(@TempDir Path root) throws IOException {
        Path file = root.resolve("zebra.png");
        Files.writeString(file, "not really a png", StandardCharsets.UTF_8);

        RepositoryCheck check = RepositoryCheck.inspect(file);

        assertTrue(check.exists());
        assertFalse(check.readable());
        assertFalse(check.usable());
        assertFalse(check.readyToPlace());
        assertTrue(noteContaining(check, "a file, not a folder"), check.notes().toString());
    }

    @Test
    @DisplayName("an empty folder can still be added, with everything missing explained")
    void anEmptyFolderIsUsableButNotReady(@TempDir Path root) {
        RepositoryCheck check = RepositoryCheck.inspect(root);

        assertTrue(check.usable(), "adding a folder you intend to generate into is a normal thing to do");
        assertFalse(check.readyToPlace());
        assertFalse(check.isGitRepository());
        assertFalse(check.hasRemote());
        assertEquals("", check.remoteSlug());
        assertEquals(0, check.imageCount());
        assertFalse(check.hasGenerators());
        assertTrue(noteContaining(check, "Not a git repository"), check.notes().toString());
        assertTrue(noteContaining(check, "No PNGs found yet"), check.notes().toString());
        assertTrue(noteContaining(check, "No generators folder"), check.notes().toString());
    }

    @Test
    @DisplayName("a folder of images with no git is browsable but cannot place anything")
    void imagesWithoutGitAreBrowsableOnly(@TempDir Path root) throws IOException {
        images(root, 3);

        RepositoryCheck check = RepositoryCheck.inspect(root);

        assertTrue(check.usable());
        assertFalse(check.readyToPlace(), "the server fetches over HTTP, so a local-only folder cannot work");
        assertFalse(check.isGitRepository());
        assertEquals(3, check.imageCount());
        assertTrue(noteContaining(check, "Not a git repository"), check.notes().toString());
        assertFalse(noteContaining(check, "No PNGs found yet"), "there are PNGs, so do not claim otherwise");
    }

    @Test
    @DisplayName("PNGs in subfolders are counted too")
    void imagesAreFoundBelowTheRoot(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("signs/regulatory"));
        Files.writeString(root.resolve("signs/give_way.png"), "x");
        Files.writeString(root.resolve("signs/regulatory/stop.PNG"), "x");
        Files.writeString(root.resolve("signs/notes.txt"), "x");

        assertEquals(2, RepositoryCheck.inspect(root).imageCount());
    }

    @Test
    @DisplayName("a real repository with a remote and images is ready to place signs")
    void aRealRepositoryWithARemoteIsReady(@TempDir Path root) throws Exception {
        gitInit(root);
        addOriginRemote(root, "https://github.com/example-owner/example-repo.git");
        images(root, 2);
        Files.createDirectories(root.resolve("generators"));

        RepositoryCheck check = RepositoryCheck.inspect(root);

        assertTrue(check.usable());
        assertTrue(check.isGitRepository());
        assertTrue(check.hasRemote());
        assertEquals("example-owner/example-repo", check.remoteSlug());
        assertEquals(2, check.imageCount());
        assertTrue(check.hasGenerators());
        assertTrue(check.readyToPlace());
        assertTrue(check.notes().isEmpty(), "nothing is wrong, so say nothing: " + check.notes());
    }

    @Test
    @DisplayName("a real repository with no remote explains why placing will not work")
    void aRealRepositoryWithoutARemoteIsNotReady(@TempDir Path root) throws Exception {
        gitInit(root);
        images(root, 1);

        RepositoryCheck check = RepositoryCheck.inspect(root);

        assertTrue(check.usable());
        assertTrue(check.isGitRepository());
        assertFalse(check.hasRemote());
        assertEquals("", check.remoteSlug());
        assertFalse(check.readyToPlace());
        assertTrue(noteContaining(check, "No origin remote"), check.notes().toString());
        assertFalse(noteContaining(check, "Not a git repository"),
                "it is a repository, so the note about not being one would be wrong");
    }

    @Test
    @DisplayName("a repository with a remote but no images is still not ready")
    void aRealRepositoryWithNoImagesIsNotReady(@TempDir Path root) throws Exception {
        gitInit(root);
        addOriginRemote(root, "git@github.com:example-owner/example-repo.git");

        RepositoryCheck check = RepositoryCheck.inspect(root);

        assertTrue(check.usable());
        assertTrue(check.hasRemote());
        assertEquals("example-owner/example-repo", check.remoteSlug());
        assertFalse(check.readyToPlace());
        assertTrue(noteContaining(check, "No PNGs found yet"), check.notes().toString());
    }

    private static boolean noteContaining(RepositoryCheck check, String fragment) {
        return check.notes().stream().anyMatch(note -> note.contains(fragment));
    }

    private static void images(Path root, int count) throws IOException {
        for (int index = 0; index < count; index++) {
            Files.writeString(root.resolve("sign_" + index + ".png"), "x", StandardCharsets.UTF_8);
        }
    }

    /**
     * A throwaway repository in a temp directory. Skipped rather than failed when
     * there is no git binary, because the mod is built to work without one and the
     * machine running the tests may not have it either.
     */
    private static void gitInit(Path root) throws IOException, InterruptedException {
        Process process;
        try {
            process = new ProcessBuilder("git", "init", root.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException exception) {
            Assumptions.abort("no git binary available: " + exception.getMessage());
            return;
        }
        Assumptions.assumeTrue(process.waitFor(60, TimeUnit.SECONDS), "git init did not finish");
        Assumptions.assumeTrue(process.exitValue() == 0, "git init failed");
        Assumptions.assumeTrue(Files.isDirectory(root.resolve(".git")), "git init produced no .git");
    }

    /**
     * Written straight into the fixture's own config file rather than run through
     * git, so nothing in this suite can touch git configuration anywhere.
     */
    private static void addOriginRemote(Path root, String url) throws IOException {
        Files.writeString(root.resolve(".git/config"),
                "\n[remote \"origin\"]\n\turl = " + url
                        + "\n\tfetch = +refs/heads/*:refs/remotes/origin/*\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }
}
