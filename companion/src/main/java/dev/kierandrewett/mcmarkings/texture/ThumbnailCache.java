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

    /** Ensure a texture is being prepared, returning it when ready. */
    CompletableFuture<TextureHandle> request(RepoImage image);

    /** Upload an image the caller already has in memory, e.g. a generator preview. */
    CompletableFuture<TextureHandle> upload(String key, BufferedImage image);

    /** Drop a single entry, e.g. after its source PNG changed. */
    void evict(String key);

    void evictAll();
}
