package dev.kierandrewett.mcmarkings.doc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import dev.kierandrewett.mcmarkings.core.GridSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serial;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * A {@link Document} as JSON, and back.
 *
 * <p>This is not an internal cache format. A template is a saved document, it lives
 * in the user's repository, and people will open it in an editor and change it by
 * hand. That drives every decision here:
 *
 * <ul>
 *   <li>colours are written as {@code "#RRGGBB"} or {@code "#RRGGBBAA"}, because a
 *       signed decimal ARGB int is unreadable and unwriteable by a human;
 *   <li>enums are lowercase words, read back case-insensitively;
 *   <li>keys come out in a fixed order and the whole file is pretty-printed, so a
 *       save produces a diff of what actually changed rather than a reshuffle;
 *   <li>every field is written even when it holds its default, so the format
 *       documents itself to whoever opens it.
 * </ul>
 *
 * <p>Reading is deliberately forgiving in one direction only. A layer of a kind this
 * build has never heard of is skipped and reported, because a newer version of the
 * mod writing a new kind must not brick an older one, and unknown fields on a known
 * kind are ignored for the same reason. A missing field takes a sensible default
 * rather than a null, because the renderer runs on a game thread and a
 * NullPointerException there is a crash. But a field that is present and wrong, like
 * a colour that is not a colour or a grid of zero columns, is a mistake in the file
 * rather than a message from the future, and it fails loudly with the offending
 * value quoted so it can be found and fixed.
 *
 * <p>Failures are checked ({@link FormatException}), and nothing unchecked leaves
 * these methods, so a bad file is something a screen handles rather than something
 * that takes the client down.
 */
public final class DocumentJson {

    /**
     * Written into every file so a future reader knows what it is looking at.
     *
     * <p>Reading does not gate on it. Refusing a newer file would defeat the
     * skip-and-report handling above, which exists precisely so an older build stays
     * useful against a newer template. A newer version is reported instead, since the
     * real risk is not reading it but saving it back and dropping what was not
     * understood.
     */
    public static final int FORMAT_VERSION = 1;

    private static final String KIND_IMAGE = "image";

    private static final String KIND_TEXT = "text";

    private static final String KIND_SHAPE = "shape";

    private static final String KIND_GROUP = "group";

    /** In the order a person would expect to see them listed in an error. */
    private static final List<String> KINDS = List.of(KIND_IMAGE, KIND_TEXT, KIND_SHAPE, KIND_GROUP);

    /** What a document with no usable name is called, so exports still get a filename. */
    private static final String DEFAULT_DOCUMENT_NAME = "untitled";

    /**
     * Text and shape defaults mirror the JS drawing API's, so a layer written by hand
     * behaves like the equivalent generator script rather than like a second system
     * with its own idea of what white is. They are repeated rather than imported
     * because the document model is deliberately free of the drawing library.
     */
    private static final String DEFAULT_FONT = "sans-serif";

    private static final double DEFAULT_TEXT_SIZE = 100.0;

    private static final int DEFAULT_TEXT_COLOUR = 0xFFFFFFFF;

    private static final int DEFAULT_SHAPE_FILL = 0xFFFFFFFF;

    /** Transparent, so a shape with no border set draws none rather than a black one. */
    private static final int DEFAULT_BORDER_COLOUR = 0x00000000;

    private static final double DEFAULT_OPACITY = 1.0;

    private static final double DEFAULT_VERTICAL_SCALE = 1.0;

    private static final Layer.Bounds ORIGIN = new Layer.Bounds(0, 0, 0, 0);

    /** Ids handed to layers that arrived without one. See {@link LayerReader}. */
    private static final String GENERATED_ID_PREFIX = "layer-";

    /**
     * Named rather than taken from {@code McMarkingsCompanion}, which pulls in
     * Minecraft. The document model is testable without the game and this codec keeps
     * it that way.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger("mcmarkings");

    /**
     * HTML escaping is off because text layers hold real sentences. Left on, an
     * apostrophe in a sign legend comes out as {@code '}, which is unreadable in
     * the very file people are meant to edit.
     */
    private static final Gson WRITER = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .registerTypeHierarchyAdapter(Layer.class, new LayerWriter())
            .create();

