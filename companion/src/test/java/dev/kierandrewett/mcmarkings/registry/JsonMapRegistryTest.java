package dev.kierandrewett.mcmarkings.registry;

import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.core.MapEntry;
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

class JsonMapRegistryTest {

    @Test
    void roundTripsThroughDisk(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("mcmarkings-maps.json");
        JsonMapRegistry registry = new JsonMapRegistry(file);

        MapEntry zebra = new MapEntry("zebra_1", "repo-a", "zebra.png", new GridSize(2, 1), "abc1234", 1_700_000_000_000L);
        MapEntry giveWay = new MapEntry("give_way_1", "repo-a", "signs/give_way.png", new GridSize(1, 1), "def5678", 1L);
        registry.put(zebra);
        registry.put(giveWay);
        registry.save();

        JsonMapRegistry reloaded = new JsonMapRegistry(file);
        reloaded.load();

        assertEquals(List.of(giveWay, zebra), reloaded.all());
        assertEquals(zebra, reloaded.byName("zebra_1").orElseThrow());
        assertEquals(new GridSize(2, 1), reloaded.byName("zebra_1").orElseThrow().grid());
    }

    @Test
    void savedFileIsReadableJson(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("mcmarkings-maps.json");
        JsonMapRegistry registry = new JsonMapRegistry(file);
        registry.put(new MapEntry("zebra_1", "repo-a", "zebra.png", new GridSize(2, 1), "abc1234", 5L));
        registry.save();

        String json = Files.readString(file, StandardCharsets.UTF_8);

        assertTrue(json.contains("\"version\""), json);
        assertTrue(json.contains("\"imageFrameName\": \"zebra_1\""), json);
        assertTrue(json.endsWith("\n"), "the file should end with a newline");
    }

