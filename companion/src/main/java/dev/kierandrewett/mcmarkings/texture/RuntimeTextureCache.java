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

    public RuntimeTextureCache(BiFunction<RepoImage, Integer, BufferedImage> loader, int maxResident) {
        this.loader = loader;
        this.maxResident = maxResident;
        this.resident = new LinkedHashMap<>(64, 0.75f, true);
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
                .thenApply(handle -> retainPreview(image.path(), handle))
                .whenComplete((handle, error) -> {
                    inFlight.remove(key);
                    if (error != null) {
                        McMarkingsCompanion.LOGGER.warn("[mcmarkings] preview failed for {}", key, error);
                    }
                }));
    }

    /** Moves a freshly uploaded preview into its own bounded shelf. */
    private synchronized TextureHandle retainPreview(String path, TextureHandle handle) {
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
        while (resident.size() > maxResident) {
            var eldest = resident.entrySet().iterator().next();
            resident.remove(eldest.getKey());
            releaseTexture(eldest.getValue());
        }
    }

    @Override
    public synchronized void evict(String key) {
        Entry removed = resident.remove(key);
        if (removed != null) {
            releaseTexture(removed);
        }
    }

    @Override
    public synchronized void evictAll() {
        resident.values().forEach(this::releaseTexture);
        resident.clear();
        previews.values().forEach(this::releaseTexture);
        previews.clear();
    }

    private void releaseTexture(Entry entry) {
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

    private record Entry(Identifier identifier, int width, int height, OptionalInt glId) implements TextureHandle {

        @Override
        public void close() {
            // Lifetime is owned by the cache; releasing here would let a screen
            // free a texture another screen is still drawing.
        }
    }
}
