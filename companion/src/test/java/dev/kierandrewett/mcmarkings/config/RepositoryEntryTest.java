package dev.kierandrewett.mcmarkings.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The id is what ties a config entry, a workspace and a registry entry together,
 * so it has to mean the same thing every time it is worked out. Two ways of
 * writing the same folder have to land on one id, and two different folders must
 * never land on the same one.
 */
class RepositoryEntryTest {

    @Test
    @DisplayName("the same folder always gets the same id")
    void idsAreStable(@TempDir Path home) {
        Path repository = home.resolve("dev/example-repo");

        assertEquals(RepositoryEntry.idFor(repository), RepositoryEntry.idFor(repository));
        assertEquals(RepositoryEntry.idFor(repository), RepositoryEntry.of(repository).id());
    }

    @Test
    @DisplayName("paths that mean the same folder get the same id")
    void equivalentPathsNormaliseToOneId(@TempDir Path home) {
        String expected = RepositoryEntry.idFor(home.resolve("dev/example-repo"));

        assertEquals(expected, RepositoryEntry.idFor(Path.of(home.resolve("dev/example-repo") + "/")));
        assertEquals(expected, RepositoryEntry.idFor(home.resolve("dev/./example-repo")));
        assertEquals(expected, RepositoryEntry.idFor(home.resolve("dev/other/../example-repo")));
        assertEquals(expected, RepositoryEntry.idFor(home.resolve("dev//example-repo")));
    }

    @Test
    @DisplayName("paths differing only in case are treated as different folders")
    void caseIsSignificant() {
        // On a case-sensitive filesystem these are genuinely two directories, and
        // folding them together would merge the map lists of unrelated
        // repositories. Where the filesystem disagrees, addRepository matches on
        // the Path itself, which is case-insensitive there, so no duplicate entry
        // is created regardless.
        assertNotEquals(RepositoryEntry.idFor(Path.of("/srv/Example-Repo")),
                RepositoryEntry.idFor(Path.of("/srv/example-repo")));
    }

    @Test
    @DisplayName("a relative path resolves against the working directory before hashing")
    void relativePathsAreMadeAbsolute() {
        assertEquals(RepositoryEntry.idFor(Path.of("").toAbsolutePath().resolve("example-repo")),
                RepositoryEntry.idFor(Path.of("example-repo")));
    }

    @Test
    @DisplayName("different folders get different ids")
    void differentFoldersNeverShareAnId(@TempDir Path home) {
        List<Path> folders = List.of(
                home.resolve("example-repo"),
                home.resolve("other-repo"),
                home.resolve("nested/example-repo"),
                home.resolve("nested/other-repo"),
                home.resolve("nested/deeper/example-repo"),
                Path.of("/srv/example-repo"),
                Path.of("/srv/signs"));

        Set<String> ids = new HashSet<>();
        for (Path folder : folders) {
            assertTrue(ids.add(RepositoryEntry.idFor(folder)), "id collision on " + folder);
        }
        assertEquals(folders.size(), ids.size());
    }

    @Test
    @DisplayName("an id is safe to use as a key and readable in a config file")
    void idsAreReadableAndPunctuationFree() {
        String id = RepositoryEntry.idFor(Path.of("/srv/My Signs (2024)/example-repo"));

        assertTrue(id.matches("[a-z0-9-]+"), id);
        assertFalse(id.startsWith("-"), id);
        assertFalse(id.endsWith("-"), id);
        assertTrue(id.contains("example-repo"), id);
    }

    /**
     * A known weakness, pinned so it is visible rather than discovered by someone
     * whose second repository quietly took over the first one's maps. Every run of
     * non-alphanumeric characters collapses to a single dash, so a separator and a
     * dash in a folder name are indistinguishable afterwards. Fixing it means
     * encoding the separator differently in {@code idFor}, which flips this test.
     */
    @Test
    @DisplayName("folders differing only in punctuation get different ids")
    void punctuationDoesNotCollapseIntoOneId() {
        // Collapsing runs of punctuation makes these two read the same, so the id
        // carries a digest of the full path. Sharing an id would merge the map
        // lists of two unrelated folders.
        assertNotEquals(RepositoryEntry.idFor(Path.of("/srv/signs/uk")),
                RepositoryEntry.idFor(Path.of("/srv/signs-uk")));
    }

    @Test
    @DisplayName("a folder with nothing usable in its name still gets an id")
    void anUnusableNameStillProducesAnId() {
        String id = RepositoryEntry.idFor(Path.of("/"));

        assertTrue(id.startsWith("repository-"), "expected a readable fallback, got " + id);
        assertNotEquals("repository-", id, "the digest must still be present");
    }

    @Test
    @DisplayName("a repository added from a folder is named after that folder")
    void ofNamesTheEntryAfterTheFolder(@TempDir Path home) {
        RepositoryEntry entry = RepositoryEntry.of(home.resolve("dev/example-repo"));

        assertEquals("example-repo", entry.name());
        assertEquals("main", entry.branch());
        assertEquals("", entry.slugOverride());
        assertEquals(home.resolve("dev/example-repo").toString(), entry.path());
        assertEquals(home.resolve("dev/example-repo"), entry.root());
    }

    @Test
    @DisplayName("a repository with no name shows its folder name instead of a blank")
    void displayNameFallsBackToTheFolder(@TempDir Path home) {
        RepositoryEntry named = RepositoryEntry.of(home.resolve("example-repo")).withName("UK Signs");

        assertEquals("UK Signs", named.displayName());
        assertEquals("example-repo", named.withName("").displayName());
        assertEquals("example-repo", named.withName("   ").displayName());
        assertEquals("example-repo", named.withName(null).displayName());
    }

    @Test
    @DisplayName("renaming or switching branch keeps the entry pointing at the same folder")
    void withersChangeOneThingOnly(@TempDir Path home) {
        RepositoryEntry original = RepositoryEntry.of(home.resolve("example-repo"));

        RepositoryEntry renamed = original.withName("UK Signs");
        RepositoryEntry rebranched = original.withBranch("release");

        assertEquals(original.id(), renamed.id());
        assertEquals(original.path(), renamed.path());
        assertEquals(original.branch(), renamed.branch());
        assertEquals(original.id(), rebranched.id());
        assertEquals(original.name(), rebranched.name());
        assertEquals("release", rebranched.branch());
        assertNotEquals(original, renamed);
    }
}
