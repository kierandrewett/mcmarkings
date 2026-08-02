package dev.kierandrewett.mcmarkings.doc;

import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.render.FontRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A session that goes on for hours, compressed.
 *
 * <p>Everything else here checks one operation at a time. Nobody had ever run a few
 * thousand of them in a row, which is the shape of actually using this: add, move,
 * resize, group, undo, think better of it, redo, carry on. The faults that shape
 * produces are the ones that do not exist at operation one, so a test that only ever
 * does one of each cannot see them.
 *
 * <p>Deterministic: the seed is fixed, so a failure is reproducible rather than
 * something that happened once on a Tuesday.
 */
class LongSessionTest {

    private static final int OPERATIONS = 4000;

    private static Layer.Shape shape(String id, int x, int y) {
        return new Layer.Shape(id, id, new Layer.Bounds(x, y, 40, 24), true, false, 1.0,
                Insets.NONE, Insets.NONE, 0xFF3366AA, 4, 0xFFFFFFFF, 2);
    }

    private static Layer.Text text(String id, int x, int y) {
        return new Layer.Text(id, id, new Layer.Bounds(x, y, 90, 20), true, false, 1.0,
                Insets.NONE, "Layer " + id, FontRegistry.DEFAULT_FONT, 14.0, 0xFF202020,
                Layer.HorizontalAlign.LEFT, Layer.VerticalAlign.TOP, 2.0, 0.0, 1.0);
    }

    /** Every layer id in the tree, groups and their children included. */
    private static void collectIds(List<Layer> layers, List<String> into) {
        for (Layer layer : layers) {
            into.add(layer.id());
            if (layer instanceof Layer.Group group) {
                collectIds(group.children(), into);
            }
        }
    }

    private static int depthOf(List<Layer> layers) {
        int deepest = 0;
        for (Layer layer : layers) {
            if (layer instanceof Layer.Group group) {
                deepest = Math.max(deepest, 1 + depthOf(group.children()));
            }
        }
        return deepest;
    }

    @Test
    @DisplayName("four thousand edits leave a document that is still coherent")
    void aLongSessionStaysCoherent() {
        Document start = new Document("soak", new GridSize(3, 2), 128, Document.TRANSPARENT, List.of());
        History history = new History(start);
        Random random = new Random(20260802L);
        int created = 0;

        for (int step = 0; step < OPERATIONS; step++) {
            Document document = history.current();
            List<String> topLevel = document.layers().stream().map(Layer::id).toList();

            int choice = random.nextInt(100);
            if (choice < 30 || topLevel.isEmpty()) {
                String id = "L" + (created++);
                Layer added = random.nextBoolean() ? shape(id, random.nextInt(300), random.nextInt(200))
                        : text(id, random.nextInt(300), random.nextInt(200));
                history.push(document.add(added), "Add layer", null);
            } else if (choice < 50) {
                String id = topLevel.get(random.nextInt(topLevel.size()));
                history.push(Edits.nudge(document, List.of(id), random.nextInt(9) - 4, random.nextInt(9) - 4),
                        "Move", "move:" + id);
            } else if (choice < 62) {
                String id = topLevel.get(random.nextInt(topLevel.size()));
                history.push(Edits.duplicate(document, List.of(id)).document(), "Duplicate", null);
            } else if (choice < 72 && topLevel.size() >= 2) {
                List<String> pair = List.of(topLevel.get(0), topLevel.get(1));
                history.push(Edits.group(document, pair, Insets.all(4)).document(), "Group", null);
            } else if (choice < 80) {
                String id = topLevel.get(random.nextInt(topLevel.size()));
                history.push(Edits.remove(document, List.of(id)), "Delete", null);
            } else if (choice < 88) {
                String id = topLevel.get(random.nextInt(topLevel.size()));
                history.push(document.reorder(id, random.nextInt(5) - 2), "Reorder", null);
            } else if (choice < 95) {
                if (history.canUndo()) {
                    history.undo();
                }
            } else if (history.canRedo()) {
                history.redo();
            }

            // Every layer id has to stay unique, or selecting one selects two and an
            // edit lands somewhere nobody asked for. Duplicate and group are where
            // this would go wrong, and both run thousands of times here.
            if (step % 200 == 0) {
                List<String> ids = new ArrayList<>();
                collectIds(history.current().layers(), ids);
                Set<String> unique = new HashSet<>(ids);
                assertEquals(ids.size(), unique.size(),
                        "duplicate layer ids after " + step + " edits");
            }
        }

        Document ended = history.current();
        List<String> ids = new ArrayList<>();
        collectIds(ended.layers(), ids);

        assertEquals(ids.size(), new HashSet<>(ids).size(), "duplicate layer ids at the end");
        assertTrue(history.depth() <= History.DEFAULT_LIMIT,
                "history grew past its cap: " + history.depth());
        assertTrue(ended.layers().size() < OPERATIONS,
                "every layer ever added is still here, so nothing was ever really deleted");
    }

