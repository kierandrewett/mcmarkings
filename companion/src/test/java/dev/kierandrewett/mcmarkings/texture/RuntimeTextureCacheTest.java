package dev.kierandrewett.mcmarkings.texture;

import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One map owns each texture.
 *
 * <p>Every upload landed in {@code resident}, and a preview was then also put into
 * {@code previews}, so the same entry sat in two maps under two keys with two
 * independent eviction policies and no knowledge of each other.
 *
 * <p>Ordinary use reaches it. Select an image so a preview loads, then carry on
 * scrolling; once the resident cap of thumbnails has gone past, the eldest end of
 * {@code resident} is the preview, and trimming releases its texture while
 * {@code previews} still holds the same entry and hands it back to be drawn. The
 * detail pane is then showing an image whose texture was freed underneath it.
 *
 * <p>Nothing about that involves a GPU: what matters is which map holds what and how
 * many times release is called. The release is a field so this can watch it.
 */
class RuntimeTextureCacheTest {

    private static RuntimeTextureCache.Entry entry(String name) {
        return new RuntimeTextureCache.Entry(
                McMarkingsCompanion.id("test/" + name), 16, 16, OptionalInt.empty());
    }

    private static RuntimeTextureCache cacheHolding(List<RuntimeTextureCache.Entry> released, int cap) {
        return new RuntimeTextureCache((image, edge) -> null, cap, released::add);
    }

    @Test
    @DisplayName("scrolling past the cap does not free the preview being looked at")
    void scrollingDoesNotFreeTheVisiblePreview() {
        List<RuntimeTextureCache.Entry> released = new ArrayList<>();
        int cap = 8;
        RuntimeTextureCache cache = cacheHolding(released, cap);

        RuntimeTextureCache.Entry preview = entry("chosen");
        cache.retainForTest("preview:signs/chosen.png", preview);
        cache.retainPreviewForTest("preview:signs/chosen.png", "signs/chosen.png", preview);

        // Keep browsing. Well past the cap, so the eldest end turns over completely.
        for (int n = 0; n < cap * 4; n++) {
            cache.retainForTest("signs/thumb-" + n + ".png", entry("thumb-" + n));
        }

        assertFalse(released.contains(preview),
                "the preview on screen had its texture freed after " + released.size() + " evictions");
        assertFalse(cache.isResident("preview:signs/chosen.png"),
                "a preview should be owned by the preview shelf, not by both maps");
    }

    @Test
    @DisplayName("clearing everything releases each texture once, not once per map")
    void evictAllDoesNotDoubleRelease() {
        List<RuntimeTextureCache.Entry> released = new ArrayList<>();
        RuntimeTextureCache cache = cacheHolding(released, 8);

        RuntimeTextureCache.Entry preview = entry("chosen");
        cache.retainForTest("preview:signs/chosen.png", preview);
        cache.retainPreviewForTest("preview:signs/chosen.png", "signs/chosen.png", preview);
        cache.retainForTest("signs/other.png", entry("other"));

        cache.evictAll();

        assertEquals(1, released.stream().filter(preview::equals).count(),
                "released " + released.stream().filter(preview::equals).count() + " times");
        assertEquals(2, released.size(), "both textures should go, and only once each");
    }

    @Test
    @DisplayName("thumbnails past the cap are still evicted, so the cap still means something")
    void thumbnailsStillEvict() {
        List<RuntimeTextureCache.Entry> released = new ArrayList<>();
        int cap = 4;
        RuntimeTextureCache cache = cacheHolding(released, cap);

        for (int n = 0; n < cap + 3; n++) {
            cache.retainForTest("signs/thumb-" + n + ".png", entry("thumb-" + n));
        }

        // The fix transfers ownership rather than skipping eviction, so the ordinary
        // bound has to still hold or a long browse grows without limit.
        assertEquals(3, released.size(), "the eldest three should have gone");
        assertTrue(cache.isResident("signs/thumb-6.png"), "the newest must survive");
    }

    @Test
    @DisplayName("only the few most recent previews are kept")
    void previewShelfStaysSmall() {
        List<RuntimeTextureCache.Entry> released = new ArrayList<>();
        RuntimeTextureCache cache = cacheHolding(released, 64);

        List<RuntimeTextureCache.Entry> previews = new ArrayList<>();
        for (int n = 0; n < 7; n++) {
            RuntimeTextureCache.Entry preview = entry("preview-" + n);
            previews.add(preview);
            cache.retainForTest("preview:signs/" + n + ".png", preview);
            cache.retainPreviewForTest("preview:signs/" + n + ".png", "signs/" + n + ".png", preview);
        }

        // A 512px preview is sixteen times the pixels of a thumbnail, which is why
        // this shelf is capped far harder than the resident one.
        assertEquals(3, released.size(), "the eldest previews should have gone");
        assertTrue(released.contains(previews.getFirst()), "the oldest preview should be the first to go");
        assertFalse(released.contains(previews.getLast()), "the newest preview must survive");
    }
}