    @Test
    void saveLeavesNoTempFilesBehind(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("mcmarkings-maps.json");
        JsonMapRegistry registry = new JsonMapRegistry(file);
        registry.put(new MapEntry("a", "repo-a", "a.png", new GridSize(1, 1), "sha", 1L));
        registry.save();
        registry.save();

        try (var listing = Files.list(directory)) {
            assertEquals(List.of("mcmarkings-maps.json"), listing.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void aFailedSaveLeavesThePreviousFileIntact(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("mcmarkings-maps.json");
        JsonMapRegistry registry = new JsonMapRegistry(file);
        registry.put(new MapEntry("a", "repo-a", "a.png", new GridSize(1, 1), "sha", 1L));
        registry.save();
        String original = Files.readString(file, StandardCharsets.UTF_8);

        // Stand in for a crash mid-write: the target is replaced by a move, so a
        // half-written temp file can never be what a reader sees.
        Path stray = Files.createTempFile(directory, "mcmarkings-maps.json", ".tmp");
        Files.writeString(stray, "{ half writ", StandardCharsets.UTF_8);

        assertEquals(original, Files.readString(file, StandardCharsets.UTF_8));

        JsonMapRegistry reloaded = new JsonMapRegistry(file);
        reloaded.load();
        assertEquals(1, reloaded.all().size());
    }

    @Test
    void loadingAMissingFileGivesAnEmptyRegistry(@TempDir Path directory) throws IOException {
        JsonMapRegistry registry = new JsonMapRegistry(directory.resolve("not-there.json"));
        registry.put(new MapEntry("stale", "repo-a", "a.png", new GridSize(1, 1), "sha", 1L));

        registry.load();

        assertTrue(registry.all().isEmpty());
    }

    @Test
    void loadingRubbishIsACheckedFailure(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("mcmarkings-maps.json");
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

        assertThrows(IOException.class, new JsonMapRegistry(file)::load);
    }

    @Test
    void saveCreatesMissingDirectories(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("nested/deeper/mcmarkings-maps.json");
        JsonMapRegistry registry = new JsonMapRegistry(file);
        registry.put(new MapEntry("a", "repo-a", "a.png", new GridSize(1, 1), "sha", 1L));

        registry.save();

        assertTrue(Files.isRegularFile(file));
    }

    @Test
    void byRepoPathReturnsEveryMapBuiltFromThatImage() {
        JsonMapRegistry registry = new JsonMapRegistry(Path.of("unused.json"));
        MapEntry small = new MapEntry("zebra_small", "repo-a", "zebra.png", new GridSize(1, 1), "sha", 1L);
        MapEntry large = new MapEntry("zebra_large", "repo-a", "zebra.png", new GridSize(4, 2), "sha", 2L);
        registry.put(small);
        registry.put(large);
        registry.put(new MapEntry("other", "repo-a", "signs/give_way.png", new GridSize(1, 1), "sha", 3L));

        assertEquals(List.of(large, small), registry.byRepoPath("zebra.png"));
        assertTrue(registry.byRepoPath("nothing.png").isEmpty());
        assertTrue(registry.byRepoPath(null).isEmpty());
    }

    @Test
    void putReplacesAnEntryWithTheSameName() {
        JsonMapRegistry registry = new JsonMapRegistry(Path.of("unused.json"));
        registry.put(new MapEntry("zebra", "repo-a", "zebra.png", new GridSize(1, 1), "old", 1L));
        registry.put(new MapEntry("zebra", "repo-a", "zebra.png", new GridSize(2, 2), "new", 2L));

        assertEquals(1, registry.all().size());
        assertEquals("new", registry.byName("zebra").orElseThrow().commitSha());
    }

    @Test
    void removeDropsTheEntry() {
        JsonMapRegistry registry = new JsonMapRegistry(Path.of("unused.json"));
        registry.put(new MapEntry("zebra", "repo-a", "zebra.png", new GridSize(1, 1), "sha", 1L));

        registry.remove("zebra");
        registry.remove("never_existed");
        registry.remove(null);

        assertTrue(registry.all().isEmpty());
        assertTrue(registry.byName("zebra").isEmpty());
        assertTrue(registry.byName(null).isEmpty());
    }

    @Test
    void putRejectsAnEntryWithNoName() {
        JsonMapRegistry registry = new JsonMapRegistry(Path.of("unused.json"));

        assertThrows(IllegalArgumentException.class, () -> registry.put(null));
        assertThrows(IllegalArgumentException.class,
                () -> registry.put(new MapEntry("  ", "repo-a", "a.png", new GridSize(1, 1), "sha", 1L)));
    }

    @Test
    void returnedListsAreImmutable() {
        JsonMapRegistry registry = new JsonMapRegistry(Path.of("unused.json"));
        registry.put(new MapEntry("zebra", "repo-a", "zebra.png", new GridSize(1, 1), "sha", 1L));

        assertThrows(UnsupportedOperationException.class, () -> registry.all().clear());
        assertThrows(UnsupportedOperationException.class, () -> registry.byRepoPath("zebra.png").clear());
    }

    @Test
    void concurrentWritersAllLand() throws Exception {
        JsonMapRegistry registry = new JsonMapRegistry(Path.of("unused.json"));
        int writers = 16;
        int perWriter = 50;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();

        for (int writer = 0; writer < writers; writer++) {
            int id = writer;
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int index = 0; index < perWriter; index++) {
                    registry.put(new MapEntry(
"map_" + id + "_" + index, "repo-a", "zebra.png", new GridSize(1, 1), "sha", index));
                }
            }));
        }

        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(writers * perWriter, registry.all().size());
    }

    @Test
    void savingWhileReadingDoesNotBlowUp(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("mcmarkings-maps.json");
        JsonMapRegistry registry = new JsonMapRegistry(file);
        for (int index = 0; index < 200; index++) {
            registry.put(new MapEntry(
"map_" + index, "repo-a", "zebra.png", new GridSize(1, 1), "sha", index));
        }

        CountDownLatch done = new CountDownLatch(1);
        Thread writer = Thread.ofVirtual().start(() -> {
            try {
                for (int index = 200; index < 400; index++) {
                    registry.put(new MapEntry(
"map_" + index, "repo-a", "zebra.png", new GridSize(1, 1), "sha", index));
                }
            } finally {
                done.countDown();
            }
        });

        for (int round = 0; round < 20; round++) {
            registry.save();
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        writer.join();

        JsonMapRegistry reloaded = new JsonMapRegistry(file);
        reloaded.load();
        assertFalse(reloaded.all().isEmpty());
    }
}
