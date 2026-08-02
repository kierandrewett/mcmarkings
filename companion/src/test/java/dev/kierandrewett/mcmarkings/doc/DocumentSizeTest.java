package dev.kierandrewett.mcmarkings.doc;

import dev.kierandrewett.mcmarkings.core.GridSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A document cannot be a size that takes the game down.
 *
 * <p>The renderer allocates width by height as ARGB. The grid and the resolution were
 * each range-limited in the interface and their product was not, so 64 frames square
 * at 2048 pixels each is 131072 by 131072, which is 68GB, and the failure mode is an
 * OutOfMemoryError rather than anything anyone can act on.
 *
 * <p>Reachable, and not only from a corrupt file. ImGui treats a drag field's range
 * as a soft limit and a control click types straight past it, which is a gesture this
 * mod's own tooltips teach.
 */
class DocumentSizeTest {

    @Test
    @DisplayName("the size the interface can ask for is refused rather than allocated")
    void theWorstTheInterfaceAllowsIsRefused() {
        // The two drag fields' own upper bounds, together.
        assertFalse(Document.fits(new GridSize(64, 64), 2048),
                "64x64 frames at 2048px is 68GB and this says it is fine");
        assertThrows(IllegalArgumentException.class,
                () -> new Document("huge", new GridSize(64, 64), 2048, Document.TRANSPARENT, List.of()));
    }

    @Test
    @DisplayName("a control-click past the field's range is refused too")
    void typedValuesPastTheRangeAreRefused() {
        assertFalse(Document.fits(new GridSize(100_000, 100_000), 2048));
        assertThrows(IllegalArgumentException.class, () -> new Document("typed",
                new GridSize(100_000, 100_000), 2048, Document.TRANSPARENT, List.of()));
    }

    /**
     * The multiplication is the part that has to be careful. As ints, a big enough
     * grid comes back negative, and a negative image size fails somewhere much
     * further away with a complaint nobody can trace back to a number they typed.
     */
    @Test
    @DisplayName("a size that overflows an int is caught here, not somewhere downstream")
    void overflowIsCaughtHere() {
        int huge = 1 << 20;
        assertTrue((huge * 2048) < 0, "this test no longer overflows, so it checks nothing");
        assertFalse(Document.fits(new GridSize(huge, huge), 2048));
    }

    @Test
    @DisplayName("everything anyone would actually build still fits")
    void realDocumentsStillFit() {
        // The largest the grid recommender will ever suggest, at four times map
        // resolution, which is well beyond what anything here renders at.
        assertTrue(Document.fits(new GridSize(8, 8), 512));
        assertTrue(Document.fits(new GridSize(64, 64), 128), "64 frames square at map resolution");
        assertTrue(Document.fits(new GridSize(16, 16), 512));
        assertTrue(Document.fits(new GridSize(1, 1), 2048));

        assertDoesNotThrow(() -> new Document("real", new GridSize(8, 8), 512,
                Document.TRANSPARENT, List.of()));
    }

    @Test
    @DisplayName("a nonsense grid or resolution is not a size, it is a refusal")
    void nonsenseIsRefused() {
        assertFalse(Document.fits(null, 128));
        assertFalse(Document.fits(new GridSize(2, 2), 0));
        assertFalse(Document.fits(new GridSize(2, 2), -1));
    }
}
