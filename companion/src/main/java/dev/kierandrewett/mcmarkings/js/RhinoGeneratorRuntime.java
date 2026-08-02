package dev.kierandrewett.mcmarkings.js;

import com.google.gson.Gson;
import dev.kierandrewett.mcmarkings.doc.Document;
import dev.kierandrewett.mcmarkings.doc.DocumentJson;
import dev.kierandrewett.mcmarkings.render.FontRegistry;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.WrapFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Runs the repository's {@code generators/*.js} on Mozilla Rhino.
 *
 * <p>Scripts are trusted-ish, in that they come out of the user's own checkout, but
 * they are still sandboxed: no Java access at all, and a wall-clock budget per call
 * so a bad loop cannot take the game down with it.
 *
 * <p>Loading and rendering are decoupled through an immutable snapshot. {@link #reload()}
 * builds a whole new set of scopes and swaps it in at the end, so a render that is
 * already running keeps the scope it started with and never sees a half-loaded world.
 *
 * <p>A script sees exactly the parameters it declared, with declared defaults filled in.
 * Keys in the map that no param declares are dropped, so the form and the script cannot
 * drift apart without someone noticing.
 */
public final class RhinoGeneratorRuntime implements GeneratorRuntime {

    /** Same logger name as the mod's, without dragging Minecraft classes into a unit test. */
    private static final Logger LOGGER = LoggerFactory.getLogger("mcmarkings");

    private static final long DEFAULT_TIMEOUT_MILLIS = 5_000;

    /** Checked often enough to stop a tight loop quickly, rarely enough to cost nothing. */
    private static final int INSTRUCTION_OBSERVER_THRESHOLD = 10_000;

    private static final int MAX_INTERPRETER_STACK_DEPTH = 1_000;

    private static final int MAX_CANVAS_EDGE = 8_192;

    /** Roughly 160MB of ARGB pixels, which is as much as the client can spare. */
    private static final long MAX_CANVAS_PIXELS = 40_000_000L;

    private static final String SCRIPT_SUFFIX = ".js";

    /** Shared by convention, so it is a module rather than a generator. */
    private static final String LIBRARY_NAME = "lib";

    private static final Object DEADLINE_KEY = new Object();

    private static final Gson GSON = new Gson();

    private final Path repoRoot;

    private final Path generatorDirectory;

    private final FontRegistry fonts;

    private final CanvasApi.ImageSource images;

    private final long timeoutMillis;

    private final ContextFactory contexts = new GeneratorContextFactory();

    private volatile Snapshot snapshot = Snapshot.empty();

    public RhinoGeneratorRuntime(Path repoRoot, String generatorDirectory, FontRegistry fonts) {
        this(repoRoot, generatorDirectory, fonts, DEFAULT_TIMEOUT_MILLIS);
    }

    public RhinoGeneratorRuntime(Path repoRoot, String generatorDirectory, FontRegistry fonts, long timeoutMillis) {
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.generatorDirectory = this.repoRoot.resolve(generatorDirectory).normalize();
        this.fonts = fonts;
        this.images = CanvasApi.repoImages(this.repoRoot);
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public void reload() throws GeneratorException {
        if (!Files.isDirectory(generatorDirectory)) {
            snapshot = Snapshot.empty();
            throw new GeneratorException("generator directory not found: " + generatorDirectory);
        }

        List<Path> scripts;
        try (Stream<Path> entries = Files.list(generatorDirectory)) {
            scripts = entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(SCRIPT_SUFFIX))
                    .filter(path -> !stemOf(path).equals(LIBRARY_NAME))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            snapshot = Snapshot.empty();
            throw new GeneratorException("could not list " + generatorDirectory + ": " + exception.getMessage());
        }

        Map<String, Loaded> loaded = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        Context cx = contexts.enterContext();
        try {
            ScriptableObject shared = createSandboxScope(cx);
            ModuleLoader modules = new ModuleLoader(shared);
            for (Path script : scripts) {
                // One bad script must not cost the user every other generator, so each
                // failure is collected and the rest carry on loading.
                try {
                    Loaded generator = loadGenerator(cx, shared, modules, script);
                    loaded.put(generator.def().id(), generator);
                } catch (GeneratorException exception) {
                    errors.add(exception.getMessage());
                    LOGGER.warn("[mcmarkings] generator failed to load: {}", exception.getMessage());
                }
            }
        } finally {
            Context.exit();
        }

        snapshot = new Snapshot(Collections.unmodifiableMap(loaded), List.copyOf(errors));
        LOGGER.info("[mcmarkings] loaded {} generator(s) from {}", loaded.size(), generatorDirectory);
        if (!errors.isEmpty()) {
            throw new GeneratorException(String.join("\n", errors));
        }
    }

    @Override
    public List<GeneratorDef> generators() {
        return snapshot.byId().values().stream().map(Loaded::def).toList();
    }

    @Override
    public Optional<GeneratorDef> byId(String id) {
        Loaded loaded = snapshot.byId().get(id);
        return Optional.ofNullable(loaded).map(Loaded::def);
    }

    /** Load failures from the last {@link #reload()}, for showing in the UI next to the good ones. */
    public List<String> loadErrors() {
        return snapshot.errors();
    }

    public FontRegistry fonts() {
        return fonts;
    }

    public Path repoRoot() {
        return repoRoot;
    }

    /** Absolute path scripts are read from, worth showing in the UI when nothing loads. */
    public Path generatorDirectory() {
        return generatorDirectory;
    }

    /**
     * Runs a generator's {@code document(params)}, if it has one.
     *
     * <p>The returned object is turned into JSON and handed to the template codec
     * rather than being converted directly. That codec already knows every field
     * alias, default and failure mode, and a second converter would be two sets of
     * rules to keep in step and two places for them to disagree.
     */
    @Override
    public Optional<Document> document(String generatorId, Map<String, Object> params) throws GeneratorException {
        Snapshot current = snapshot;
        Loaded generator = current.byId().get(generatorId);
        if (generator == null) {
            throw new GeneratorException(
                    "no generator with id \"" + generatorId + "\", loaded: " + current.byId().keySet());
        }
        if (generator.documentFunction() == null) {
            return Optional.empty();
        }

        synchronized (generator) {
            Context cx = contexts.enterContext();
            try {
                cx.putThreadLocal(DEADLINE_KEY, System.nanoTime() + timeoutMillis * 1_000_000L);
                Scriptable scope = generator.scope();
                Scriptable jsParams = buildParams(cx, scope, generator.def(), params);

                Object returned = generator.documentFunction()
                        .call(cx, scope, scope, new Object[] {jsParams});
                if (returned == null || Undefined.isUndefined(returned)) {
                    return Optional.empty();
                }

                return Optional.of(DocumentJson.read(GSON.toJson(ScriptJson.of(returned))));
            } catch (ScriptTimeoutError timeout) {
                throw new GeneratorException(generator.source() + ": " + timeout.getMessage());
            } catch (RhinoException exception) {
                throw new GeneratorException(generator.source() + ": " + exception.details());
            } catch (IOException exception) {
                throw new GeneratorException(
                        generator.source() + ": document() did not describe a valid document: "
                                + exception.getMessage());
            } finally {
                Context.exit();
            }
        }
    }

    @Override
    public BufferedImage render(String generatorId, Map<String, Object> params) throws GeneratorException {
        Snapshot current = snapshot;
        Loaded generator = current.byId().get(generatorId);
        if (generator == null) {
            throw new GeneratorException(
                    "no generator with id \"" + generatorId + "\", loaded: " + current.byId().keySet());
        }

        // One script's scope is not thread safe; two concurrent renders of the same
        // generator would trample each other's globals.
        synchronized (generator) {
            Context cx = contexts.enterContext();
            CanvasApi canvas = null;
            try {
                cx.putThreadLocal(DEADLINE_KEY, System.nanoTime() + timeoutMillis * 1_000_000L);
                Scriptable scope = generator.scope();
                Scriptable jsParams = buildParams(cx, scope, generator.def(), params);

                int[] size = callSize(cx, generator, jsParams);
                BufferedImage image = CanvasApi.newCanvas(size[0], size[1]);
                canvas = new CanvasApi(image, fonts, images);
                Scriptable ctx = CanvasBinding.create(cx, scope, canvas);
                generator.render().call(cx, scope, scope, new Object[] {ctx, jsParams});
                return image;
            } catch (ScriptTimeoutError timeout) {
                throw new GeneratorException(generator.source() + ": " + timeout.getMessage());
            } catch (RhinoException exception) {
                throw new GeneratorException(describe(generator.source(), exception), exception);
            } catch (RuntimeException exception) {
                // Anything the drawing stack throws is still the user's problem to see,
                // and the caller is only prepared for a GeneratorException.
                throw new GeneratorException(generator.source() + ": " + exception, exception);
            } finally {
                if (canvas != null) {
                    canvas.dispose();
                }
                cx.putThreadLocal(DEADLINE_KEY, null);
                Context.exit();
            }
        }
    }

    private int[] callSize(Context cx, Loaded generator, Scriptable jsParams) throws GeneratorException {
        Scriptable scope = generator.scope();
        Object result = generator.size().call(cx, scope, scope, new Object[] {jsParams});
        if (!(result instanceof Scriptable size)) {
            throw new GeneratorException(generator.source() + ": size() must return { width, height }, got "
                    + JsValues.text(result));
        }
        long width = Math.round(requiredNumber(generator.source(), size, "width"));
        long height = Math.round(requiredNumber(generator.source(), size, "height"));
        if (width < 1 || height < 1) {
            throw new GeneratorException(
                    generator.source() + ": size() returned " + width + "x" + height + ", both must be at least 1");
        }
        if (width > MAX_CANVAS_EDGE || height > MAX_CANVAS_EDGE || width * height > MAX_CANVAS_PIXELS) {
            throw new GeneratorException(generator.source() + ": size() returned " + width + "x" + height
                    + ", which is above the " + MAX_CANVAS_EDGE + "px edge / " + MAX_CANVAS_PIXELS + "px limit");
        }
        return new int[] {(int) width, (int) height};
    }

    private static double requiredNumber(String source, Scriptable object, String key) throws GeneratorException {
        Object value = JsValues.property(object, key);
        if (value == null) {
            throw new GeneratorException(source + ": size() result is missing \"" + key + "\"");
        }
        try {
            return JsValues.number(value, "size()." + key);
        } catch (IllegalArgumentException exception) {
            throw new GeneratorException(source + ": " + exception.getMessage());
        }
    }

    private Scriptable buildParams(Context cx, Scriptable scope, GeneratorDef def, Map<String, Object> params)
            throws GeneratorException {
        Scriptable object = cx.newObject(scope);
        for (ParamDef param : def.params()) {
            Object raw = params == null ? null : params.get(param.key());
            ScriptableObject.putProperty(object, param.key(), convert(cx, scope, param, raw));
        }
        return object;
    }

    private static Object convert(Context cx, Scriptable scope, ParamDef param, Object raw)
            throws GeneratorException {
        return switch (param.type()) {
            case LINES -> lines(cx, scope, param, raw);
            case NUMBER -> number(param, raw);
            case BOOLEAN -> bool(param, raw);
            case TEXT, SELECT, COLOUR, IMAGE -> string(param, raw);
        };
    }

    private static Object lines(Context cx, Scriptable scope, ParamDef param, Object raw) {
        List<String> values = new ArrayList<>();
        if (raw instanceof List<?> list) {
            list.forEach(item -> values.add(item == null ? "" : item.toString()));
        } else {
            // An empty box means no lines at all, not one empty line.
            String text = rawText(param, raw);
            if (text != null && !text.isEmpty()) {
                values.addAll(List.of(text.split("\r?\n", -1)));
            }
        }
        return JsValues.array(cx, scope, values.toArray());
    }

    private static Object number(ParamDef param, Object raw) throws GeneratorException {
        if (raw instanceof Number value) {
            return value.doubleValue();
        }
        String text = rawText(param, raw);
        if (text == null || text.isBlank()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException exception) {
            throw new GeneratorException("parameter \"" + param.key() + "\" is not a number: " + text);
        }
    }

    private static Object bool(ParamDef param, Object raw) {
        if (raw instanceof Boolean value) {
            return value;
        }
        String text = rawText(param, raw);
        if (text == null) {
            return Boolean.FALSE;
        }
        String normalised = text.trim().toLowerCase(Locale.ROOT);
        return normalised.equals("true") || normalised.equals("yes") || normalised.equals("1");
    }

    private static Object string(ParamDef param, Object raw) {
        String text = rawText(param, raw);
        return text == null ? "" : text;
    }

    /** The supplied value, or the declared default when the form did not send one. */
    private static String rawText(ParamDef param, Object raw) {
        return raw != null ? raw.toString() : param.defaultValue();
    }

    // ---------------------------------------------------------------- loading

    private Loaded loadGenerator(Context cx, ScriptableObject shared, ModuleLoader modules, Path script)
            throws GeneratorException {
        String stem = stemOf(script);
        String source = stem + SCRIPT_SUFFIX;
        Scriptable scope = newScriptScope(shared);
        Capture capture = new Capture();

        ScriptableObject.putProperty(scope, "defineGenerator", JsValues.fn(scope, "defineGenerator", 1,
                (c, s, self, args) -> {
                    if (capture.definition != null) {
                        throw new IllegalStateException("defineGenerator() called more than once");
                    }
                    Object raw = JsValues.arg(args, 0);
                    if (!(raw instanceof Scriptable definition)) {
                        throw new IllegalArgumentException("expects a definition object");
                    }
                    capture.definition = definition;
                    return Undefined.instance;
                }));
        installRequire(scope, modules);
        installConsole(cx, scope, stem);

        evaluate(cx, scope, script, source);

        if (capture.definition == null) {
            throw new GeneratorException(source + ": did not call defineGenerator({ ... })");
        }
        Scriptable definition = capture.definition;

        String id = requiredString(source, definition, "id");
        if (!id.equals(stem)) {
            throw new GeneratorException(source + ": id \"" + id + "\" must match the filename, expected \"" + stem
                    + "\"");
        }
        String title = requiredString(source, definition, "title");
        Object descriptionValue = JsValues.property(definition, "description");
        String description = descriptionValue == null ? "" : JsValues.text(descriptionValue);

        Function size = requiredFunction(source, definition, "size");
        Function render = requiredFunction(source, definition, "render");
        // Optional: a script that can describe itself as layers becomes editable
        // rather than only regenerable. Absent is the normal case.
        Function documentFunction = optionalFunction(definition, "document");
        List<ParamDef> params = params(source, definition);

        return new Loaded(new GeneratorDef(id, title, description, params, documentFunction != null),
                size, render, documentFunction, scope, source);
    }

    private void evaluate(Context cx, Scriptable scope, Path script, String source) throws GeneratorException {
        cx.putThreadLocal(DEADLINE_KEY, System.nanoTime() + timeoutMillis * 1_000_000L);
        try (Reader reader = Files.newBufferedReader(script, StandardCharsets.UTF_8)) {
            cx.evaluateReader(scope, reader, source, 1, null);
        } catch (IOException exception) {
            throw new GeneratorException(source + ": could not read " + script + ": " + exception.getMessage());
        } catch (ScriptTimeoutError timeout) {
            throw new GeneratorException(source + ": " + timeout.getMessage());
        } catch (RhinoException exception) {
            throw new GeneratorException(describe(source, exception), exception);
        } catch (RuntimeException exception) {
            throw new GeneratorException(source + ": " + exception, exception);
        } finally {
            cx.putThreadLocal(DEADLINE_KEY, null);
        }
    }

    private static List<ParamDef> params(String source, Scriptable definition) throws GeneratorException {
        Object raw = JsValues.property(definition, "params");
        if (raw == null) {
            throw new GeneratorException(source + ": definition is missing \"params\" (use [] if there are none)");
        }
        if (!(raw instanceof NativeArray array)) {
            throw new GeneratorException(source + ": \"params\" must be an array");
        }
        List<ParamDef> params = new ArrayList<>();
        for (int index = 0; index < array.getLength(); index++) {
            Object entry = ScriptableObject.getProperty(array, index);
            if (!(entry instanceof Scriptable param)) {
                throw new GeneratorException(source + ": params[" + index + "] must be an object");
            }
            params.add(param(source, index, param));
        }
        return List.copyOf(params);
    }

    private static ParamDef param(String source, int index, Scriptable param) throws GeneratorException {
        Object keyValue = JsValues.property(param, "key");
        if (keyValue == null) {
            throw new GeneratorException(source + ": params[" + index + "] is missing \"key\"");
        }
        String key = JsValues.text(keyValue);
        Object labelValue = JsValues.property(param, "label");
        String label = labelValue == null ? key : JsValues.text(labelValue);

        Object typeValue = JsValues.property(param, "type");
        if (typeValue == null) {
            throw new GeneratorException(source + ": param \"" + key + "\" is missing \"type\"");
        }
        ParamDef.ParamType type;
        try {
            type = ParamDef.ParamType.valueOf(JsValues.text(typeValue).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new GeneratorException(source + ": param \"" + key + "\" has unknown type \""
                    + JsValues.text(typeValue) + "\", expected one of " + typeNames());
        }

        List<String> options = options(source, key, param);
        if (type == ParamDef.ParamType.SELECT && options.isEmpty()) {
            throw new GeneratorException(source + ": param \"" + key + "\" is a select but has no options");
        }

        Object defaultValue = JsValues.property(param, "default");
        String defaulted = defaultValue == null ? null : JsValues.text(defaultValue);
        if (type == ParamDef.ParamType.SELECT && defaulted != null && !options.contains(defaulted)) {
            throw new GeneratorException(source + ": param \"" + key + "\" defaults to \"" + defaulted
                    + "\", which is not one of " + options);
        }

        Object helpValue = JsValues.property(param, "help");
        String help = helpValue == null ? "" : JsValues.text(helpValue);

        return new ParamDef(key, label, type, options, defaulted, help);
    }

    private static List<String> options(String source, String key, Scriptable param) throws GeneratorException {
        Object raw = JsValues.property(param, "options");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof NativeArray array)) {
            throw new GeneratorException(source + ": param \"" + key + "\" has \"options\" that is not an array");
        }
        List<String> options = new ArrayList<>();
        for (int index = 0; index < array.getLength(); index++) {
            options.add(JsValues.text(ScriptableObject.getProperty(array, index)));
        }
        return List.copyOf(options);
    }

    private static String typeNames() {
        return Arrays.stream(ParamDef.ParamType.values())
                .map(type -> "\"" + type.name().toLowerCase(Locale.ROOT) + "\"")
                .toList()
                .toString();
    }

    private static String requiredString(String source, Scriptable object, String key) throws GeneratorException {
        Object value = JsValues.property(object, key);
        if (value == null || JsValues.text(value).isBlank()) {
            throw new GeneratorException(source + ": definition is missing \"" + key + "\"");
        }
        return JsValues.text(value).trim();
    }

    /** Null when the script does not define it, which is not an error. */
    private static Function optionalFunction(Scriptable object, String key) {
        Object value = JsValues.property(object, key);
        return value instanceof Function function ? function : null;
    }

    private static Function requiredFunction(String source, Scriptable object, String key)
            throws GeneratorException {
        Object value = JsValues.property(object, key);
        if (!(value instanceof Function function)) {
            throw new GeneratorException(source + ": definition is missing a \"" + key + "\" function");
        }
        return function;
    }

    // ---------------------------------------------------------------- sandbox

    /**
     * A sealed set of standard objects, shared as the prototype of every script scope.
     * Sealing keeps one script from redefining Array.prototype under another, and the
     * Java bridges are removed outright: the class shutter would refuse them anyway,
     * but a clear "java is not defined" beats a security error nobody can act on.
     */
    private static ScriptableObject createSandboxScope(Context cx) {
        ScriptableObject scope = cx.initStandardObjects(null, false);
        for (String bridge : List.of(
                "Packages", "java", "javax", "org", "com", "edu", "net",
                "getClass", "JavaAdapter", "JavaImporter", "importClass", "importPackage", "loadClass")) {
            scope.delete(bridge);
        }
        scope.sealObject();
        return scope;
    }

    /**
     * Per-script globals hang off a fresh object whose prototype is the shared scope,
     * so two scripts cannot see each other's variables but both get the standard library.
     */
    private static Scriptable newScriptScope(ScriptableObject shared) {
        NativeObject scope = new NativeObject();
        scope.setPrototype(shared);
        scope.setParentScope(null);
        return scope;
    }

    private void installRequire(Scriptable scope, ModuleLoader modules) {
        ScriptableObject.putProperty(scope, "require", JsValues.fn(scope, "require", 1, (c, s, self, args) ->
                modules.require(c, JsValues.stringArg(args, 0, "module name"))));
    }

    private static void installConsole(Context cx, Scriptable scope, String tag) {
        Scriptable console = cx.newObject(scope);
        ScriptableObject.putProperty(console, "log", JsValues.fn(scope, "log", 1, (c, s, self, args) -> {
            LOGGER.info("[generator:{}] {}", tag, join(args));
            return Undefined.instance;
        }));
        ScriptableObject.putProperty(console, "warn", JsValues.fn(scope, "warn", 1, (c, s, self, args) -> {
            LOGGER.warn("[generator:{}] {}", tag, join(args));
            return Undefined.instance;
        }));
        ScriptableObject.putProperty(console, "error", JsValues.fn(scope, "error", 1, (c, s, self, args) -> {
            LOGGER.error("[generator:{}] {}", tag, join(args));
            return Undefined.instance;
        }));
        ScriptableObject.putProperty(scope, "console", console);
    }

    private static String join(Object[] args) {
        StringBuilder joined = new StringBuilder();
        for (Object arg : args) {
            if (!joined.isEmpty()) {
                joined.append(' ');
            }
            joined.append(JsValues.text(arg));
        }
        return joined.toString();
    }

    private static String stemOf(Path path) {
        String name = path.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(SCRIPT_SUFFIX)
                ? name.substring(0, name.length() - SCRIPT_SUFFIX.length())
                : name;
    }

    private static String describe(String source, RhinoException exception) {
        String detail = exception.details();
        int line = exception.lineNumber();
        return source + ": " + detail + (line > 0 ? " (line " + line + ")" : "");
    }

    /** Loads and caches {@code require}d modules for one reload cycle. */
    private final class ModuleLoader {

        private final ScriptableObject shared;

        private final Map<String, Object> exportsByPath = new HashMap<>();

        /** Doubles as the cycle detector and as the chain shown when one is found. */
        private final Deque<String> loading = new ArrayDeque<>();

        private ModuleLoader(ScriptableObject shared) {
            this.shared = shared;
        }

        private Object require(Context cx, String name) {
            String requested = name.trim();
            if (requested.isEmpty()) {
                throw new IllegalArgumentException("module name is required");
            }
            String bare = requested.toLowerCase(Locale.ROOT).endsWith(SCRIPT_SUFFIX)
                    ? requested.substring(0, requested.length() - SCRIPT_SUFFIX.length())
                    : requested;
            Path resolved = generatorDirectory.resolve(bare + SCRIPT_SUFFIX).normalize();
            if (!resolved.startsWith(generatorDirectory)) {
                throw new IllegalArgumentException("module \"" + requested + "\" is outside " + generatorDirectory);
            }
            if (!Files.isRegularFile(resolved)) {
                throw new IllegalArgumentException("no module \"" + requested + "\" (looked for " + resolved + ")");
            }

            String key = resolved.toString();
            if (exportsByPath.containsKey(key)) {
                return exportsByPath.get(key);
            }
            if (loading.contains(key)) {
                throw new IllegalStateException("circular require: " + chain(key));
            }

            loading.push(key);
            try {
                Object exports = load(cx, resolved, bare);
                exportsByPath.put(key, exports);
                return exports;
            } finally {
                loading.pop();
            }
        }

        private Object load(Context cx, Path resolved, String bare) {
            String source = bare + SCRIPT_SUFFIX;
            Scriptable scope = newScriptScope(shared);
            Scriptable module = cx.newObject(scope);
            Scriptable exports = cx.newObject(scope);
            ScriptableObject.putProperty(module, "exports", exports);
            ScriptableObject.putProperty(scope, "module", module);
            ScriptableObject.putProperty(scope, "exports", exports);
            installRequire(scope, this);
            installConsole(cx, scope, bare);
            // A module is not a generator, and silently ignoring the call would be worse
            // than saying so.
            ScriptableObject.putProperty(scope, "defineGenerator", JsValues.fn(scope, "defineGenerator", 1,
                    (c, s, self, args) -> {
                        throw new IllegalStateException("cannot be called from the module " + source);
                    }));

            try (Reader reader = Files.newBufferedReader(resolved, StandardCharsets.UTF_8)) {
                cx.evaluateReader(scope, reader, source, 1, null);
            } catch (IOException exception) {
                throw new IllegalArgumentException("could not read " + resolved + ": " + exception.getMessage());
            }
            // Re-read rather than reusing the object above, because a module is free to
            // replace module.exports outright.
            return ScriptableObject.getProperty(module, "exports");
        }

        private String chain(String key) {
            List<String> names = new ArrayList<>();
            loading.descendingIterator().forEachRemaining(entry -> names.add(Path.of(entry).getFileName().toString()));
            names.add(Path.of(key).getFileName().toString());
            return String.join(" -> ", names);
        }
    }

    private static final class Capture {

        private Scriptable definition;
    }

    /** One loaded script: its declaration, its entry points and the scope they close over. */
    private static final class Loaded {

        private final GeneratorDef def;

        private final Function size;

        private final Function render;

        /** Null unless the script describes itself as layers. */
        private final Function documentFunction;

        private final Scriptable scope;

        private final String source;

        private Loaded(GeneratorDef def, Function size, Function render, Function documentFunction, Scriptable scope, String source) {
            this.def = def;
            this.size = size;
            this.render = render;
            this.documentFunction = documentFunction;
            this.scope = scope;
            this.source = source;
        }

        private GeneratorDef def() {
            return def;
        }

        private Function size() {
            return size;
        }

        private Function render() {
            return render;
        }

        private Function documentFunction() {
            return documentFunction;
        }

        private Scriptable scope() {
            return scope;
        }

        private String source() {
            return source;
        }
    }

    private record Snapshot(Map<String, Loaded> byId, List<String> errors) {

        private static Snapshot empty() {
            return new Snapshot(Map.of(), List.of());
        }
    }

    /**
     * Not a RhinoException on purpose: a script must not be able to catch its own
     * timeout and carry on burning the frame budget.
     */
    private static final class ScriptTimeoutError extends Error {

        private ScriptTimeoutError(String message) {
            super(message, null, false, false);
        }
    }

    private static final class GeneratorContextFactory extends ContextFactory {

        /** Denies every class. Scripts get the canvas and nothing else. */
        private static final ClassShutter DENY_ALL_JAVA = className -> false;

        @Override
        protected Context makeContext() {
            Context cx = super.makeContext();
            cx.setLanguageVersion(Context.VERSION_ES6);
            // Interpreted mode is what makes the instruction observer, and therefore the
            // timeout, work at all. Generators are small; the compiler would not pay off.
            cx.setOptimizationLevel(-1);
            cx.setInstructionObserverThreshold(INSTRUCTION_OBSERVER_THRESHOLD);
            cx.setMaximumInterpreterStackDepth(MAX_INTERPRETER_STACK_DEPTH);
            cx.setClassShutter(DENY_ALL_JAVA);
            cx.setWrapFactory(new NoJavaWrapFactory());
            return cx;
        }

        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            Object deadline = cx.getThreadLocal(DEADLINE_KEY);
            if (deadline instanceof Long nanos && System.nanoTime() > nanos) {
                throw new ScriptTimeoutError("script did not finish in time and was stopped");
            }
        }
    }

    /**
     * Belt and braces alongside the class shutter: if any code path ever tries to hand
     * a Java object to a script, it fails loudly here instead of quietly working.
     */
    private static final class NoJavaWrapFactory extends WrapFactory {

        @Override
        public Object wrap(Context cx, Scriptable scope, Object obj, Class<?> staticType) {
            if (obj == null || obj instanceof Scriptable || obj instanceof String || obj instanceof Number
                    || obj instanceof Boolean || obj instanceof Character || Undefined.isUndefined(obj)) {
                return super.wrap(cx, scope, obj, staticType);
            }
            throw new IllegalStateException("java objects are not available to generator scripts: "
                    + obj.getClass().getName());
        }

        @Override
        public Scriptable wrapAsJavaObject(Context cx, Scriptable scope, Object javaObject, Class<?> staticType) {
            throw new IllegalStateException("java objects are not available to generator scripts: "
                    + javaObject.getClass().getName());
        }
    }
}
