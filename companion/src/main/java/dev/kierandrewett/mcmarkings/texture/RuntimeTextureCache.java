package dev.kierandrewett.mcmarkings.texture;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.core.RepoImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.function.Consumer;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;

/**
 * Decodes repository images off-thread and uploads them as GPU textures, keeping
 * only a bounded number resident.
 *
 * <p>The repository holds well over a thousand PNGs. Uploading them all would
 * exhaust VRAM, and decoding on the render thread would stall the game, so this
 * decodes on a worker, hops to the render thread to upload, and evicts
 * least-recently-used entries past a cap.
 *
 * <p>Scaling is not done here. The caller supplies a loader that returns an
 * already-thumbnailed image, which keeps this class free of any policy about how
 * images should be resampled.
 */
public class RuntimeTextureCache implements ThumbnailCache {

    /** Decodes an image at a maximum edge length, so both tiers share one path. */
    private final BiFunction<RepoImage, Integer, BufferedImage> loader;

    private final int maxResident;

    /**
     * Preview textures, kept separately and kept few.
     *
     * <p>A 512px preview is sixteen times the pixels of a 128px thumbnail, so these
     * are capped hard. Someone only ever looks at one at a time; the rest are just
     * the ones they clicked on recently.
     */
    private final LinkedHashMap<String, Entry> previews = new LinkedHashMap<>(8, 0.75f, true);