    /**
     * The document still has to survive a save and a reopen after all that. A session
     * that ends by saving something that will not load is the worst outcome of the
     * lot, and it is invisible until the next time it is opened.
     */
    @Test
    @DisplayName("what a long session ends with still round trips through JSON")
    void theResultStillSaves() throws Exception {
        Document start = new Document("soak", new GridSize(2, 2), 128, Document.TRANSPARENT, List.of());
        History history = new History(start);
        Random random = new Random(7L);

        for (int step = 0; step < 600; step++) {
            Document document = history.current();
            if (document.layers().size() < 3 || random.nextInt(3) == 0) {
                history.push(document.add(shape("S" + step, random.nextInt(200), random.nextInt(200))),
                        "Add", null);
                continue;
            }
            List<String> ids = document.layers().stream().map(Layer::id).toList();
            history.push(Edits.group(document, List.of(ids.get(0), ids.get(1)), Insets.all(2)).document(),
                    "Group", null);
        }

        Document ended = history.current();
        DocumentJson.Result reopened = DocumentJson.readWithReport(DocumentJson.write(ended));

        assertEquals(List.of(), reopened.warnings(), "the document did not come back whole");
        assertEquals(DocumentJson.write(ended), DocumentJson.write(reopened.document()),
                "a saved session does not reopen identically");
    }

    /**
     * Nesting has to stop somewhere. Grouping the same two layers repeatedly is a
     * thing people do without noticing, and each round wraps the last, so the tree
     * gets one deeper every time.
     */
    @Test
    @DisplayName("repeated grouping does not build a tree nothing can render")
    void repeatedGroupingIsSurvivable() throws Exception {
        Document document = new Document("nest", new GridSize(1, 1), 128, Document.TRANSPARENT,
                List.of(shape("a", 0, 0), shape("b", 20, 20)));

        // A fresh layer each round, then group it with what is already there. Grouping
        // a lone group does nothing: two members is the existing floor, since a group
        // of one is only an extra level to click through. My first version of this
        // looped two hundred times against that floor and nested exactly once while
        // asserting nothing, which is how it passed.
        for (int round = 0; round < 200; round++) {
            document = document.add(shape("extra" + round, round, round));
            List<String> ids = document.layers().stream().map(Layer::id).toList();
            document = Edits.group(document, ids, Insets.all(1)).document();
        }

        // Two hundred rounds asked for, at most MAX_GROUP_DEPTH made. Gson stops
        // reading at 255 levels, so a document nested past that saves without
        // complaint and cannot be opened again, and the loss shows up the next time
        // somebody reaches for the file rather than when they made it.
        int depth = depthOf(document.layers());
        assertTrue(depth > 0, "nothing nested at all");
        assertTrue(depth <= Edits.MAX_GROUP_DEPTH,
                "grouping nested " + depth + " deep, past the cap of " + Edits.MAX_GROUP_DEPTH);

        // Round trips rather than overflowing a stack, which is the failure that
        // would actually bite: Gson recurses, and so does the renderer.
        DocumentJson.Result reopened = DocumentJson.readWithReport(DocumentJson.write(document));
        assertEquals(depth, depthOf(reopened.document().layers()),
                "a deeply nested document lost levels on the way through JSON");
    }

