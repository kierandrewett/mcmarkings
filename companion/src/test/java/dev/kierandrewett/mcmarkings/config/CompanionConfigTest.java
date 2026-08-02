package dev.kierandrewett.mcmarkings.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single-repository to many-repositories move, and the list handling the GUI
 * drives afterwards.
 *
 * <p>Nothing here calls {@code load()} or {@code save()}. Both resolve their path
 * through {@code FabricLoader}, which needs a running loader that a unit test does
 * not have. Everything those two do beyond reading and writing the file is reached
 * directly instead: {@code migrate()} is package-private, and the rest is plain
 * list work on an instance.
 */
class CompanionConfigTest {

    /** Same settings {@code load()} parses with, so the shapes match what it sees. */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Test
    @DisplayName("a config from before multi-repository support keeps the folder someone set up")
    void aLegacyConfigBecomesExactlyOneRepository(@TempDir Path home) {
        Path existing = home.resolve("dev/example-repo");
        CompanionConfig config = new CompanionConfig();
        config.repoPath = existing.toString();
        config.branch = "trunk";
        config.githubSlug = "example-owner/example-repo";

        config.migrate();

        assertEquals(1, config.repositories.size());
        RepositoryEntry migrated = config.repositories.getFirst();
        assertEquals(existing.toAbsolutePath().normalize().toString(), migrated.path());
        assertEquals("example-repo", migrated.name());
        assertEquals("trunk", migrated.branch(), "the branch someone pulled from must survive");
        assertEquals("example-owner/example-repo", migrated.slugOverride(),
                "githubSlug was an override, so it stays one");
        assertEquals(migrated.id(), config.activeRepositoryId, "the migrated repository is the one in use");
    }

    @Test
    @DisplayName("the legacy fields are cleared once carried across")
    void theOldFieldsAreEmptiedAfterMigrating(@TempDir Path home) {
        CompanionConfig config = new CompanionConfig();
        config.repoPath = home.resolve("example-repo").toString();
        config.branch = "trunk";
        config.githubSlug = "example-owner/example-repo";

        config.migrate();

        assertEquals("", config.repoPath);
        assertEquals("", config.branch);
        assertEquals("", config.githubSlug);
    }

    @Test
    @DisplayName("a legacy config with no branch recorded lands on main")
    void aMissingBranchFallsBackToMain(@TempDir Path home) {
        CompanionConfig config = new CompanionConfig();
        config.repoPath = home.resolve("example-repo").toString();
        config.branch = "";
        config.githubSlug = "";

        config.migrate();

        assertEquals("main", config.repositories.getFirst().branch());
        assertEquals("", config.repositories.getFirst().slugOverride(),
                "no slug recorded means derive it from the remote, not override it with a blank");
    }

    @Test
    @DisplayName("migrating twice does not add the folder twice")
    void migratingIsIdempotent(@TempDir Path home) {
        CompanionConfig config = new CompanionConfig();
        config.repoPath = home.resolve("example-repo").toString();

        config.migrate();
        config.migrate();

        assertEquals(1, config.repositories.size());
    }

    @Test
    @DisplayName("a config already using repositories is left alone")
    void anAlreadyMigratedConfigIsUntouched(@TempDir Path home) {
        CompanionConfig config = new CompanionConfig();
        RepositoryEntry first = config.addRepository(home.resolve("first"));
        RepositoryEntry second = config.addRepository(home.resolve("second"));
        config.activeRepositoryId = second.id();

        config.migrate();

        assertEquals(List.of(first, second), config.repositories);
        assertEquals(second.id(), config.activeRepositoryId, "migrating must not move someone off their choice");
    }

    /**
     * A stray repoPath alongside a real repository list is what an install that was
     * downgraded and upgraded again looks like. Adding the old folder back would
     * resurrect something the user may have deliberately removed.
     */
    @Test
    @DisplayName("a leftover repoPath is discarded when repositories already exist")
    void aLeftoverLegacyPathDoesNotComeBack(@TempDir Path home) {
        CompanionConfig config = new CompanionConfig();
        config.addRepository(home.resolve("current"));
        config.repoPath = home.resolve("removed-ages-ago").toString();

        config.migrate();

        assertEquals(1, config.repositories.size());
        assertEquals("", config.repoPath);
    }

