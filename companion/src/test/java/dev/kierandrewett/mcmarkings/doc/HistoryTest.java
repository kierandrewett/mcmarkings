package dev.kierandrewett.mcmarkings.doc;

import dev.kierandrewett.mcmarkings.core.GridSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Undo and redo.
 *
 * <p>This is what makes the editor safe to play in, so the awkward cases matter
 * more than the happy path: a drag must be one undo rather than hundreds, a long
 * session must not grow without bound, and undoing then editing must not leave a
 * stale redo pointing at a document that no longer follows.
 */
class HistoryTest {

    private static Document at(int x) {
        Layer.Image layer = new Layer.Image("a", "a", new Layer.Bounds(x, 0, 10, 10), true, false,
                1.0, Insets.NONE, "a.png", Layer.Fit.CONTAIN);
        return new Document("d", new GridSize(1, 1), 128, Document.TRANSPARENT, List.of(layer));
    }

    private static int xOf(Document document) {
        return document.byId("a").orElseThrow().bounds().x();
    }

    @Test
    @DisplayName("undo steps back and redo steps forward again")
    void undoAndRedo() {
        History history = new History(at(0));
        history.push(at(10), "Move layer", null, 1_000);
        history.push(at(20), "Move layer", null, 5_000);

        assertEquals(20, xOf(history.current()));
        assertEquals(10, xOf(history.undo()));
        assertEquals(0, xOf(history.undo()));
        assertFalse(history.canUndo());

        assertEquals(10, xOf(history.redo()));
        assertEquals(20, xOf(history.redo()));
        assertFalse(history.canRedo());
    }

    @Test
    @DisplayName("a drag is one undo, not one per pixel")
    void aDragCoalescesIntoOneEntry() {
        History history = new History(at(0));

        // What a drag actually looks like: many tiny edits in quick succession.
        for (int step = 1; step <= 50; step++) {
            history.push(at(step), "Move layer", "move:a", 1_000 + step * 10L);
        }

        assertEquals(50, xOf(history.current()));
        assertEquals(1, history.depth(), "the whole drag should be a single entry");
        assertEquals(0, xOf(history.undo()), "one undo should return to before the drag");
    }

    @Test
    @DisplayName("a pause ends the gesture, so two drags are two undos")
    void separateDragsDoNotMerge() {
        History history = new History(at(0));
        history.push(at(10), "Move layer", "move:a", 1_000);
        history.push(at(20), "Move layer", "move:a", 10_000);

        assertEquals(2, history.depth());
        assertEquals(10, xOf(history.undo()));
    }

    @Test
    @DisplayName("letting go ends the gesture even when the next drag is immediate")
    void endGestureBreaksCoalescing() {
        History history = new History(at(0));
        history.push(at(10), "Move layer", "move:a", 1_000);
        history.endGesture();
        history.push(at(20), "Move layer", "move:a", 1_100);

        assertEquals(2, history.depth(), "releasing the mouse should separate the actions");
    }

    @Test
    @DisplayName("edits to different layers never merge, however fast")
    void differentLayersDoNotMerge() {
        History history = new History(at(0));
        history.push(at(10), "Move layer", "move:a", 1_000);
        history.push(at(20), "Move layer", "move:b", 1_050);

        assertEquals(2, history.depth());
    }

    @Test
    @DisplayName("editing after an undo discards the redo, which no longer follows")
    void editingClearsRedo() {
        History history = new History(at(0));
        history.push(at(10), "Move layer", null, 1_000);
        history.push(at(20), "Move layer", null, 5_000);

        history.undo();
        assertTrue(history.canRedo());

        history.push(at(99), "Move layer", null, 9_000);

        assertFalse(history.canRedo(), "the old future is no longer reachable from here");
        assertEquals(99, xOf(history.current()));
    }

    @Test
    @DisplayName("an edit that changes nothing is not recorded")
    void noOpEditsAreIgnored() {
        History history = new History(at(0));

        // Dragging a layer and putting it back should not cost an undo.
        history.push(at(0), "Move layer", null, 1_000);

        assertFalse(history.canUndo());
        assertEquals(0, history.depth());
    }

    @Test
    @DisplayName("the stack is bounded, so a long session cannot eat memory")
    void historyIsBounded() {
        History history = new History(at(0), 5);
        for (int step = 1; step <= 50; step++) {
            history.push(at(step), "Move layer", null, step * 10_000L);
        }

        assertEquals(5, history.depth());

        // The oldest states are gone, but everything recent still undoes cleanly.
        for (int step = 0; step < 5; step++) {
            history.undo();
        }
        assertFalse(history.canUndo());
    }

    @Test
    @DisplayName("undo and redo at the ends are harmless")
    void steppingPastTheEndsIsSafe() {
        History history = new History(at(0));

        assertEquals(0, xOf(history.undo()));
        assertEquals(0, xOf(history.redo()));
        assertFalse(history.canUndo());
        assertFalse(history.canRedo());
    }

    @Test
    @DisplayName("the label says what will be undone, for the menu")
    void labelsDescribeTheAction() {
        History history = new History(at(0));
        assertTrue(history.undoLabel().isEmpty());

        history.push(at(10), "Move layer", null, 1_000);
        assertEquals("Move layer", history.undoLabel().orElseThrow());

        history.undo();
        assertEquals("Move layer", history.redoLabel().orElseThrow());
    }

    @Test
    @DisplayName("opening another document forgets the previous one")
    void resetClearsEverything() {
        History history = new History(at(0));
        history.push(at(10), "Move layer", null, 1_000);

        history.reset(at(500));

        assertFalse(history.canUndo(), "undoing into a different document would be nonsense");
        assertFalse(history.canRedo());
        assertEquals(500, xOf(history.current()));
    }
}
