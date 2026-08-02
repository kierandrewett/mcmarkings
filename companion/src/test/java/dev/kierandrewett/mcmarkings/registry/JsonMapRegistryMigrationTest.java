package dev.kierandrewett.mcmarkings.registry;

import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.core.MapEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The version 1 to version 2 move, which added a repository to every entry.
 *
 * <p>This file is the only record of which ImageFrame maps exist on a server, and
 * there is no way to rebuild it from the server side. So the bar for the migration
 * is not "mostly works": an old file has to come back with every entry intact, and
 * anything the reader cannot make sense of has to stop the load rather than get
 * quietly dropped on the next save.
 */
class JsonMapRegistryMigrationTest {

    /** Exactly what version 1 wrote: no repositoryId anywhere. */
    private static final String LEGACY_FILE = """
            {
              "version": 1,
              "entries": [
                {
                  "imageFrameName": "zebra_1",
                  "repoPath": "zebra.png",
                  "grid": { "columns": 2, "rows": 1 },
                  "commitSha": "abc1234",
                  "createdAtEpochMillis": 1700000000000
                },
                {
                  "imageFrameName": "give_way_1",
                  "repoPath": "signs/give_way.png",
                  "grid": { "columns": 1, "rows": 1 },
                  "commitSha": "def5678",
                  "createdAtEpochMillis": 1
                }
              ]
            }
            """;

    @Test
    @DisplayName("an old file loads with every entry still there")
    void legacyEntriesSurviveTheRead(@TempDir Path directory) throws IOException {
        Path file = write(directory, LEGACY_FILE);
        JsonMapRegistry registry = new JsonMapRegistry(file);

        registry.load();

        assertEquals(2, registry.all().size());
        MapEntry zebra = registry.byName("zebra_1").orElseThrow();
        assertEquals("zebra.png", zebra.repoPath());
        assertEquals(new GridSize(2, 1), zebra.grid());
        assertEquals("abc1234", zebra.commitSha());
        assertEquals(1_700_000_000_000L, zebra.createdAtEpochMillis());
    }

    @Test
    @DisplayName("an entry with no repository is marked unknown rather than guessed at")
    void legacyEntriesAreMarkedRatherThanAttachedToARepository(@TempDir Path directory) throws IOException {
        Path file = write(directory, LEGACY_FILE);
        JsonMapRegistry registry = new JsonMapRegistry(file);

        registry.load();

        for (MapEntry entry : registry.all()) {
            assertEquals(MapRegistry.UNKNOWN_REPOSITORY, entry.repositoryId(),
                    "guessing a repository would point refreshes at the wrong PNG once a second one is added");
        }
    }

    @Test
    @DisplayName("migrated entries are still found by repo path, so a pull still refreshes them")
    void byRepoPathStillFindsMigratedEntries(@TempDir Path directory) throws IOException {
        Path file = write(directory, LEGACY_FILE);
        JsonMapRegistry registry = new JsonMapRegistry(file);

        registry.load();

        assertEquals(List.of("zebra_1"),
                registry.byRepoPath("zebra.png").stream().map(MapEntry::imageFrameName).toList());
        assertEquals(List.of("give_way_1"),
                registry.byRepoPath("signs/give_way.png").stream().map(MapEntry::imageFrameName).toList());
    }

    @Test
    @DisplayName("migrated entries are listed together so the GUI can offer to adopt them")
    void byRepositoryGroupsTheUnclaimedEntries(@TempDir Path directory) throws IOException {
        Path file = write(directory, LEGACY_FILE);
        JsonMapRegistry registry = new JsonMapRegistry(file);
        registry.load();
        registry.put(new MapEntry("stop_1", "example-repo", "signs/stop.png", new GridSize(1, 1), "aaa", 9L));

        assertEquals(List.of("give_way_1", "zebra_1"),
                registry.byRepository(MapRegistry.UNKNOWN_REPOSITORY).stream()
                        .map(MapEntry::imageFrameName).toList());
        assertEquals(List.of("stop_1"),
                registry.byRepository("example-repo").stream().map(MapEntry::imageFrameName).toList());
        assertTrue(registry.byRepository("never-added").isEmpty());
        assertTrue(registry.byRepository(null).isEmpty());
    }

