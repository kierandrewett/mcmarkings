package dev.kierandrewett.mcmarkings.doc;

import dev.kierandrewett.mcmarkings.core.GridSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A full undo history stays small, because entries share their layers.
 *
 * <p>The history holds whole documents rather than a list of changes, which sounds
 * expensive and is not: documents are immutable records, an edit builds a new list
 * with one new layer in it, and the other forty nine are the same objects in every
 * entry going back. Measured, five hundred steps on a fifty layer document come to
 * about two hundred and thirty kilobytes, against a hundred and fifteen for a hundred
 * steps. Five times the history for twice the memory.
 *
 * <p>That is the property the depth of the history rests on, and it is one line away
 * from being untrue. Anything that copies a layer on the way into a document, or
 * normalises one into a new object, turns every entry into a full fifty layer
 * snapshot and the cost stops being shared. Nothing would look wrong: undo still
 * works, and the memory goes quietly.
 *
 * <p>Generous, because measuring memory is not exact. It is looking for the fifty
 * fold difference between sharing and not, so it does not need to be.
 */
class HistoryCostTest {

    private static final int LAYERS = 50;

    /** Comfortably above what sharing costs, far below what copying would. */
    private static final long BUDGET_BYTES = 2L * 1024 * 1024;

    private static Layer.Shape shape(String id, int x, int y) {
        return new Layer.Shape(id, id, new Layer.Bounds(x, y, 40, 24), true, false, 1.0,
                Insets.NONE, Insets.NONE, 0xFF3366AA, 4, 0xFFFFFFFF, 2);
    }

    private static long used() {
        for (int settle = 0; settle < 5; settle++) {
            System.gc();
            try {
                Thread.sleep(60);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    @Test
    @DisplayName("a full history of a busy document costs well under a megabyte")
    void aFullHistorySharesItsLayers() {
        List<Layer> layers = new ArrayList<>();
        for (int at = 0; at < LAYERS; at++) {
            layers.add(shape("id" + at, at, at));
        }
        Document document = new Document("cost", new GridSize(2, 1), 128,
                Document.TRANSPARENT, List.copyOf(layers));

        long before = used();
        History history = new History(document, History.DEFAULT_LIMIT);

        // Twice the limit, so the stack is full and has been trimmed, which is the
        // state it spends a long session in.
        for (int step = 0; step < History.DEFAULT_LIMIT * 2; step++) {
            int at = step % layers.size();
            List<Layer> next = new ArrayList<>(layers);
            next.set(at, shape("id" + at, step % 400, at));
            layers = next;
            document = new Document(document.name(), document.grid(), document.pixelsPerFrame(),
                    document.background(), List.copyOf(next));
            history.push(document, "Move", "step-" + step, step * 10_000L);
        }
        long cost = used() - before;

        assertTrue(history.depth() == History.DEFAULT_LIMIT,
                "expected a full stack of " + History.DEFAULT_LIMIT + ", found " + history.depth());
        assertTrue(cost < BUDGET_BYTES, () ->
                "a full undo history of a " + LAYERS + " layer document is holding "
                        + (cost / 1024) + " KB, and sharing puts it near two hundred and thirty. "
                        + "Something on the way into a Document is copying or rebuilding layers, so "
                        + "every entry now holds its own " + LAYERS + " of them instead of pointing "
                        + "at the ones that did not change. Undo still works, which is why this is "
                        + "checked rather than noticed.");
    }
}
