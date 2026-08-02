package dev.kierandrewett.mcmarkings.doc;

import dev.kierandrewett.mcmarkings.core.GridSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The small actions a composition is actually built from.
 *
 * <p>Grouping gets the most attention because it is the one that rewrites
 * coordinates. Getting that wrong makes everything jump the moment it is grouped,
 * which is glaring on screen and invisible in the code.
 */
class EditsTest {

    private static Layer.Image image(String id, int x, int y, int width, int height) {
        return new Layer.Image(id, id, new Layer.Bounds(x, y, width, height), true, false, 1.0,
                Insets.NONE, "a.png", Layer.Fit.CONTAIN);
    }

    private static Document canvas(Layer... layers) {
        return new Document("d", new GridSize(2, 2), 128, Document.TRANSPARENT, List.of(layers));
    }

    private static Layer.Bounds boundsOf(Document document, String id) {
        return document.byId(id).orElseThrow().bounds();
    }

    @Test
    @DisplayName("a duplicate is offset so it is visibly a second copy")
    void duplicateIsOffsetAndDistinct() {
        Document document = canvas(image("a", 10, 20, 30, 40));

        Edits.Result result = Edits.duplicate(document, List.of("a"));
        String copyId = result.createdIds().getFirst();

        assertNotEquals("a", copyId, "a copy needs its own identity");
        assertEquals(2, result.document().layers().size());
        assertNotEquals(boundsOf(result.document(), "a"), boundsOf(result.document(), copyId));
        assertEquals(30, boundsOf(result.document(), copyId).width(), "size should not change");
    }

    @Test
    @DisplayName("duplicating a group gives its children new ids too")
    void duplicatingAGroupDoesNotShareChildIds() {
        Layer.Group group = new Layer.Group("g", "Group", new Layer.Bounds(0, 0, 100, 100), true, false,
                1.0, Insets.NONE, Insets.NONE, List.of(image("child", 0, 0, 10, 10)));

        Edits.Result result = Edits.duplicate(canvas(group), List.of("g"));
        Layer.Group copy = (Layer.Group) result.document().byId(result.createdIds().getFirst()).orElseThrow();

        assertNotEquals("child", copy.children().getFirst().id(),
                "sharing a child id would make the two groups edit each other");
    }

    @Test
    @DisplayName("nudging moves by the delta and leaves locked layers alone")
    void nudgeRespectsLocking() {
        Layer.Image locked = new Layer.Image("b", "b", new Layer.Bounds(50, 50, 10, 10), true, true,
                1.0, Insets.NONE, "a.png", Layer.Fit.CONTAIN);
        Document document = canvas(image("a", 10, 10, 10, 10), locked);

        Document nudged = Edits.nudge(document, List.of("a", "b"), 5, -3);

        assertEquals(15, boundsOf(nudged, "a").x());
        assertEquals(7, boundsOf(nudged, "a").y());
        assertEquals(50, boundsOf(nudged, "b").x(), "locked means locked");
    }

    @Test
    @DisplayName("grouping encloses its members without moving them on screen")
    void groupingDoesNotMoveAnythingVisually() {
        Document document = canvas(image("a", 20, 30, 40, 10), image("b", 100, 50, 20, 20));

        Edits.Result result = Edits.group(document, List.of("a", "b"), Insets.NONE);
        Layer.Group group = (Layer.Group) result.document().byId(result.createdIds().getFirst()).orElseThrow();

        // The group encloses both exactly.
        assertEquals(20, group.bounds().x());
        assertEquals(30, group.bounds().y());
        assertEquals(100, group.bounds().width(), "20 to 120");
        assertEquals(40, group.bounds().height(), "30 to 70");

        // Children are now relative to the group, so absolute positions are unchanged.
        Layer.Bounds first = group.children().getFirst().bounds();
        assertEquals(0, first.x(), "20 absolute minus the group's 20");
        assertEquals(0, first.y());
        Layer.Bounds second = group.children().getLast().bounds();
        assertEquals(80, second.x(), "100 absolute minus the group's 20");
        assertEquals(20, second.y());
    }

    @Test
    @DisplayName("group padding grows the group outwards, not the contents inwards")
    void groupPaddingExpandsOutwards() {
        Document document = canvas(image("a", 20, 20, 40, 40), image("b", 80, 20, 40, 40));

        Edits.Result result = Edits.group(document, List.of("a", "b"), Insets.all(10));
        Layer.Group group = (Layer.Group) result.document().byId(result.createdIds().getFirst()).orElseThrow();

        assertEquals(10, group.bounds().x(), "padding pushes the edge out, not the children in");
        assertEquals(120, group.bounds().width(), "100 of content plus 10 either side");
    }