    @Test
    @DisplayName("a fresh config is usable with no repositories set up")
    void aFreshConfigHasWorkingDefaults() {
        CompanionConfig config = new CompanionConfig();

        config.migrate();

        assertTrue(config.repositories.isEmpty());
        assertEquals("", config.activeRepositoryId);
        assertTrue(config.active().isEmpty(), "no repositories means nothing active, not a crash");
        assertEquals("imageframe", config.commandAlias);
        assertEquals(256, config.exportPixelsPerFrame);
        assertEquals("generated", config.generatedDirectory);
        assertEquals("generators", config.generatorDirectory);
        assertNotNull(config.fontSearchPaths);
    }

    @Test
    @DisplayName("an empty config file parses into the same defaults as a fresh install")
    void anEmptyDocumentGivesDefaults() {
        CompanionConfig parsed = usable(GSON.fromJson("{}", CompanionConfig.class));

        assertEquals("imageframe", parsed.commandAlias);
        assertEquals(2.0, parsed.commandsPerSecond);
        assertTrue(parsed.repositories.isEmpty());
        assertTrue(parsed.active().isEmpty());
    }

    /**
     * An empty file parses to null rather than an object, which is why the real
     * loader substitutes a fresh config instead of handing one back.
     */
    @Test
    @DisplayName("a blank config file parses to nothing, so the loader has to substitute defaults")
    void aBlankFileParsesToNull() {
        assertNull(GSON.fromJson("", CompanionConfig.class));
        assertNull(GSON.fromJson("null", CompanionConfig.class));
    }

    /**
     * The lists are the parts a hand-edited file can null out, and every screen
     * iterates them without checking.
     */
    @Test
    @DisplayName("a config with its lists nulled out is still usable once guarded")
    void nulledListsAreRecoverable() {
        CompanionConfig parsed = usable(GSON.fromJson(
                "{ \"repositories\": null, \"fontSearchPaths\": null }", CompanionConfig.class));

        assertNotNull(parsed.repositories);
        assertNotNull(parsed.fontSearchPaths);
        assertTrue(parsed.active().isEmpty());
    }

    @Test
    @DisplayName("a corrupt config file fails as a runtime error the loader already catches")
    void corruptJsonIsARuntimeFailure() {
        assertThrows(JsonSyntaxException.class, () -> GSON.fromJson("{ not json at all", CompanionConfig.class));
        assertThrows(JsonSyntaxException.class, () -> GSON.fromJson("[1, 2, 3]", CompanionConfig.class));
    }

    @Test
    @DisplayName("adding the same folder twice returns the entry that is already there")
    void addRepositoryIsIdempotent(@TempDir Path home) {
        CompanionConfig config = new CompanionConfig();
        Path repository = home.resolve("dev/example-repo");

        RepositoryEntry first = config.addRepository(repository);
        RepositoryEntry again = config.addRepository(repository);

        assertEquals(1, config.repositories.size());
        assertSame(first, again);
    }

    @Test
    @DisplayName("the same folder written awkwardly is still the same folder")
    void addRepositoryNormalisesThePathBeforeComparing(@TempDir Path home) {
        CompanionConfig config = new CompanionConfig();
        Path repository = home.resolve("dev/example-repo");

        RepositoryEntry direct = config.addRepository(repository);
        config.addRepository(Path.of(repository + "/"));
        config.addRepository(home.resolve("dev/other/../example-repo"));
        config.addRepository(home.resolve("dev/./example-repo"));

        assertEquals(1, config.repositories.size(), "a trailing slash or a .. is a typing habit, not a new folder");
        assertEquals(direct.id(), config.repositories.getFirst().id());
    }

    @Test
    @DisplayName("the first repository added becomes the active one")
    void theFirstRepositoryBecomesActive(@TempDir Path home) {
        CompanionConfig config = new CompanionConfig();

        RepositoryEntry first = config.addRepository(home.resolve("first"));
        config.addRepository(home.resolve("second"));

        assertEquals(first.id(), config.activeRepositoryId, "adding a second must not steal focus");
        assertEquals(first, config.active().orElseThrow());
    }

