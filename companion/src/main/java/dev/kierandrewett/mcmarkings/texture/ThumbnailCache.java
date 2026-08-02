package dev.kierandrewett.mcmarkings.texture;

import dev.kierandrewett.mcmarkings.core.RepoImage;

import java.awt.image.BufferedImage;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Lazily decodes and uploads repository images as GPU textures.
 *
 * <p>The repository holds well over a thousand PNGs, so decoding happens off the
 * render thread and upload is bounded by an eviction policy. Screens should call
 * {@link #peek} while drawing and {@link #request} when a cell scrolls into view,
 * never blocking on the future.
 */
public interface ThumbnailCache {

    /** Already-resident texture, if any. Never blocks. */
    Optional<TextureHandle> peek(RepoImage image);

    /**
     * The larger texture used for a detail pane, if it is already resident.
     *
     * <p>A separate tier because a grid cell and a preview pane want genuinely
     * different resolutions: upscaling the grid's thumbnail into a large pane looks
     * soft, and decoding at preview size for every cell would be wasteful. Kept to
     * a handful of entries, since these are far bigger than a thumbnail.
     */
    Optional<TextureHandle> peekPreview(RepoImage image);

    /** Ensure the larger preview texture is being prepared. */
    CompletableFuture<TextureHandle> requestPreview(RepoImage image);

    /** Ensure a texture is being prepared, returning it when ready. */
    CompletableFuture<TextureHandle> request(RepoImage image);

    /** Upload an image the caller already has in memory, e.g. a generator preview. */
    CompletableFuture<TextureHandle> upload(String key, BufferedImage image);

    /**
     * The same, for an image a panel will hold on to and free itself.
     *
     * <p>An ordinary upload joins the least-recently-used pool, which is right for a
     * thumbnail nobody is looking at any more and wrong for anything a panel keeps a
     * handle to. The editor's canvas and the generator's preview both do: they hold
     * the handle for as long as the panel lives and drop it in onRemoved.
     *
     * <p>Without this the pool evicts them. Browsing a few hundred images pushes the
     * preview off the end, the texture is released, and the handle the panel is still
     * drawing points at whatever the graphics driver put in that slot next. It shows
     * as another image entirely: a generator preview drawn as somebody else's road
     * sign, on returning to the tab.
     *
     * <p>The caller owns it and must evict it. Nothing else will.
     */
    CompletableFuture<TextureHandle> uploadPinned(String key, BufferedImage image);

    /** Drop a single entry, e.g. after its source PNG changed. */
    void evict(String key);

    void evictAll();

    /**
     * Frees everything and stops the workers, for a cache being thrown away.
     *
     * <p>Separate from {@link #evictAll()} because they are different intentions:
     * evicting keeps the cache usable, this ends it. A cache whose services have
     * been discarded has nothing left to decode for.
     */
    void close();
}
