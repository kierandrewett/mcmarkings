package dev.kierandrewett.mcmarkings.doc;

import dev.kierandrewett.mcmarkings.core.GridSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crash recovery.
 *
 * <p>Losing an hour of composing to a crash is the thing most likely to stop
 * someone opening the editor again, so the awkward cases matter more than the
 * happy path: a snapshot half-written by the crash itself, one left behind by a
 * session that ended badly, and the cost of taking it in the first place.
 */
class RecoveryStoreTest {

    private static Document documentNamed(String name, int x) {
        Layer.Image layer = new Layer.Image("a", "a", new Layer.Bounds(x, 0, 10, 10), true, false,
                1.0, Insets.NONE, "a.png", Layer.Fit.CONTAIN);
        return new Document(name, new GridSize(1, 1), 128, Document.TRANSPARENT, List.of(layer));
    }

    @Test
    @DisplayName("work survives a session that never came back")
    void snapshotSurvivesRestart(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("recovery.json");

        RecoveryStore store = new RecoveryStore(file);
        store.record(documentNamed("In progress", 42), "repo-1");
        store.flushNow(1_000);

        // A different instance, as if the game had been restarted.
        RecoveryStore.Recovered recovered = new RecoveryStore(file).pending().orElseThrow();

        assertEquals("In progress", recovered.document().name());
        assertEquals(42, recovered.document().byId("a").orElseThrow().bounds().x());
        assertEquals("repo-1", recovered.repositoryId(), "it has to reopen against the right repository");
        assertEquals(1_000, recovered.savedAtMillis());
    }

    @Test
    @DisplayName("recording is cheap and writes nothing on its own")
    void recordingDoesNoWork(@TempDir Path directory) {
        Path file = directory.resolve("recovery.json");
        RecoveryStore store = new RecoveryStore(file);

        // Called on every edit, so it must not touch the disk. If saving your work
        // makes the game stutter, people turn it off.
        for (int step = 0; step < 1_000; step++) {
            store.record(documentNamed("Doc", step), "repo-1");
        }

        assertFalse(Files.exists(file), "recording should not have written anything yet");
        assertTrue(store.hasUnsavedChanges());
    }

    @Test
    @DisplayName("writes are rate limited rather than happening on every edit")
    void writesAreRateLimited(@TempDir Path directory) throws IOException {
        RecoveryStore store = new RecoveryStore(directory.resolve("recovery.json"));

        store.record(documentNamed("Doc", 1), "repo-1");
        assertTrue(store.flushIfDue(RecoveryStore.INTERVAL_MILLIS), "the first flush should write");

        store.record(documentNamed("Doc", 2), "repo-1");
        assertFalse(store.flushIfDue(RecoveryStore.INTERVAL_MILLIS + 1), "too soon to write again");

        assertTrue(store.flushIfDue(RecoveryStore.INTERVAL_MILLIS * 2 + 1), "enough time has passed");
    }

    @Test
    @DisplayName("nothing to write means nothing happens")
    void unchangedStateIsNotRewritten(@TempDir Path directory) throws IOException {
        RecoveryStore store = new RecoveryStore(directory.resolve("recovery.json"));

        assertFalse(store.flushIfDue(999_999), "no document recorded yet");

        store.record(documentNamed("Doc", 1), "repo-1");
        store.flushNow(1_000);

        assertFalse(store.hasUnsavedChanges());
        assertFalse(store.flushIfDue(999_999), "already written, nothing changed since");
    }

    @Test
    @DisplayName("a saved document stops being offered as lost work")
    void clearingRemovesTheSnapshot(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("recovery.json");
        RecoveryStore store = new RecoveryStore(file);
        store.record(documentNamed("Doc", 1), "repo-1");
        store.flushNow(1_000);

        store.clear();

        assertFalse(Files.exists(file));
        assertTrue(new RecoveryStore(file).pending().isEmpty(),
                "offering to recover work that was already saved would be alarming");
    }

    @Test
    @DisplayName("a snapshot destroyed by the crash itself is ignored, not fatal")
    void corruptSnapshotIsIgnored(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("recovery.json");
        Files.writeString(file, "{ \"document\": { truncated by the cra", StandardCharsets.UTF_8);

        // This is already the fallback for a bad outcome. Failing here would turn a
        // recoverable session into a broken editor.
        assertTrue(new RecoveryStore(file).pending().isEmpty());
    }

    @Test
    @DisplayName("a missing snapshot is the normal case")
    void noSnapshotIsNormal(@TempDir Path directory) {
        assertTrue(new RecoveryStore(directory.resolve("nothing.json")).pending().isEmpty());
    }

    @Test
    @DisplayName("a half-written file never replaces a good one")
    void writesAreAtomic(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("recovery.json");
        RecoveryStore store = new RecoveryStore(file);

        store.record(documentNamed("First", 1), "repo-1");
        store.flushNow(1_000);
        store.record(documentNamed("Second", 2), "repo-1");
        store.flushNow(RecoveryStore.INTERVAL_MILLIS * 3);

        assertEquals("Second", new RecoveryStore(file).pending().orElseThrow().document().name());
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.toString().endsWith(".tmp")),
                    "a leftover temp file would be the thing a crash left behind");
        }
    }

    @Test
    @DisplayName("the whole document comes back, not just its shape")
    void everythingIsPreserved(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("recovery.json");
        Layer.Text text = new Layer.Text("t", "Legend", new Layer.Bounds(4, 5, 60, 20), true, false,
                0.5, Insets.all(3), "Half typed", "sans-serif", 24, 0xFF102030,
                Layer.HorizontalAlign.RIGHT, Layer.VerticalAlign.BOTTOM, 2, 1, 3.0);
        Document document = new Document("Work", new GridSize(2, 3), 256, 0xFF204060, List.of(text));

        RecoveryStore store = new RecoveryStore(file);
        store.record(document, "repo-9");
        store.flushNow(5_000);

        Document recovered = new RecoveryStore(file).pending().orElseThrow().document();
        Layer.Text recoveredText = (Layer.Text) recovered.layers().getFirst();

        assertEquals(new GridSize(2, 3), recovered.grid());
        assertEquals(0xFF204060, recovered.background());
        assertEquals("Half typed", recoveredText.text());
        assertEquals(3.0, recoveredText.verticalScale(), 1.0e-9);
        assertEquals(Layer.VerticalAlign.BOTTOM, recoveredText.verticalAlign());
        assertEquals(0.5, recoveredText.opacity(), 1.0e-9);
    }
}