    @Test
    @DisplayName("removing the active repository moves the selection to another one")
    void removingTheActiveRepositoryMovesTheSelection(@TempDir Path home) {
        CompanionConfig config = new CompanionConfig();
        RepositoryEntry first = config.addRepository(home.resolve("first"));
        RepositoryEntry second = config.addRepository(home.resolve("second"));
        config.activeRepositoryId = second.id();

        config.removeRepository(second.id());

        assertEquals(List.of(first), config.repositories);
        assertEquals(first.id(), config.activeRepositoryId);
        assertEquals(first, config.active().orElseThrow());
    }

    @Test
    @DisplayName("removing an inactive repository leaves the selection where it was")
    void removingAnInactiveRepositoryLeavesTheSelectionAlone(@TempDir Path home) {
        CompanionConfig config = new CompanionConfig();
        RepositoryEntry first = config.addRepository(home.resolve("first"));
        RepositoryEntry second = config.addRepository(home.resolve("second"));
        config.activeRepositoryId = second.id();

        config.removeRepository(first.id());

        assertEquals(second.id(), config.activeRepositoryId);
    }

    @Test
    @DisplayName("removing the last repository leaves nothing selected")
    void removingTheLastRepositoryBlanksTheSelection(@TempDir Path home) {
        CompanionConfig config = new CompanionConfig();
        RepositoryEntry only = config.addRepository(home.resolve("only"));

        config.removeRepository(only.id());

        assertTrue(config.repositories.isEmpty());
        assertEquals("", config.activeRepositoryId, "a stale id would keep pointing at a folder that is gone");
        assertTrue(config.active().isEmpty());
    }

    @Test
    @DisplayName("removing a repository that was never there changes nothing")
    void removingAnUnknownRepositoryIsANoOp(@TempDir Path home) {
        CompanionConfig config = new CompanionConfig();
        RepositoryEntry only = config.addRepository(home.resolve("only"));

        config.removeRepository("never-added");

        assertEquals(List.of(only), config.repositories);
        assertEquals(only.id(), config.activeRepositoryId);
    }

    @Test
    @DisplayName("renaming a repository updates it in place, keeping the order")
    void replaceRepositoryUpdatesInPlace(@TempDir Path home) {
        CompanionConfig config = new CompanionConfig();
        RepositoryEntry first = config.addRepository(home.resolve("first"));
        RepositoryEntry second = config.addRepository(home.resolve("second"));

        config.replaceRepository(second.withName("Signs").withBranch("release"));

        assertEquals(2, config.repositories.size());
        assertEquals(first, config.repositories.getFirst());
        RepositoryEntry updated = config.repositories.getLast();
        assertEquals("Signs", updated.name());
        assertEquals("release", updated.branch());
        assertEquals(second.id(), updated.id());
        assertEquals(second.path(), updated.path());
    }

    @Test
    @DisplayName("replacing a repository that is not in the list adds nothing")
    void replaceRepositoryIgnoresAStranger(@TempDir Path home) {
        CompanionConfig config = new CompanionConfig();
        RepositoryEntry only = config.addRepository(home.resolve("only"));

        config.replaceRepository(RepositoryEntry.of(home.resolve("elsewhere")));

        assertEquals(List.of(only), config.repositories);
    }

    @Test
    @DisplayName("an active id pointing at nothing falls back to the first repository")
    void anUnknownActiveIdFallsBackToTheFirst(@TempDir Path home) {
        CompanionConfig config = new CompanionConfig();
        RepositoryEntry first = config.addRepository(home.resolve("first"));
        config.addRepository(home.resolve("second"));
        config.activeRepositoryId = "removed-by-hand";

        assertEquals(first, config.active().orElseThrow(),
                "a hand-edited file should not leave every screen with no repository");
        assertTrue(config.byId("removed-by-hand").isEmpty());
    }

    /** The guards {@code load()} applies to whatever Gson hands back. */
    private static CompanionConfig usable(CompanionConfig parsed) {
        CompanionConfig config = parsed == null ? new CompanionConfig() : parsed;
        if (config.repositories == null) {
            config.repositories = new ArrayList<>();
        }
        if (config.fontSearchPaths == null) {
            config.fontSearchPaths = new ArrayList<>();
        }
        config.migrate();
        return config;
    }
}