    @Test
    @DisplayName("ungrouping puts everything back exactly where it was")
    void groupThenUngroupIsIdentity() {
        Document document = canvas(image("a", 20, 30, 40, 10), image("b", 100, 50, 20, 20));

        Edits.Result grouped = Edits.group(document, List.of("a", "b"), Insets.all(6));
        Edits.Result flat = Edits.ungroup(grouped.document(), grouped.createdIds().getFirst());

        assertEquals(new Layer.Bounds(20, 30, 40, 10), boundsOf(flat.document(), "a"));
        assertEquals(new Layer.Bounds(100, 50, 20, 20), boundsOf(flat.document(), "b"));
        assertEquals(2, flat.document().layers().size(), "the group itself should be gone");
    }

    @Test
    @DisplayName("grouping one layer is refused, since it only adds a level to click through")
    void groupingASingleLayerDoesNothing() {
        Document document = canvas(image("a", 0, 0, 10, 10));

        Edits.Result result = Edits.group(document, List.of("a"), Insets.NONE);

        assertTrue(result.createdIds().isEmpty());
        assertInstanceOf(Layer.Image.class, result.document().layers().getFirst());
    }

    @Test
    @DisplayName("grouping preserves the members' order within the group")
    void groupingKeepsStackOrder() {
        Document document = canvas(image("bottom", 0, 0, 10, 10), image("top", 0, 0, 10, 10));

        Edits.Result result = Edits.group(document, List.of("top", "bottom"), Insets.NONE);
        Layer.Group group = (Layer.Group) result.document().byId(result.createdIds().getFirst()).orElseThrow();

        // Passed in the wrong order deliberately; stack order should win.
        assertEquals("bottom", group.children().getFirst().id());
        assertEquals("top", group.children().getLast().id());
    }

    @Test
    @DisplayName("front and back move layers to the ends of the stack")
    void zOrderShortcuts() {
        Document document = canvas(image("a", 0, 0, 10, 10), image("b", 0, 0, 10, 10),
                image("c", 0, 0, 10, 10));

        assertEquals(2, Edits.bringToFront(document, List.of("a")).indexOf("a"));
        assertEquals(0, Edits.sendToBack(document, List.of("c")).indexOf("c"));

        // Several at once keep their order relative to each other.
        Document raised = Edits.bringToFront(document, List.of("a", "b"));
        assertTrue(raised.indexOf("a") < raised.indexOf("b"));
    }

    @Test
    @DisplayName("fit to canvas fills the document exactly")
    void fitToCanvasFillsTheDocument() {
        Document document = canvas(image("a", 30, 30, 10, 10));

        Document fitted = Edits.fitToCanvas(document, "a");

        assertEquals(new Layer.Bounds(0, 0, 256, 256), boundsOf(fitted, "a"));
    }

    @Test
    @DisplayName("operations on ids that are not there are harmless")
    void unknownIdsAreIgnored() {
        Document document = canvas(image("a", 0, 0, 10, 10));

        assertEquals(document, Edits.nudge(document, List.of("missing"), 5, 5));
        assertEquals(document, Edits.fitToCanvas(document, "missing"));
        assertTrue(Edits.duplicate(document, List.of("missing")).createdIds().isEmpty());
        assertTrue(Edits.ungroup(document, "missing").createdIds().isEmpty());
    }

    @Test
    @DisplayName("removing takes out exactly what was asked for")
    void removeDeletesTheSelection() {
        Document document = canvas(image("a", 0, 0, 10, 10), image("b", 0, 0, 10, 10));

        Document removed = Edits.remove(document, List.of("a"));

        assertEquals(1, removed.layers().size());
        assertFalse(removed.byId("a").isPresent());
        assertTrue(removed.byId("b").isPresent());
    }

    @Test
    @DisplayName("nothing mutates the document it was given")
    void operationsAreNonDestructive() {
        Document document = canvas(image("a", 10, 10, 10, 10));

        Edits.nudge(document, List.of("a"), 50, 50);
        Edits.duplicate(document, List.of("a"));
        Edits.bringToFront(document, List.of("a"));

        assertEquals(new Layer.Bounds(10, 10, 10, 10), boundsOf(document, "a"));
        assertEquals(1, document.layers().size());
    }
}