    private final ExecutorService decodePool = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            runnable -> {
                Thread thread = new Thread(runnable, "mcmarkings-thumbnail");
                thread.setDaemon(true);
                return thread;
            });

    /** Access-ordered so the eldest entry is the least recently used. */
    private final LinkedHashMap<String, Entry> resident;

    private final Map<String, CompletableFuture<TextureHandle>> inFlight = new ConcurrentHashMap<>();

    /** Grid cells are small, so decoding beyond this is wasted work. */
    private static final int THUMBNAIL_EDGE = 128;

    /** Enough to stay sharp in a detail pane without being a full decode. */
    private static final int PREVIEW_EDGE = 512;

    /** Only ever one on screen; the rest are recently clicked. */
    private static final int MAX_RESIDENT_PREVIEWS = 4;

    /**
     * How a texture is handed back to the game.
     *
     * <p>The one line in this class that needs a running client, and the one whose
     * correctness is worth checking: what matters about releasing is how many times
     * it happens and to which entry, which is bookkeeping rather than graphics. Held
     * as a field so a test can watch it without a game attached.
     */
    private final Consumer<Entry> releaser;

    public RuntimeTextureCache(BiFunction<RepoImage, Integer, BufferedImage> loader, int maxResident) {
        this(loader, maxResident, RuntimeTextureCache::releaseThroughClient);
    }

    RuntimeTextureCache(BiFunction<RepoImage, Integer, BufferedImage> loader, int maxResident,
            Consumer<Entry> releaser) {
        this.loader = loader;
        this.maxResident = maxResident;
        this.releaser = releaser;
        this.resident = new LinkedHashMap<>(64, 0.75f, true);
    }

    /**
     * Records an already-uploaded texture, so the eviction rules can be exercised
     * without a GPU. Package-private: the real path in is an upload.
     */
    synchronized Entry retainPinnedForTest(String key, Entry entry) {
        pinned.add(key);
        return retainForTest(key, entry);
    }

    synchronized Entry retainForTest(String key, Entry entry) {
        resident.put(key, entry);
        trim();
        return entry;
    }

    synchronized TextureHandle retainPreviewForTest(String key, String path, Entry entry) {
        return retainPreview(key, path, entry);
    }

    boolean decodePoolIsShutDown() {
        return decodePool.isShutdown();
    }

    synchronized boolean isResident(String key) {
        return resident.containsKey(key);
    }

    @Override
    public synchronized Optional<TextureHandle> peek(RepoImage image) {
        Entry entry = resident.get(image.path());
        return Optional.ofNullable(entry);
    }

    @Override
    public CompletableFuture<TextureHandle> request(RepoImage image) {
        String key = image.path();

        synchronized (this) {
            Entry existing = resident.get(key);
            if (existing != null) {
                return CompletableFuture.completedFuture(existing);
            }
        }

        return inFlight.computeIfAbsent(key, ignored -> CompletableFuture
                .supplyAsync(() -> loader.apply(image, THUMBNAIL_EDGE), decodePool)
                .thenCompose(decoded -> uploadOnRenderThread(key, decoded))
                .whenComplete((handle, error) -> {
                    inFlight.remove(key);
                    if (error != null) {
                        McMarkingsCompanion.LOGGER.warn("[mcmarkings] thumbnail failed for {}", key, error);
                    }
                }));
    }

    @Override
    public synchronized Optional<TextureHandle> peekPreview(RepoImage image) {
        return Optional.ofNullable(previews.get(image.path()));
    }

    @Override
    public CompletableFuture<TextureHandle> requestPreview(RepoImage image) {
        String key = "preview:" + image.path();

        synchronized (this) {
            Entry existing = previews.get(image.path());
            if (existing != null) {
                return CompletableFuture.completedFuture(existing);
            }
        }

        return inFlight.computeIfAbsent(key, ignored -> CompletableFuture
                .supplyAsync(() -> loader.apply(image, PREVIEW_EDGE), decodePool)
                .thenCompose(decoded -> uploadOnRenderThread(key, decoded))
                .thenApply(handle -> retainPreview(key, image.path(), handle))
                .whenComplete((handle, error) -> {
                    inFlight.remove(key);
                    if (error != null) {
                        McMarkingsCompanion.LOGGER.warn("[mcmarkings] preview failed for {}", key, error);
                    }
                }));
    }

    /**
     * Moves a freshly uploaded preview into its own bounded shelf.
     *
     * <p>Out of {@code resident} on the way, not merely into {@code previews}. The
     * upload puts every texture in {@code resident}, so without this the same Entry
     * sits in both maps under two keys, with two independent eviction policies over
     * one GPU texture and no idea of each other.
     *
     * <p>That is reachable by ordinary use. Select an image, then keep scrolling:
     * once 512 more thumbnails have loaded, {@code trim} drops the preview from the
     * eldest end and releases its texture, while {@code previews} still holds the
     * same entry and hands it back to be drawn. The preview pane is showing an image
     * whose texture has been freed underneath it, and nothing anywhere says so.
     *
     * <p>{@code evictAll} had the same shape of problem the other way round, calling
     * release on both maps and so twice on any entry in both.
     *
     * <p>Removed without releasing, because this is a transfer of ownership rather
     * than an eviction. One map owns each texture from here.
     */
    private synchronized TextureHandle retainPreview(String key, String path, TextureHandle handle) {
        resident.remove(key);
        previews.put(path, (Entry) handle);
        while (previews.size() > MAX_RESIDENT_PREVIEWS) {
            var eldest = previews.entrySet().iterator().next();
            previews.remove(eldest.getKey());
            releaseTexture(eldest.getValue());
        }
        return handle;
    }

    @Override
    public CompletableFuture<TextureHandle> upload(String key, BufferedImage image) {
        evict(key);
        return uploadOnRenderThread(key, image);
    }

    @Override
    public CompletableFuture<TextureHandle> uploadPinned(String key, BufferedImage image) {
        evict(key);
        synchronized (this) {
            pinned.add(key);
        }
        return uploadOnRenderThread(key, image);
    }

    /**
     * Keys the trim must leave alone.
     *
     * <p>Small by construction: one per panel that keeps a preview, and each replaces
     * its own on every render. It is not a leak waiting to happen, it is the opposite,
     * since the alternative was freeing a texture somebody was still drawing.
     */
    private final java.util.Set<String> pinned = new java.util.HashSet<>();

    /**
     * Converts pixels off-thread and only hops to the render thread to upload.
     *
     * <p>The conversion is a per-pixel copy, which for a screenful of thumbnails is
     * millions of calls. Doing that on the render thread stutters the game for no
     * reason, since only the GPU upload and the texture manager actually require
     * it.
     */
    private CompletableFuture<TextureHandle> uploadOnRenderThread(String key, BufferedImage source) {
        CompletableFuture<TextureHandle> future = new CompletableFuture<>();

        NativeImage image;
        try {
            image = toNativeImage(source);
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
            return future;
        }

        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            try {
                future.complete(register(key, image));
            } catch (RuntimeException exception) {
                image.close();
                future.completeExceptionally(exception);
            }
        });

        return future;
    }

    /** Safe to call from any thread; allocates native memory but touches no GL. */
    private static NativeImage toNativeImage(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();

        NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // BufferedImage.getRGB and NativeImage.setPixel are both ARGB, so no
                // channel swap. Getting this wrong silently swaps red and blue.
                image.setPixel(x, y, source.getRGB(x, y));
            }
        }
        return image;
    }

    private synchronized Entry register(String key, NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        Identifier identifier = McMarkingsCompanion.id("thumb/" + sanitise(key));
        DynamicTexture texture = new DynamicTexture(() -> "mcmarkings " + key, image);
        texture.upload();
        Minecraft.getInstance().getTextureManager().register(identifier, texture);

        Entry entry = new Entry(identifier, width, height, glIdOf(texture));
        resident.put(key, entry);
        trim();
        return entry;
    }

    /** Evict from the eldest end until back within the cap. */
    private void trim() {
        if (resident.size() <= maxResident) {
            return;
        }
        // Walked rather than repeatedly taking the first, because the first may be
        // pinned and taking it again forever is a hang rather than a wrong picture.
        var entries = resident.entrySet().iterator();
        while (resident.size() > maxResident && entries.hasNext()) {
            var eldest = entries.next();
            if (pinned.contains(eldest.getKey())) {
                continue;
            }
            entries.remove();
            releaseTexture(eldest.getValue());
        }
    }

    @Override
    public synchronized void evict(String key) {
        pinned.remove(key);
        Entry removed = resident.remove(key);
        if (removed != null) {
            releaseTexture(removed);
        }
    }

    @Override
    public synchronized void evictAll() {
        pinned.clear();
        resident.values().forEach(this::releaseTexture);
        resident.clear();
        previews.values().forEach(this::releaseTexture);
        previews.clear();
    }

    /**
     * Done with, as opposed to merely emptied.
     *
     * <p>The decode pool was never shut down. Its threads are daemons, so they never
     * held the game open and nothing ever went wrong loudly, but reloading from
     * settings builds a fresh services object and the old pool's threads stayed for
     * the rest of the session. Half a core's worth each time, for a button someone
     * may press whenever they change a setting.
     */
    @Override
    public void close() {
        evictAll();
        decodePool.shutdownNow();
    }

    private void releaseTexture(Entry entry) {
        releaser.accept(entry);
    }

    private static void releaseThroughClient(Entry entry) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.getTextureManager().release(entry.identifier()));
    }

    /**
     * Raw GL name, needed only for ImGui interop. Backend-specific by nature:
     * Minecraft is moving to Vulkan, so callers must cope with this being absent
     * rather than assume OpenGL.
     */
    private static OptionalInt glIdOf(AbstractTexture texture) {
        GpuTexture gpuTexture = texture.getTexture();
        if (gpuTexture instanceof GlTexture glTexture) {
            return OptionalInt.of(glTexture.glId());
        }
        return OptionalInt.empty();
    }

    private static String sanitise(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
    }

    record Entry(Identifier identifier, int width, int height, OptionalInt glId) implements TextureHandle {

        @Override
        public void close() {
            // Lifetime is owned by the cache; releasing here would let a screen
            // free a texture another screen is still drawing.
        }
    }
}
