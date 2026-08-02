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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Templates on disk.
 *
 * <p>A template is just a saved document, so most of the risk is in the file
 * handling rather than the format: a name that is illegal on someone else's
 * platform, or a half-written file left behind by an interrupted save, both break
 * for the person who pulls the repository rather than the one who made it.
 */
class TemplateStoreTest {

    private static Document document(String name) {
        Layer.Text text = new Layer.Text("t", "Legend", new Layer.Bounds(10, 10, 100, 40), true, false,
                1.0, Insets.NONE, "Hello", "sans-serif", 32, 0xFFFFFFFF,
                Layer.HorizontalAlign.CENTRE, Layer.VerticalAlign.MIDDLE, 4, 0, 1.0);
        return new Document(name, new GridSize(2, 1), 128, 0xFF102030, List.of(text));
    }

    @Test
    @DisplayName("a saved template loads back as the same document")
    void roundTripsThroughDisk(@TempDir Path root) throws IOException {
        TemplateStore store = new TemplateStore(root);

        store.save(document("Warning plate"));
        Document loaded = store.load(store.byName("Warning plate").orElseThrow());

        assertEquals("Warning plate", loaded.name());
        assertEquals(0xFF102030, loaded.background());
        assertEquals(1, loaded.layers().size());
        assertEquals("Hello", ((Layer.Text) loaded.layers().getFirst()).text());
    }

    @Test
    @DisplayName("templates live in the repository, so they travel with their images")
    void savesIntoTheRepository(@TempDir Path root) throws IOException {
        TemplateStore store = new TemplateStore(root);

        Path file = store.save(document("Plate"));

        assertTrue(file.startsWith(root.resolve(TemplateStore.DIRECTORY)));
        assertTrue(Files.exists(file));
    }

    @Test
    @DisplayName("names become file names that are legal everywhere")
    void namesAreMadePortable() {
        // A name legal on one platform and not another breaks the checkout of
        // whoever pulls it, not the author's.
        assertEquals("give-way-50-yards.json", TemplateStore.fileNameFor("Give Way: 50 yards?"));
        assertEquals("untitled.json", TemplateStore.fileNameFor("   "));
        assertEquals("untitled.json", TemplateStore.fileNameFor(null));
        assertEquals("a-b.json", TemplateStore.fileNameFor("a/b"));
        assertFalse(TemplateStore.fileNameFor("../escape").contains(".."), "must not climb out of the folder");
    }

    @Test
    @DisplayName("listing is sorted and does not parse the documents")
    void listsWithoutParsing(@TempDir Path root) throws IOException {
        TemplateStore store = new TemplateStore(root);
        store.save(document("Zebra"));
        store.save(document("Alpha"));

        // Deliberately unparseable: listing must not care, since the picker only
        // needs names and parsing every file to show a list would be wasteful.
        Files.writeString(store.directory().resolve("broken.json"), "{ not json", StandardCharsets.UTF_8);

        List<TemplateStore.Entry> entries = store.list();

        assertEquals(3, entries.size());
        assertEquals("Alpha", entries.getFirst().name());
        assertEquals("Zebra", entries.getLast().name());
    }

    @Test
    @DisplayName("a missing templates folder lists empty rather than failing")
    void missingDirectoryIsNotAnError(@TempDir Path root) {
        assertTrue(new TemplateStore(root).list().isEmpty());
    }

    @Test
    @DisplayName("saving twice overwrites rather than accumulating copies")
    void savingTwiceOverwrites(@TempDir Path root) throws IOException {
        TemplateStore store = new TemplateStore(root);

        store.save(document("Plate"));
        store.save(document("Plate").withBackground(0xFF000000));

        assertEquals(1, store.list().size());
        assertEquals(0xFF000000, store.load(store.byName("Plate").orElseThrow()).background());
    }

    @Test
    @DisplayName("an interrupted save leaves no temporary file behind")
    void savingLeavesNoTemporaryFile(@TempDir Path root) throws IOException {
        TemplateStore store = new TemplateStore(root);

        store.save(document("Plate"));

        try (var files = Files.list(store.directory())) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().endsWith(".tmp")),
                    "a stray temp file would be listed as a template and fail to load");
        }
    }

    @Test
    @DisplayName("a corrupt template fails on load, not on listing")
    void corruptTemplateFailsWhenOpened(@TempDir Path root) throws IOException {
        TemplateStore store = new TemplateStore(root);
        Files.createDirectories(store.directory());
        Files.writeString(store.directory().resolve("broken.json"), "{ not json", StandardCharsets.UTF_8);

        TemplateStore.Entry entry = store.list().getFirst();

        assertThrows(IOException.class, () -> store.load(entry));
    }

    @Test
    @DisplayName("a template that is not there is an error a screen can show")
    void missingTemplateReportsClearly(@TempDir Path root) {
        TemplateStore store = new TemplateStore(root);

        IOException thrown = assertThrows(IOException.class,
                () -> store.load(root.resolve("templates/nope.json")));

        assertTrue(thrown.getMessage().contains("nope.json"));
    }

    @Test
    @DisplayName("deleting removes it from the list")
    void deleteRemoves(@TempDir Path root) throws IOException {
        TemplateStore store = new TemplateStore(root);
        store.save(document("Plate"));

        store.delete(store.byName("Plate").orElseThrow());

        assertTrue(store.list().isEmpty());
    }

    @Test
    @DisplayName("names that look different can be the same file")
    void differentNamesCanCollide() {
        // The reason the save prompt has to compare file names rather than typed
        // ones. These are three distinct things to type and one thing on disk, so
        // warning about an overwrite by comparing what someone wrote would say
        // nothing right up until the second save replaced the first.
        assertEquals("give-way.json", TemplateStore.fileNameFor("Give Way"));
        assertEquals("give-way.json", TemplateStore.fileNameFor("give_way"));
        assertEquals("give-way.json", TemplateStore.fileNameFor("  give   way  "));
    }

    @Test
    @DisplayName("a name with nothing usable in it still has a file")
    void unusableNamesStillLand() {
        assertEquals("untitled.json", TemplateStore.fileNameFor("!!!"));
        assertEquals("untitled.json", TemplateStore.fileNameFor(""));
    }
}