    /**
     * The specific number, because it is the one that decides whether a file opens.
     *
     * <p>Found by soaking rather than by reading: 600 ordinary operations produced a
     * document that wrote fine and came back MalformedJsonException, and the message
     * named a limit nothing in this project knew about.
     */
    @Test
    @DisplayName("nesting stops well short of what the parser will read")
    void theCapIsBelowWhatGsonWillRead() {
        assertTrue(Edits.MAX_GROUP_DEPTH < 255,
                "the cap is at or past Gson's own limit, so it guarantees nothing");
        assertTrue(Edits.MAX_GROUP_DEPTH >= 8,
                "the cap is low enough to get in the way of composing something real");
    }

    /**
     * Refusing has to be visible to the caller, or it is the same silence with a
     * different cause. An empty result is how every other refusal here reads.
     */
    @Test
    @DisplayName("grouping past the cap refuses rather than making an unopenable document")
    void groupingPastTheCapRefuses() {
        Document document = new Document("nest", new GridSize(1, 1), 128, Document.TRANSPARENT,
                List.of(shape("a", 0, 0), shape("b", 20, 20)));

        for (int round = 0; round < Edits.MAX_GROUP_DEPTH + 5; round++) {
            document = document.add(shape("extra" + round, round, round));
            List<String> ids = document.layers().stream().map(Layer::id).toList();
            Edits.Result result = Edits.group(document, ids, Insets.NONE);
            if (result.createdIds().isEmpty()) {
                assertEquals(document, result.document(), "a refused group still changed the document");
                assertTrue(depthOf(document.layers()) >= Edits.MAX_GROUP_DEPTH - 1,
                        "grouping refused far too early, at depth " + depthOf(document.layers()));
                return;
            }
            document = result.document();
        }
        throw new AssertionError("grouping never refused, so the cap is not enforced");
    }

    /**
     * Undo takes you back, all the way, after a long run of edits.
     *
     * <p>The soak above checks the document stays coherent and says nothing about
     * whether undo means anything, which is the whole promise of an editor: you can
     * try something because you can take it back. Coalescing is where that would go
     * wrong, since it rewrites the present entry rather than pushing a new one, and
     * a run of moves on one layer is exactly what someone does all afternoon.
     *
     * <p>Under the history limit on purpose. Past a hundred entries the oldest are
     * dropped by design and walking back to the start is no longer the contract.
     */
    @Test
    @DisplayName("undoing everything returns exactly the document you started with")
    void undoWalksAllTheWayBack() {
        Document start = new Document("undo", new GridSize(2, 2), 128, Document.TRANSPARENT,
                List.of(shape("keep", 5, 5)));
        History history = new History(start);
        Random random = new Random(99L);

        int pushed = 0;
        long clock = 1_000_000L;
        for (int step = 0; step < 60; step++) {
            Document document = history.current();
            List<String> ids = document.layers().stream().map(Layer::id).toList();
            String id = ids.get(random.nextInt(ids.size()));

            Document next;
            String key = null;
            if (random.nextInt(3) == 0) {
                next = document.add(shape("N" + step, random.nextInt(100), random.nextInt(100)));
            } else {
                next = Edits.nudge(document, List.of(id), 1 + random.nextInt(3), 1 + random.nextInt(3));
                key = "move:" + id;
            }

            // Each edit a clear minute apart, so nothing coalesces and every push is
            // its own step. The coalescing case is checked separately below.
            clock += 60_000L;
            int before = history.depth();
            history.push(next, "Edit " + step, key, clock);
            if (history.depth() > before) {
                pushed++;
            }
        }

        assertTrue(pushed > 40, "not enough distinct edits to be a useful walk back: " + pushed);
        while (history.canUndo()) {
            history.undo();
        }
        assertEquals(start, history.current(), "undoing everything did not return the starting document");
    }

