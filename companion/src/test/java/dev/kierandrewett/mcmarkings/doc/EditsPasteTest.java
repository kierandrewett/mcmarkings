package dev.kierandrewett.mcmarkings.doc;

import dev.kierandrewett.mcmarkings.core.GridSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pasting layers that came from somewhere else.
 *
 * <p>The reason this exists rather than duplicate covering it: copying between two
 * documents is how a plate or a legend gets reused, and duplicate only ever works
 * inside one. The failure that matters is a copy sharing an id with something
 * already there, because then editing one silently moves both and it looks like the
 * editor is haunted rather than like a bug.
 */
class EditsPasteTest {

    private static Layer.Image image(String id, int x, int y) {
        return new Layer.Image(id, id, new Layer.Bounds(x, y, 10, 10), true, false,
                1.0, Insets.NONE, id + ".png", Layer.Fit.CONTAIN);
    }

    private static Document of(Layer... layers) {
        return new Document("doc", new GridSize(2, 1), 128, Document.TRANSPARENT, List.of(layers));
    }

    @Test
    @DisplayName("a pasted layer never shares an id with anything")
    void identitiesAreFresh() {
        Document target = of(image("a", 0, 0));

        Edits.Result result = Edits.paste(target, List.of(image("a", 0, 0)), true);

        assertEquals(2, result.document().layers().size());
        assertNotEquals(result.document().layers().get(0).id(), result.document().layers().get(1).id());
        assertEquals(1, result.createdIds().size());
    }

    @Test
    @DisplayName("pasting twice produces two independent layers")
    void pastingTwiceDoesNotCollide() {
        // The bug this guards: reusing the clipboard's ids means the second paste
        // lands on the first, and moving one moves both.
        List<Layer> clipboard = List.of(image("a", 0, 0));

        Edits.Result once = Edits.paste(of(), clipboard, false);
        Edits.Result twice = Edits.paste(once.document(), clipboard, false);

        assertEquals(2, twice.document().layers().size());
        assertEquals(2, twice.document().layers().stream().map(Layer::id).distinct().count());
    }

    @Test
    @DisplayName("a group's children are all given new identities too")
    void groupChildrenAreRenamedThroughout() {
        Layer.Group group = new Layer.Group("g", "Panel", new Layer.Bounds(0, 0, 40, 40), true, false,
                1.0, Insets.NONE, Insets.NONE, List.of(image("child", 1, 1)));

        Edits.Result result = Edits.paste(of(group), List.of(group), false);

        Layer.Group pasted = (Layer.Group) result.document().layers().get(1);
        assertNotEquals("g", pasted.id());
        assertNotEquals("child", pasted.children().getFirst().id(),
                "a shared child id is the same bug one level down, and harder to see");
    }

    @Test
    @DisplayName("names are kept, because that is usually why it was worth copying")
    void namesSurvive() {
        Edits.Result result = Edits.paste(of(), List.of(image("legend", 0, 0)), false);

        assertEquals("legend", result.document().layers().getFirst().name(),
                "moving a layer between documents is not duplicating it");
    }

    @Test
    @DisplayName("offset puts a paste where it can be grabbed")
    void offsetMovesTheCopy() {
        Edits.Result offset = Edits.paste(of(), List.of(image("a", 20, 30)), true);
        Edits.Result inPlace = Edits.paste(of(), List.of(image("a", 20, 30)), false);

        Layer.Bounds moved = offset.document().layers().getFirst().bounds();
        Layer.Bounds kept = inPlace.document().layers().getFirst().bounds();

        assertTrue(moved.x() > 20 && moved.y() > 30, "an exact overlap cannot be picked up: " + moved);
        assertEquals(20, kept.x(), "pasting elsewhere should land where it was");
        assertEquals(30, kept.y());
    }

    @Test
    @DisplayName("order is kept, so a legend does not come back under its panel")
    void stackOrderSurvives() {
        Edits.Result result = Edits.paste(of(),
                List.of(image("panel", 0, 0), image("legend", 0, 0)), false);

        assertEquals(List.of("panel", "legend"),
                result.document().layers().stream().map(Layer::name).toList());
    }

    @Test
    @DisplayName("an empty clipboard changes nothing")
    void emptyClipboardIsHarmless() {
        Document target = of(image("a", 0, 0));

        Edits.Result result = Edits.paste(target, List.of(), true);

        assertEquals(target, result.document());
        assertTrue(result.createdIds().isEmpty());
    }
}
