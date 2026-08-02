package dev.kierandrewett.mcmarkings.doc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kierandrewett.mcmarkings.core.GridSize;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the layout files the old builder wrote, as documents.
 *
 * <p>The builder was replaced by the layer editor, which does everything it did and
 * a good deal more. Deleting it would have stranded every {@code .layout.json}
 * already published, and a tool that silently drops your old work is not one anybody
 * should trust with new work. This is the bridge, so the builder can go.
 *
 * <p>The mapping is exact rather than approximate. Builder coordinates are in the
 * grid's map-pixel space, 128 to a frame, and each item's {@code scale} multiplies
 * its source image. Exporting then multiplied everything by
 * {@code pixelsPerFrame / 128}. Doing the same multiplication here means the
 * document renders to the same pixels the builder would have published, so
 * reopening something and publishing it again does not move anything.
 *
 * <p>Source dimensions are needed to turn a scale into a size, which is why this
 * takes a resolver rather than reading files itself: it keeps the maths testable
 * without a repository on disk, and lets the caller decide what happens to an image
 * that has since been deleted.
 */
public final class BuilderLayout {

    /** Answers how big a repository image is, without this class knowing about files. */
    @FunctionalInterface
    public interface SizeResolver {

        /**
         * @return the source size, or null when the image is no longer in the repository
         */
        Size sizeOf(String repoPath) throws IOException;
    }

    public record Size(int width, int height) {
    }

    /** What came back, including anything that could not be brought across. */
    public record Result(Document document, List<String> missing) {
    }

    private BuilderLayout() {
    }

    /**
     * Converts one layout file.
     *
     * @param json           the file's contents
     * @param name           what to call the document
     * @param pixelsPerFrame the resolution the document should render at
     * @param sizes          resolves source image dimensions
     */
    public static Result read(String json, String name, int pixelsPerFrame, SizeResolver sizes)
            throws IOException {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        int columns = intOr(root, "columns", 1);
        int rows = intOr(root, "rows", 1);
        GridSize grid = new GridSize(Math.max(1, columns), Math.max(1, rows));

        // The one multiplication the whole conversion turns on. Builder coordinates
        // are in 128-per-frame space; the document renders at pixelsPerFrame.
        double factor = pixelsPerFrame / (double) GridSize.MAP_PIXELS;

        List<Layer> layers = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (JsonElement element : itemsOf(root)) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            String repoPath = stringOr(item, "repoPath", "");
            if (repoPath.isBlank()) {
                continue;
            }

            Size size = sizes.sizeOf(repoPath);
            if (size == null) {
                // Recorded rather than thrown. Losing one image from a composition of
                // twenty should not cost the other nineteen.
                missing.add(repoPath);
                continue;
            }

            double scale = doubleOr(item, "scale", 1.0);
            Layer.Bounds bounds = new Layer.Bounds(
                    (int) Math.round(doubleOr(item, "x", 0.0) * factor),
                    (int) Math.round(doubleOr(item, "y", 0.0) * factor),
                    Math.max(1, (int) Math.round(size.width() * scale * factor)),
                    Math.max(1, (int) Math.round(size.height() * scale * factor)));

            layers.add(new Layer.Image(
                    "imported-" + layers.size(),
                    fileNameOf(repoPath),
                    bounds,
                    true,
                    false,
                    1.0,
                    Insets.NONE,
                    repoPath,
                    // The bounds are already exactly the scaled source, so stretching
                    // is the one fit mode that cannot change what the builder drew.
                    Layer.Fit.STRETCH));
        }

        Document document = new Document(name, grid, pixelsPerFrame, Document.TRANSPARENT, List.copyOf(layers));
        return new Result(document, List.copyOf(missing));
    }

    /**
     * Whether this looks like a builder layout rather than a document.
     *
     * <p>Both are JSON with a name that says nothing, so the two are told apart by
     * shape: a builder layout has {@code items}, a document has {@code layers}.
     */
    public static boolean looksLikeLayout(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            return root.has("items") && !root.has("layers");
        } catch (RuntimeException notJson) {
            return false;
        }
    }

    private static JsonArray itemsOf(JsonObject root) {
        if (!root.has("items") || !root.get("items").isJsonArray()) {
            return new JsonArray();
        }
        return root.getAsJsonArray("items");
    }

    private static String fileNameOf(String repoPath) {
        int slash = repoPath.lastIndexOf('/');
        String tail = slash < 0 ? repoPath : repoPath.substring(slash + 1);
        int dot = tail.lastIndexOf('.');
        return dot <= 0 ? tail : tail.substring(0, dot);
    }

    private static int intOr(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (RuntimeException notANumber) {
            return fallback;
        }
    }

    private static double doubleOr(JsonObject object, String key, double fallback) {
        try {
            return object.has(key) ? object.get(key).getAsDouble() : fallback;
        } catch (RuntimeException notANumber) {
            return fallback;
        }
    }

    private static String stringOr(JsonObject object, String key, String fallback) {
        try {
            return object.has(key) ? object.get(key).getAsString() : fallback;
        } catch (RuntimeException notAString) {
            return fallback;
        }
    }
}