    /**
     * And forward again. Redo is the half people reach for when they undo one step
     * too many, which is a thing that happens constantly.
     */
    @Test
    @DisplayName("redoing everything returns exactly where undo started from")
    void redoWalksAllTheWayForward() {
        Document start = new Document("redo", new GridSize(2, 2), 128, Document.TRANSPARENT,
                List.of(shape("keep", 5, 5)));
        History history = new History(start);

        long clock = 2_000_000L;
        for (int step = 0; step < 40; step++) {
            clock += 60_000L;
            history.push(history.current().add(shape("R" + step, step, step)), "Add " + step, null, clock);
        }
        Document ended = history.current();

        while (history.canUndo()) {
            history.undo();
        }
        assertEquals(start, history.current(), "undo did not reach the start");

        while (history.canRedo()) {
            history.redo();
        }
        assertEquals(ended, history.current(), "redo did not reach where undo started");
    }

    /**
     * A run of small moves on one layer is one undo, not forty.
     *
     * <p>That is the point of coalescing and it is also where undo could quietly stop
     * meaning anything: if the window merged edits on different layers, one undo
     * would take back work somewhere the person was not looking.
     */
    @Test
    @DisplayName("a drag is one undo, and a drag of something else is another")
    void coalescingMergesOneLayerAtATime() {
        Document start = new Document("drag", new GridSize(2, 2), 128, Document.TRANSPARENT,
                List.of(shape("a", 0, 0), shape("b", 60, 0)));
        History history = new History(start);

        long clock = 3_000_000L;
        for (int step = 0; step < 20; step++) {
            clock += 10L;
            history.push(Edits.nudge(history.current(), List.of("a"), 1, 0), "Move", "move:a", clock);
        }
        assertEquals(1, history.depth(), "a single drag filled the stack");

        for (int step = 0; step < 20; step++) {
            clock += 10L;
            history.push(Edits.nudge(history.current(), List.of("b"), 1, 0), "Move", "move:b", clock);
        }
        assertEquals(2, history.depth(), "dragging a second layer merged into the first layer's undo");

        history.undo();
        history.undo();
        assertEquals(start, history.current(), "two undos did not take back two drags");
    }

    /**
     * The cap has to protect both stores, not just the one it was found in.
     *
     * <p>The unopenable document turned up through TemplateStore, and recovery writes
     * the same documents through a different file with no size or depth checks of its
     * own. Capping where the nesting is made rather than where it is written is what
     * makes that safe, and this is the check that it actually is.
     */
    @Test
    @DisplayName("a document at the nesting cap survives both ways of saving it")
    void theCapProtectsEveryWayOut(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
            throws Exception {
        Document document = new Document("deep", new GridSize(1, 1), 128, Document.TRANSPARENT,
                List.of(shape("a", 0, 0)));
        for (int round = 0; round < Edits.MAX_GROUP_DEPTH + 10; round++) {
            document = document.add(shape("extra" + round, round, round));
            List<String> ids = document.layers().stream().map(Layer::id).toList();
            document = Edits.group(document, ids, Insets.NONE).document();
        }

        assertTrue(depthOf(document.layers()) <= Edits.MAX_GROUP_DEPTH,
                "the cap did not hold, so neither store is protected");

        TemplateStore templates = new TemplateStore(directory);
        java.nio.file.Path saved = templates.save(document);
        assertEquals(List.of(), templates.readWithReport(saved).warnings(),
                "the template did not come back whole");

        RecoveryStore recovery = new RecoveryStore(directory.resolve("recovery.json"));
        recovery.record(document, "repo-1");
        recovery.flushNow(1L);
        assertTrue(recovery.pending().isPresent(), "the recovery snapshot could not be read back");
    }
}