    @Test
    @DisplayName("reading an old file rewrites it as version 2")
    void theFileIsUpgradedOnDisk(@TempDir Path directory) throws IOException {
        Path file = write(directory, LEGACY_FILE);

        new JsonMapRegistry(file).load();

        String rewritten = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(rewritten.contains("\"version\": 2"), rewritten);
        assertTrue(rewritten.contains("\"repositoryId\": \"" + MapRegistry.UNKNOWN_REPOSITORY + "\""), rewritten);
        assertTrue(rewritten.endsWith("\n"), "the file should still end with a newline");

        // Reading it back has nothing left to migrate and must not disturb anything.
        JsonMapRegistry reloaded = new JsonMapRegistry(file);
        reloaded.load();
        assertEquals(2, reloaded.all().size());
        assertEquals(rewritten, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("an upgraded file round-trips without losing anything")
    void theMigratedFileRoundTrips(@TempDir Path directory) throws IOException {
        Path file = write(directory, LEGACY_FILE);
        JsonMapRegistry migrated = new JsonMapRegistry(file);
        migrated.load();

        JsonMapRegistry reloaded = new JsonMapRegistry(file);
        reloaded.load();

        assertEquals(migrated.all(), reloaded.all());
    }

    /**
     * A version field is a promise the writer knew about the reader. A file with no
     * version at all predates that promise, so it gets the same treatment as an
     * explicit version 1 rather than being read as whatever is current.
     */
    @Test
    @DisplayName("a file written before the version field is treated as the oldest format")
    void anUnversionedFileIsMigratedToo(@TempDir Path directory) throws IOException {
        Path file = write(directory, """
                {
                  "entries": [
                    {
                      "imageFrameName": "zebra_1",
                      "repoPath": "zebra.png",
                      "grid": { "columns": 1, "rows": 1 },
                      "commitSha": "abc1234",
                      "createdAtEpochMillis": 1
                    }
                  ]
                }
                """);
        JsonMapRegistry registry = new JsonMapRegistry(file);

        registry.load();

        assertEquals(MapRegistry.UNKNOWN_REPOSITORY, registry.byName("zebra_1").orElseThrow().repositoryId());
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("\"version\": 2"));
    }

    @Test
    @DisplayName("a version from the future is refused instead of being read and rewritten")
    void aNewerFormatStopsTheLoad(@TempDir Path directory) throws IOException {
        Path file = write(directory, """
                {
                  "version": 99,
                  "entries": [
                    {
                      "imageFrameName": "zebra_1",
                      "repositoryId": "example-repo",
                      "repoPath": "zebra.png",
                      "grid": { "columns": 1, "rows": 1 },
                      "commitSha": "abc1234",
                      "createdAtEpochMillis": 1,
                      "somethingThisBuildHasNeverHeardOf": true
                    }
                  ]
                }
                """);
        String before = Files.readString(file, StandardCharsets.UTF_8);
        JsonMapRegistry registry = new JsonMapRegistry(file);

        IOException failure = assertThrows(IOException.class, registry::load);

        assertTrue(failure.getMessage().contains("version 99"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Update the mod"), failure.getMessage());
        assertTrue(registry.all().isEmpty(), "nothing should be served from a file we cannot read properly");
        assertEquals(before, Files.readString(file, StandardCharsets.UTF_8),
                "a refused file must be left exactly as it was, fields and all");
    }

    @Test
    @DisplayName("a corrupt file is a checked failure, not a crash")
    void corruptJsonIsAnIoException(@TempDir Path directory) throws IOException {
        Path truncated = write(directory, "{ \"version\": 1, \"entries\": [ { \"imageFrameName\"");
        assertThrows(IOException.class, new JsonMapRegistry(truncated)::load);

        Path wrongShape = directory.resolve("array.json");
        Files.writeString(wrongShape, "[1, 2, 3]", StandardCharsets.UTF_8);
        assertThrows(IOException.class, new JsonMapRegistry(wrongShape)::load);

        Path notAnObject = directory.resolve("string.json");
        Files.writeString(notAnObject, "\"a registry, honest\"", StandardCharsets.UTF_8);
        assertThrows(IOException.class, new JsonMapRegistry(notAnObject)::load);
    }

    @Test
    @DisplayName("entries with no name are skipped rather than taking the whole file down")
    void unusableEntriesAreDroppedButTheRestSurvive(@TempDir Path directory) throws IOException {
        Path file = write(directory, """
                {
                  "version": 1,
                  "entries": [
                    null,
                    { "repoPath": "orphan.png", "grid": { "columns": 1, "rows": 1 } },
                    {
                      "imageFrameName": "zebra_1",
                      "repoPath": "zebra.png",
                      "grid": { "columns": 1, "rows": 1 },
                      "commitSha": "abc1234",
                      "createdAtEpochMillis": 1
                    }
                  ]
                }
                """);
        JsonMapRegistry registry = new JsonMapRegistry(file);

        registry.load();

        assertEquals(List.of("zebra_1"), registry.all().stream().map(MapEntry::imageFrameName).toList());
    }

    @Test
    @DisplayName("the migrating rewrite is still atomic and leaves no temp files")
    void migrationLeavesTheDirectoryClean(@TempDir Path directory) throws IOException {
        Path file = write(directory, LEGACY_FILE);

        new JsonMapRegistry(file).load();

        try (var listing = Files.list(directory)) {
            assertEquals(List.of("mcmarkings-maps.json"),
                    listing.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    @DisplayName("putting and saving from several threads at once keeps every entry")
    void concurrentPutsAndSavesAllHold(@TempDir Path directory) throws Exception {
        Path file = write(directory, LEGACY_FILE);
        JsonMapRegistry registry = new JsonMapRegistry(file);
        registry.load();

        int writers = 8;
        int perWriter = 40;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(writers);
        List<Thread> threads = new ArrayList<>();

        for (int writer = 0; writer < writers; writer++) {
            int id = writer;
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int index = 0; index < perWriter; index++) {
                        registry.put(new MapEntry("map_" + id + "_" + index, "example-repo", "zebra.png",
                                new GridSize(1, 1), "sha", index));
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            }));
        }

        start.countDown();
        // Saving underneath the writers is the real pattern: the GUI saves on a tick
        // while a placement thread is still recording maps.
        for (int round = 0; round < 20; round++) {
            registry.save();
        }
        assertTrue(finished.await(30, TimeUnit.SECONDS));
        for (Thread thread : threads) {
            thread.join();
        }
        registry.save();

        assertEquals(2 + writers * perWriter, registry.all().size());
        assertEquals(2, registry.byRepository(MapRegistry.UNKNOWN_REPOSITORY).size(),
                "the migrated entries should survive everything written on top of them");

        JsonMapRegistry reloaded = new JsonMapRegistry(file);
        reloaded.load();
        assertEquals(registry.all(), reloaded.all());
        assertFalse(reloaded.all().isEmpty());
    }

    private static Path write(Path directory, String contents) throws IOException {
        Path file = directory.resolve("mcmarkings-maps.json");
        Files.writeString(file, contents, StandardCharsets.UTF_8);
        return file;
    }
}