    private DocumentJson() {
    }

    /** A document that was read, plus everything the reader had to ignore to get it. */
    public record Result(Document document, List<String> warnings) {

        public Result {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public boolean clean() {
            return warnings.isEmpty();
        }
    }

    /** A document that could not be understood at all, as opposed to one with gaps. */
    public static class FormatException extends IOException {

        @Serial
        private static final long serialVersionUID = 1L;

        public FormatException(String message) {
            super(message);
        }

        public FormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Renders a document as the text of a template file, ending in a newline.
     *
     * <p>Total by construction: there is no document this can refuse. Values a JSON
     * writer cannot represent, which in practice means an infinite or NaN double that
     * arrived from a bad edit upstream, fall back to their defaults rather than
     * throwing out of a save.
     */
    public static String write(Document document) {
        if (document == null) {
            throw new IllegalArgumentException("cannot write a null document");
        }

        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        root.addProperty("name", blank(document.name()) ? DEFAULT_DOCUMENT_NAME : document.name());
        root.add("grid", gridTree(document.grid()));
        root.addProperty("pixelsPerFrame", document.pixelsPerFrame());
        root.addProperty("background", colourText(document.background()));

        JsonArray layers = new JsonArray();
        for (Layer layer : document.layers()) {
            layers.add(WRITER.toJsonTree(layer, Layer.class));
        }
        root.add("layers", layers);

        // A trailing newline because this is a text file in a git repository, and one
        // without it makes every diff show the last line as changed.
        return WRITER.toJson(root) + "\n";
    }

    /**
     * Reads a template file, discarding the report.
     *
     * <p>Anything skipped is logged rather than swallowed, so a template that silently
     * lost a layer still leaves a trace. Callers with somewhere to show it should use
     * {@link #readWithReport(String)} instead.
     */
    public static Document read(String json) throws FormatException {
        Result result = readWithReport(json);
        for (String warning : result.warnings()) {
            LOGGER.warn("[mcmarkings] {}", warning);
        }
        return result.document();
    }

    /** Reads a template file and hands back what was ignored along the way. */
    public static Result readWithReport(String json) throws FormatException {
        if (json == null || json.isBlank()) {
            throw new FormatException("a document cannot be read from empty text");
        }

        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonParseException exception) {
            throw new FormatException("document is not valid JSON: " + exception.getMessage(), exception);
        }
        if (!parsed.isJsonObject()) {
            throw new FormatException("a document must be a JSON object, got " + describe(parsed));
        }

        List<String> warnings = new ArrayList<>();
        try {
            return new Result(readDocument(parsed.getAsJsonObject(), warnings), warnings);
        } catch (JsonParseException exception) {
            // Everything below reports a bad file by throwing this, so it is converted
            // once, here, and only a checked failure ever reaches the caller.
            throw new FormatException("document is malformed: " + exception.getMessage(), exception);
        } catch (IllegalArgumentException exception) {
            // The model's own invariants, if a combination slipped past the checks
            // below. Same reasoning: it is a bad file, not a bug in the caller.
            throw new FormatException("document is malformed: " + exception.getMessage(), exception);
        }
    }

    private static Document readDocument(JsonObject root, List<String> warnings) {
        int version = integer(root, "version", FORMAT_VERSION);
        if (version > FORMAT_VERSION) {
            warnings.add("document is version " + version + " but this build understands version " + FORMAT_VERSION
                    + "; anything it does not recognise will be dropped if the document is saved");
        }

        String name = nonBlank(root, "name", DEFAULT_DOCUMENT_NAME);
        GridSize grid = readGrid(root);
        int pixelsPerFrame = integer(root, "pixelsPerFrame", GridSize.MAP_PIXELS);
        if (pixelsPerFrame <= 0) {
            throw new JsonParseException("\"pixelsPerFrame\" must be at least 1, got " + pixelsPerFrame);
        }
        int background = colourValue(root, "background", Document.TRANSPARENT);

        // A fresh reader per read, so generated ids restart at one and the warnings
        // belong to this document rather than to whatever was read before it.
        LayerReader layerReader = new LayerReader(warnings);
        Gson reader = new GsonBuilder().registerTypeHierarchyAdapter(Layer.class, layerReader).create();

        return new Document(name, grid, pixelsPerFrame, background, readLayers(root, "layers", reader));
    }

