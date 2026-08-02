package dev.kierandrewett.mcmarkings.doc;

import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.render.FontRegistry;
import dev.kierandrewett.mcmarkings.render.ImageComposer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A group larger than anything can draw costs that group, not the game.
 *
 * <p>The document canvas was capped and this was not. A group renders onto a scratch
 * image its own size, and a group is deliberately not clipped to the canvas, so it
 * can be far larger than the document holding it. The size field in the properties
 * panel had a maximum of Integer.MAX_VALUE, which went straight into that allocation.
 *
 * <p>Reported rather than thrown, because this runs against documents from disk as
 * well as from the editor and one impossible group should not cost the whole render.
 */
class GroupSizeRenderTest {

    private static DocumentRenderer renderer() {
        return new DocumentRenderer(
                new FontRegistry(List.of(System.getProperty("user.home") + "/.local/share/fonts",
                        "/usr/share/fonts")),
                new ImageComposer());
    }

    private static Layer.Shape shape(String id, int size) {
        return new Layer.Shape(id, id, new Layer.Bounds(0, 0, size, size), true, false, 1.0,
                Insets.NONE, Insets.NONE, 0xFFCC2222, 0, 0, 0);
    }

    private static Document withGroup(int edge) {
        Layer.Group group = new Layer.Group("g", "Enormous",
                new Layer.Bounds(0, 0, edge, edge), true, false, 1.0, Insets.NONE, Insets.NONE,
                List.of(shape("child", 20)));
        return new Document("big group", new GridSize(2, 2), 128, Document.TRANSPARENT,
                List.of(shape("backdrop", 40), group));
    }

    @Test
    @DisplayName("an impossible group is named, and the rest of the document still draws")
    void anImpossibleGroupIsReportedNotThrown() {
        DocumentRenderer renderer = renderer();
        BufferedImage rendered = assertDoesNotThrow(
                () -> renderer.render(withGroup(200_000), path -> null),
                "the render died rather than reporting the group");

        assertNotNull(rendered, "nothing came back at all");
        assertEquals(256, rendered.getWidth(), "the document's own size should be unaffected");
        assertTrue(renderer.problems().stream().anyMatch(problem -> problem.contains("Enormous")),
                () -> "the group was skipped without saying so: " + renderer.problems());
    }

    /**
     * The number is what matters, so it is checked either side rather than only at
     * the extreme. A cap nobody can reach protects nothing, and one that catches
     * ordinary work is worse than the problem.
     */
    @Test
    @DisplayName("a large but sane group still renders")
    void aLargeSaneGroupStillRenders() {
        DocumentRenderer renderer = renderer();
        assertDoesNotThrow(() -> renderer.render(withGroup(4096), path -> null));
        assertEquals(List.of(), renderer.problems(),
                "a group well inside the budget was refused");
    }

    @Test
    @DisplayName("the group budget is the same one the document gets")
    void theBudgetIsShared() {
        // Two caps that could drift apart would be two numbers to keep in step, and
        // the second one would be the one nobody remembered.
        assertTrue(Document.MAX_PIXELS >= 8192L * 8192L, "the shared budget has shrunk");
        assertTrue(8192L * 8192L <= Document.MAX_PIXELS,
                "a layer can no longer be as large as the canvas it sits on");
    }
}