    private static List<Layer> readLayers(JsonObject owner, String key, Gson reader) {
        JsonElement element = owner.get(key);
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new JsonParseException("\"" + key + "\" must be an array, got " + describe(element));
        }

        List<Layer> layers = new ArrayList<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            Layer layer = reader.fromJson(entry, Layer.class);
            // Null means the reader skipped an unrecognised kind and has already said
            // so. List.copyOf inside the model would reject it, hence the filter.
            if (layer != null) {
                layers.add(layer);
            }
        }
        return layers;
    }

    private static GridSize readGrid(JsonObject root) {
        JsonElement element = root.get("grid");
        if (element == null || element.isJsonNull()) {
            return new GridSize(1, 1);
        }

        JsonObject grid = objectOf(element, "grid");
        int columns = integer(grid, "columns", 1);
        int rows = integer(grid, "rows", 1);
        if (columns < 1 || rows < 1) {
            throw new JsonParseException("\"grid\" must be at least 1x1, got " + columns + "x" + rows);
        }
        return new GridSize(columns, rows);
    }

    /**
     * Writes one layer, discriminated by {@code kind}.
     *
     * <p>The switch is over a sealed interface with no default, so adding a layer kind
     * to the model stops the build here rather than quietly producing a file that will
     * not read back.
     */
    private static final class LayerWriter implements JsonSerializer<Layer> {

        @Override
        public JsonElement serialize(Layer layer, Type type, JsonSerializationContext context) {
            String kind = kindOf(layer);

            JsonObject object = new JsonObject();
            object.addProperty("kind", kind);
            object.addProperty("id", layer.id() == null ? "" : layer.id());
            object.addProperty("name", blank(layer.name()) ? kind : layer.name());
            object.add("bounds", boundsTree(layer.bounds()));
            object.addProperty("visible", layer.visible());
            object.addProperty("locked", layer.locked());
            object.addProperty("opacity", finite(layer.opacity(), DEFAULT_OPACITY));
            object.add("margins", insetsTree(layer.margins()));

            switch (layer) {
                case Layer.Image image -> {
                    object.addProperty("repoPath", image.repoPath() == null ? "" : image.repoPath());
                    object.addProperty("fit", enumText(image.fit(), Layer.Fit.CONTAIN));
                }
                case Layer.Text text -> {
                    object.addProperty("text", text.text() == null ? "" : text.text());
                    object.addProperty("font", blank(text.font()) ? DEFAULT_FONT : text.font());
                    object.addProperty("size", finite(text.size(), DEFAULT_TEXT_SIZE));
                    object.addProperty("colour", colourText(text.colour()));
                    object.addProperty("horizontalAlign",
                            enumText(text.horizontalAlign(), Layer.HorizontalAlign.LEFT));
                    object.addProperty("verticalAlign", enumText(text.verticalAlign(), Layer.VerticalAlign.TOP));
                    object.addProperty("lineGap", finite(text.lineGap(), 0.0));
                    object.addProperty("tracking", finite(text.tracking(), 0.0));
                    object.addProperty("verticalScale", finite(text.verticalScale(), DEFAULT_VERTICAL_SCALE));
                }
                case Layer.Shape shape -> {
                    object.add("padding", insetsTree(shape.padding()));
                    object.addProperty("fill", colourText(shape.fill()));
                    object.addProperty("cornerRadius", shape.cornerRadius());
                    object.addProperty("borderColour", colourText(shape.borderColour()));
                    object.addProperty("borderWidth", shape.borderWidth());
                }
                case Layer.Group group -> {
                    object.add("padding", insetsTree(group.padding()));
                    JsonArray children = new JsonArray();
                    for (Layer child : group.children()) {
                        children.add(context.serialize(child, Layer.class));
                    }
                    object.add("children", children);
                }
            }
            return object;
        }
    }

    /**
     * Reads one layer, or returns null for a kind this build does not know.
     *
     * <p>Holds per-read state, so an instance belongs to a single call. The id counter
     * is what makes an id-less layer stable: the same text read twice produces the same
     * ids, which is enough for selection and undo to work within a session. It does not
     * try to avoid colliding with an explicit id later in the file, because that would
     * need a pass over the whole document first and a template with a layer called
     * {@code layer-1} and another with no id at all is not worth that.
     */
    private static final class LayerReader implements JsonDeserializer<Layer> {

        private final List<String> warnings;

        private int generated;

        private LayerReader(List<String> warnings) {
            this.warnings = warnings;
        }

        @Override
        public Layer deserialize(JsonElement element, Type type, JsonDeserializationContext context) {
            JsonObject object = objectOf(element, "layer");
            String declared = nonBlank(object, "kind", "");
            String kind = declared.trim().toLowerCase(Locale.ROOT);
            String rawId = nonBlank(object, "id", "");

            if (kind.isEmpty()) {
                warnings.add("skipped a layer with no \"kind\"" + idSuffix(rawId));
                return null;
            }
            if (!KINDS.contains(kind)) {
                warnings.add("skipped a layer of unknown kind \"" + declared + "\"" + idSuffix(rawId)
                        + "; this build understands " + String.join(", ", KINDS));
                return null;
            }

            String id = rawId.isEmpty() ? GENERATED_ID_PREFIX + (++generated) : rawId;
            try {
                return readLayer(kind, id, object, context);
            } catch (IllegalArgumentException exception) {
                // The model's invariants, reported as a bad file with the layer named,
                // because "children cannot contain null" on its own is impossible to act on.
                throw new JsonParseException("layer \"" + id + "\": " + exception.getMessage(), exception);
            }
        }

        private Layer readLayer(String kind, String id, JsonObject object, JsonDeserializationContext context) {
            String name = nonBlank(object, "name", kind);
            Layer.Bounds bounds = readBounds(object);
            boolean visible = bool(object, "visible", true);
            boolean locked = bool(object, "locked", false);
            // Clamped rather than refused: an out-of-range opacity is a slip that has an
            // obvious intended meaning, and clamping keeps the layer instead of losing it.
            double opacity = Math.clamp(number(object, "opacity", DEFAULT_OPACITY), 0.0, 1.0);
            Insets margins = readInsets(object, "margins");

            return switch (kind) {
                case KIND_IMAGE -> new Layer.Image(id, name, bounds, visible, locked, opacity, margins,
                        string(object, "repoPath", ""),
                        enumValue(object, "fit", Layer.Fit.CONTAIN, id));
                case KIND_TEXT -> new Layer.Text(id, name, bounds, visible, locked, opacity, margins,
                        string(object, "text", ""),
                        nonBlank(object, "font", DEFAULT_FONT),
                        number(object, "size", DEFAULT_TEXT_SIZE),
                        colourValue(object, "colour", DEFAULT_TEXT_COLOUR),
                        enumValue(object, "horizontalAlign", Layer.HorizontalAlign.LEFT, id),
                        enumValue(object, "verticalAlign", Layer.VerticalAlign.TOP, id),
                        number(object, "lineGap", 0.0),
                        number(object, "tracking", 0.0),
                        number(object, "verticalScale", DEFAULT_VERTICAL_SCALE));
                case KIND_SHAPE -> new Layer.Shape(id, name, bounds, visible, locked, opacity, margins,
                        readInsets(object, "padding"),
                        colourValue(object, "fill", DEFAULT_SHAPE_FILL),
                        integer(object, "cornerRadius", 0),
                        colourValue(object, "borderColour", DEFAULT_BORDER_COLOUR),
                        integer(object, "borderWidth", 0));
                case KIND_GROUP -> new Layer.Group(id, name, bounds, visible, locked, opacity, margins,
                        readInsets(object, "padding"),
                        readChildren(object, context));
                default -> throw new JsonParseException("unhandled layer kind \"" + kind + "\"");
            };
        }

        /** Recurses through the same adapter, so a group nests to any depth. */
        private List<Layer> readChildren(JsonObject object, JsonDeserializationContext context) {
            JsonElement element = object.get("children");
            if (element == null || element.isJsonNull()) {
                return List.of();
            }
            if (!element.isJsonArray()) {
                throw new JsonParseException("\"children\" must be an array, got " + describe(element));
            }

            List<Layer> children = new ArrayList<>();
            for (JsonElement entry : element.getAsJsonArray()) {
                Layer child = context.deserialize(entry, Layer.class);
                if (child != null) {
                    children.add(child);
                }
            }
            return children;
        }

        private <E extends Enum<E>> E enumValue(JsonObject object, String key, E fallback, String layerId) {
            JsonElement element = object.get(key);
            if (element == null || element.isJsonNull()) {
                return fallback;
            }

            String raw = element.isJsonPrimitive() ? element.getAsString() : describe(element);
            // "center" because half the world spells it that way and the drawing API
            // already accepts it; nothing else here is spelled with an "er".
            String wanted = raw.trim().toUpperCase(Locale.ROOT).replace("CENTER", "CENTRE");
            for (E constant : fallback.getDeclaringClass().getEnumConstants()) {
                if (constant.name().equals(wanted)) {
                    return constant;
                }
            }

            // Reported and defaulted rather than refused, because a value this build has
            // never heard of is exactly what a newer writer would produce, and losing one
            // property of a layer beats losing the whole document.
            warnings.add("layer \"" + layerId + "\": \"" + key + "\" is \"" + raw + "\", which is not one of "
                    + choices(fallback) + "; using " + enumText(fallback, fallback));
            return fallback;
        }

        private static String idSuffix(String id) {
            return id.isEmpty() ? "" : " (id \"" + id + "\")";
        }
    }

    private static String kindOf(Layer layer) {
        return switch (layer) {
            case Layer.Image _ -> KIND_IMAGE;
            case Layer.Text _ -> KIND_TEXT;
            case Layer.Shape _ -> KIND_SHAPE;
            case Layer.Group _ -> KIND_GROUP;
        };
    }

    private static JsonObject gridTree(GridSize grid) {
        GridSize safe = grid == null ? new GridSize(1, 1) : grid;
        JsonObject object = new JsonObject();
        object.addProperty("columns", safe.columns());
        object.addProperty("rows", safe.rows());
        return object;
    }

    private static JsonObject boundsTree(Layer.Bounds bounds) {
        Layer.Bounds safe = bounds == null ? ORIGIN : bounds;
        JsonObject object = new JsonObject();
        object.addProperty("x", safe.x());
        object.addProperty("y", safe.y());
        object.addProperty("width", safe.width());
        object.addProperty("height", safe.height());
        return object;
    }

    private static JsonObject insetsTree(Insets insets) {
        Insets safe = insets == null ? Insets.NONE : insets;
        JsonObject object = new JsonObject();
        object.addProperty("top", safe.top());
        object.addProperty("right", safe.right());
        object.addProperty("bottom", safe.bottom());
        object.addProperty("left", safe.left());
        return object;
    }

    private static Layer.Bounds readBounds(JsonObject owner) {
        JsonElement element = owner.get("bounds");
        if (element == null || element.isJsonNull()) {
            return ORIGIN;
        }

        JsonObject bounds = objectOf(element, "bounds");
        return new Layer.Bounds(
                integer(bounds, "x", 0),
                integer(bounds, "y", 0),
                integer(bounds, "width", 0),
                integer(bounds, "height", 0));
    }

    private static Insets readInsets(JsonObject owner, String key) {
        JsonElement element = owner.get(key);
        if (element == null || element.isJsonNull()) {
            return Insets.NONE;
        }

        JsonObject insets = objectOf(element, key);
        return new Insets(
                integer(insets, "top", 0),
                integer(insets, "right", 0),
                integer(insets, "bottom", 0),
                integer(insets, "left", 0));
    }

    /**
     * ARGB as {@code #RRGGBB}, or {@code #RRGGBBAA} when it is not fully opaque.
     *
     * <p>Alpha goes last rather than first, matching CSS and the drawing API's own
     * parser. Dropping an opaque alpha keeps the common case short.
     */
    private static String colourText(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        String rgb = String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF);
        return alpha == 0xFF ? rgb : rgb + String.format(Locale.ROOT, "%02X", alpha);
    }

    private static int colourValue(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!isString(element)) {
            throw new JsonParseException("\"" + key + "\" must be a colour string, got " + describe(element));
        }
        return parseColour(element.getAsString(), key);
    }

    /** Accepts {@code #RGB}, {@code #RRGGBB} and {@code #RRGGBBAA}, in either case. */
    private static int parseColour(String text, String key) {
        String value = text.trim();
        String digits = value.startsWith("#") ? value.substring(1) : "";
        boolean sized = digits.length() == 3 || digits.length() == 6 || digits.length() == 8;
        if (!sized || !digits.matches("(?i)[0-9a-f]+")) {
            throw new JsonParseException("\"" + key + "\" is \"" + text
                    + "\", which is not a colour; expected #RGB, #RRGGBB or #RRGGBBAA");
        }

        if (digits.length() == 3) {
            StringBuilder expanded = new StringBuilder(6);
            for (char nibble : digits.toCharArray()) {
                expanded.append(nibble).append(nibble);
            }
            digits = expanded.toString();
        }

        int red = Integer.parseInt(digits.substring(0, 2), 16);
        int green = Integer.parseInt(digits.substring(2, 4), 16);
        int blue = Integer.parseInt(digits.substring(4, 6), 16);
        int alpha = digits.length() == 8 ? Integer.parseInt(digits.substring(6, 8), 16) : 0xFF;
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static <E extends Enum<E>> String enumText(E value, E fallback) {
        E resolved = value == null ? fallback : value;
        return resolved.name().toLowerCase(Locale.ROOT);
    }

    private static <E extends Enum<E>> String choices(E example) {
        StringJoiner joiner = new StringJoiner(", ", "[", "]");
        for (E constant : example.getDeclaringClass().getEnumConstants()) {
            joiner.add(constant.name().toLowerCase(Locale.ROOT));
        }
        return joiner.toString();
    }

    private static JsonObject objectOf(JsonElement element, String key) {
        if (!element.isJsonObject()) {
            throw new JsonParseException("\"" + key + "\" must be an object, got " + describe(element));
        }
        return element.getAsJsonObject();
    }

    /** Blank counts as absent, for the fields that must never end up empty. */
    private static String nonBlank(JsonObject object, String key, String fallback) {
        String value = string(object, key, fallback);
        return blank(value) ? fallback : value.trim();
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!isString(element)) {
            throw new JsonParseException("\"" + key + "\" must be a string, got " + describe(element));
        }
        return element.getAsString();
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new JsonParseException("\"" + key + "\" must be true or false, got " + describe(element));
        }
        return element.getAsBoolean();
    }

    private static int integer(JsonObject object, String key, int fallback) {
        return (int) number(object, key, fallback);
    }

    private static double number(JsonObject object, String key, double fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException("\"" + key + "\" must be a number, got " + describe(element));
        }

        double value = element.getAsDouble();
        if (!Double.isFinite(value)) {
            // Gson's lenient parser accepts NaN and Infinity. Neither survives the
            // renderer's maths, and both are far easier to diagnose here than there.
            throw new JsonParseException("\"" + key + "\" must be a finite number, got " + value);
        }
        return value;
    }

    private static boolean isString(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    private static double finite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** What a value is, for an error message, without dumping a whole nested object. */
    private static String describe(JsonElement element) {
        if (element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonArray()) {
            return "an array";
        }
        if (element.isJsonObject()) {
            return "an object";
        }
        return element.toString();
    }
}
