package dev.kierandrewett.mcmarkings.js;

import dev.kierandrewett.mcmarkings.render.FontRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the runtime with small inline scripts written to a temporary repository. */
class RhinoGeneratorRuntimeTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private static final FontRegistry FONTS = new FontRegistry(List.of());

    private static final String RED_RECT = """
            defineGenerator({
                id: "red",
                title: "Red rectangle",
                description: "A red rectangle, for testing",
                params: [],
                size: function () { return { width: 20, height: 10 }; },
                render: function (ctx) { ctx.fillRect(0, 0, ctx.width, ctx.height, "#FF0000"); },
            });
            """;

    @Test
    void rendersAScriptToPixels(@TempDir Path repo) throws Exception {
        RhinoGeneratorRuntime runtime = runtimeWith(repo, Map.of("red.js", RED_RECT));

        BufferedImage image = runtime.render("red", Map.of());

        assertEquals(20, image.getWidth());
        assertEquals(10, image.getHeight());
        assertEquals(0xFFFF0000, image.getRGB(10, 5));
    }

    @Test
    void exposesTheDeclaredDefinition(@TempDir Path repo) throws Exception {
        RhinoGeneratorRuntime runtime = runtimeWith(repo, Map.of("plate.js", """
                defineGenerator({
                    id: "plate",
                    title: "Worded plate",
                    description: "A worded plate",
                    params: [
                        { key: "lines", label: "Text lines", type: "lines", default: "", help: "One line per row" },
                        { key: "scheme", label: "Colour", type: "select", options: ["blue", "green"],
                          default: "blue" },
                    ],
                    size: function () { return { width: 10, height: 10 }; },
                    render: function () {},
                });
                """));

        GeneratorDef def = runtime.byId("plate").orElseThrow();
        assertEquals("Worded plate", def.title());
        assertEquals(2, def.params().size());
        assertEquals(ParamDef.ParamType.LINES, def.params().get(0).type());
        assertEquals("One line per row", def.params().get(0).help());
        ParamDef scheme = def.params().get(1);
        assertEquals(ParamDef.ParamType.SELECT, scheme.type());
        assertEquals(List.of("blue", "green"), scheme.options());
        assertEquals("blue", scheme.defaultValue());
        assertEquals(List.of(def), runtime.generators());
    }

    @Test
    void linesArriveAsAnArrayOfStrings(@TempDir Path repo) throws Exception {
        RhinoGeneratorRuntime runtime = runtimeWith(repo, Map.of("rows.js", """
                defineGenerator({
                    id: "rows",
                    title: "Rows",
                    params: [{ key: "rows", label: "Rows", type: "lines", default: "a\\nbb" }],
                    size: function (p) {
                        if (!Array.isArray(p.rows)) { throw new Error("rows was " + typeof p.rows); }
                        return { width: 40, height: 10 * p.rows.length };
                    },
                    render: function (ctx, p) {
                        for (var i = 0; i < p.rows.length; i++) {
                            ctx.fillRect(0, i * 10, p.rows[i].length * 10, 10, "#00FF00");
                        }
                    },
                });
                """));

        BufferedImage supplied = runtime.render("rows", Map.of("rows", List.of("ab", "cde", "f")));
        assertEquals(30, supplied.getHeight(), "one row per supplied line");
        assertEquals(0xFF00FF00, supplied.getRGB(15, 5), "row 0 is two characters wide");
        assertEquals(0, supplied.getRGB(25, 5));
        assertEquals(0xFF00FF00, supplied.getRGB(25, 15), "row 1 is three characters wide");
    }

    @Test
    void appliesDeclaredDefaultsWhenAKeyIsAbsent(@TempDir Path repo) throws Exception {
        RhinoGeneratorRuntime runtime = runtimeWith(repo, Map.of("rows.js", """
                defineGenerator({
                    id: "rows",
                    title: "Rows",
                    params: [
                        { key: "rows", label: "Rows", type: "lines", default: "a\\nbb" },
                        { key: "scale", label: "Scale", type: "number", default: "4" },
                    ],
                    size: function (p) { return { width: 10 * p.scale, height: 10 * p.rows.length }; },
                    render: function () {},
                });
                """));

        BufferedImage image = runtime.render("rows", Map.of());
        assertEquals(20, image.getHeight(), "the two default lines");
        assertEquals(40, image.getWidth(), "the default number");
    }

    @Test
    void convertsEveryParamTypeToItsJavaScriptShape(@TempDir Path repo) throws Exception {
        RhinoGeneratorRuntime runtime = runtimeWith(repo, Map.of("types.js", """
                function check(name, value, expected) {
                    var actual = Array.isArray(value) ? "array" : typeof value;
                    if (actual !== expected) {
                        throw new Error(name + " arrived as " + actual + ", expected " + expected);
                    }
                }
                defineGenerator({
                    id: "types",
                    title: "Types",
                    params: [
                        { key: "t", label: "t", type: "text", default: "hi" },
                        { key: "l", label: "l", type: "lines", default: "" },
                        { key: "s", label: "s", type: "select", options: ["a", "b"], default: "a" },
                        { key: "n", label: "n", type: "number", default: "3" },
                        { key: "b", label: "b", type: "boolean", default: "true" },
                        { key: "c", label: "c", type: "colour", default: "#FFFFFF" },
                        { key: "i", label: "i", type: "image", default: "signs/x.png" },
                    ],
                    size: function (p) {
                        check("t", p.t, "string");
                        check("l", p.l, "array");
                        check("s", p.s, "string");
                        check("n", p.n, "number");
                        check("b", p.b, "boolean");
                        check("c", p.c, "string");
                        check("i", p.i, "string");
                        if (p.n !== 7) { throw new Error("n was " + p.n); }
                        if (p.b !== false) { throw new Error("b was " + p.b); }
                        return { width: 4, height: 4 };
                    },
                    render: function () {},
                });
                """));

        BufferedImage image = runtime.render("types", Map.of("n", 7.0, "b", false));
        assertEquals(4, image.getWidth());
    }

    @Test
    void requireLoadsAndCachesTheSharedLibrary(@TempDir Path repo) throws Exception {
        RhinoGeneratorRuntime runtime = runtimeWith(repo, Map.of(
                "lib.js", """
                        module.exports = {
                            double: function (n) { return n * 2; },
                        };
                        """,
                "uses.js", """
                        var lib = require("lib");
                        var cached = require("lib") === lib;
                        defineGenerator({
                            id: "uses",
                            title: "Uses the library",
                            params: [],
                            size: function () {
                                return { width: lib.double(21), height: cached ? 10 : 99 };
                            },
                            render: function () {},
                        });
                        """));

        BufferedImage image = runtime.render("uses", Map.of());
        assertEquals(42, image.getWidth(), "the library function ran");
        assertEquals(10, image.getHeight(), "require returned the same module object twice");
    }

    @Test
    void libraryIsNotItselfAGenerator(@TempDir Path repo) throws Exception {
        RhinoGeneratorRuntime runtime = runtimeWith(repo, Map.of(
                "lib.js", "module.exports = { x: 1 };",
                "red.js", RED_RECT));

        assertEquals(List.of("red"), runtime.generators().stream().map(GeneratorDef::id).toList());
    }

    @Test
    void reportsCircularRequires(@TempDir Path repo) throws IOException {
        RhinoGeneratorRuntime runtime = brokenRuntimeWith(repo, Map.of(
                "lib.js", "require(\"lib\"); module.exports = {};",
                "uses.js", """
                        var lib = require("lib");
                        defineGenerator({
                            id: "uses", title: "Uses", params: [],
                            size: function () { return { width: 1, height: 1 }; },
                            render: function () {},
                        });
                        """,
                "red.js", RED_RECT));

        assertTrue(runtime.loadErrors().getFirst().contains("circular require"), runtime.loadErrors().toString());
        assertEquals(List.of("red"), runtime.generators().stream().map(GeneratorDef::id).toList());
    }

    @Test
    void oneBrokenScriptDoesNotStopTheOthersLoading(@TempDir Path repo) throws IOException {
        RhinoGeneratorRuntime runtime = brokenRuntimeWith(repo, Map.of(
                "broken.js", "defineGenerator({ id: \"broken\", title: ,);",
                "red.js", RED_RECT));

        assertEquals(1, runtime.loadErrors().size(), runtime.loadErrors().toString());
        assertTrue(runtime.loadErrors().getFirst().startsWith("broken.js:"), runtime.loadErrors().getFirst());
        assertEquals(List.of("red"), runtime.generators().stream().map(GeneratorDef::id).toList());
        assertTrue(runtime.byId("broken").isEmpty());
    }

    @Test
    void rejectsUnknownParamTypesByNamingTheScriptAndKey(@TempDir Path repo) throws IOException {
        RhinoGeneratorRuntime runtime = brokenRuntimeWith(repo, Map.of("odd.js", """
                defineGenerator({
                    id: "odd",
                    title: "Odd",
                    params: [{ key: "shade", label: "Shade", type: "gradient" }],
                    size: function () { return { width: 1, height: 1 }; },
                    render: function () {},
                });
                """));

        String error = runtime.loadErrors().getFirst();
        assertTrue(error.contains("odd.js"), error);
        assertTrue(error.contains("shade"), error);
        assertTrue(error.contains("gradient"), error);
    }

    @Test
    void requiresTheIdToMatchTheFilename(@TempDir Path repo) throws IOException {
        RhinoGeneratorRuntime runtime = brokenRuntimeWith(repo, Map.of("plate.js", """
                defineGenerator({
                    id: "sign", title: "Sign", params: [],
                    size: function () { return { width: 1, height: 1 }; },
                    render: function () {},
                });
                """));

        assertTrue(runtime.loadErrors().getFirst().contains("must match the filename"),
                runtime.loadErrors().getFirst());
    }

    @Test
    void reportsAScriptThatNeverDefinesAGenerator(@TempDir Path repo) throws IOException {
        RhinoGeneratorRuntime runtime = brokenRuntimeWith(repo, Map.of("quiet.js", "var x = 1;"));

        assertTrue(runtime.loadErrors().getFirst().contains("did not call defineGenerator"),
                runtime.loadErrors().getFirst());
    }

    @Test
    void abortsAScriptThatLoopsForever(@TempDir Path repo) throws Exception {
        RhinoGeneratorRuntime runtime = runtimeWith(repo, Map.of("spin.js", """
                defineGenerator({
                    id: "spin",
                    title: "Spin",
                    params: [],
                    size: function () { return { width: 4, height: 4 }; },
                    render: function () { while (true) { } },
                });
                """), 400);

        GeneratorException thrown = assertThrows(GeneratorException.class, () -> runtime.render("spin", Map.of()));
        assertTrue(thrown.getMessage().contains("did not finish in time"), thrown.getMessage());
    }

    @Test
    void abortsAScriptThatLoopsForeverWhileLoading(@TempDir Path repo) throws IOException {
        RhinoGeneratorRuntime runtime = brokenRuntimeWith(repo, Map.of(
                "spin.js", "while (true) { }",
                "red.js", RED_RECT), 400);

        assertTrue(runtime.loadErrors().getFirst().contains("did not finish in time"),
                runtime.loadErrors().getFirst());
        assertEquals(List.of("red"), runtime.generators().stream().map(GeneratorDef::id).toList());
    }

    @Test
    void aScriptCannotReachJava(@TempDir Path repo) throws IOException {
        RhinoGeneratorRuntime runtime = brokenRuntimeWith(repo, Map.of("evil.js", """
                var when = java.lang.System.currentTimeMillis();
                defineGenerator({
                    id: "evil", title: "Evil", params: [],
                    size: function () { return { width: 1, height: 1 }; },
                    render: function () {},
                });
                """));

        String error = runtime.loadErrors().getFirst();
        assertTrue(error.contains("java"), error);
        assertTrue(runtime.byId("evil").isEmpty());
    }

    @Test
    void theJavaBridgesAreAllGone(@TempDir Path repo) throws Exception {
        RhinoGeneratorRuntime runtime = runtimeWith(repo, Map.of("sealed.js", """
                var bridges = ["java", "javax", "Packages", "JavaAdapter", "JavaImporter", "getClass",
                               "importClass", "importPackage"];
                var leaked = [];
                for (var i = 0; i < bridges.length; i++) {
                    if (typeof this[bridges[i]] !== "undefined") { leaked.push(bridges[i]); }
                }
                // The usual way out of a scope: build a function and ask it for the global.
                var top = Function("return this;")();
                if (typeof top.java !== "undefined" || typeof top.Packages !== "undefined") {
                    leaked.push("Function()");
                }
                defineGenerator({
                    id: "sealed",
                    title: "Sealed",
                    params: [],
                    size: function () {
                        if (leaked.length > 0) { throw new Error("leaked " + leaked.join(", ")); }
                        return { width: 2, height: 2 };
                    },
                    render: function () {},
                });
                """));

        assertEquals(2, runtime.render("sealed", Map.of()).getWidth());
    }

    @Test
    void consoleIsAvailableToScriptsAndModules(@TempDir Path repo) throws Exception {
        RhinoGeneratorRuntime runtime = runtimeWith(repo, Map.of(
                "lib.js", """
                        console.log("loading the library");
                        module.exports = { ok: true };
                        """,
                "chatty.js", """
                        var lib = require("lib");
                        console.log("loaded", lib.ok, { a: 1 });
                        console.warn("careful");
                        console.error("but not fatal");
                        defineGenerator({
                            id: "chatty", title: "Chatty", params: [],
                            size: function () { console.log("sizing"); return { width: 2, height: 2 }; },
                            render: function () { console.log("rendering"); },
                        });
                        """));

        assertEquals(2, runtime.render("chatty", Map.of()).getWidth());
    }

    @Test
    void canvasErrorsComeBackNamingTheScript(@TempDir Path repo) throws Exception {
        RhinoGeneratorRuntime runtime = runtimeWith(repo, Map.of("bad.js", """
                defineGenerator({
                    id: "bad", title: "Bad", params: [],
                    size: function () { return { width: 4, height: 4 }; },
                    render: function (ctx) { ctx.fillRect(0, 0, 4, 4, "burgundy"); },
                });
                """));

        GeneratorException thrown = assertThrows(GeneratorException.class, () -> runtime.render("bad", Map.of()));
        assertTrue(thrown.getMessage().startsWith("bad.js:"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("burgundy"), thrown.getMessage());
    }

    @Test
    void refusesAbsurdCanvasSizes(@TempDir Path repo) throws Exception {
        RhinoGeneratorRuntime runtime = runtimeWith(repo, Map.of("huge.js", """
                defineGenerator({
                    id: "huge", title: "Huge", params: [],
                    size: function () { return { width: 100000, height: 100000 }; },
                    render: function () {},
                });
                """));

        GeneratorException thrown = assertThrows(GeneratorException.class, () -> runtime.render("huge", Map.of()));
        assertTrue(thrown.getMessage().contains("limit"), thrown.getMessage());
    }

    @Test
    void namesTheGeneratorThatDoesNotExist(@TempDir Path repo) throws Exception {
        RhinoGeneratorRuntime runtime = runtimeWith(repo, Map.of("red.js", RED_RECT));

        GeneratorException thrown = assertThrows(GeneratorException.class, () -> runtime.render("blue", Map.of()));
        assertTrue(thrown.getMessage().contains("blue"), thrown.getMessage());
    }

    @Test
    void reloadPicksUpChangesOnDisk(@TempDir Path repo) throws Exception {
        RhinoGeneratorRuntime runtime = runtimeWith(repo, Map.of("red.js", RED_RECT));
        assertEquals(20, runtime.render("red", Map.of()).getWidth());

        write(repo, "red.js", RED_RECT.replace("width: 20", "width: 30"));
        runtime.reload();

        assertEquals(30, runtime.render("red", Map.of()).getWidth());
    }

    @Test
    void reloadIsSafeWhileRendersAreRunning(@TempDir Path repo) throws Exception {
        RhinoGeneratorRuntime runtime = runtimeWith(repo, Map.of("red.js", RED_RECT));
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<?>> running = List.of(
                    pool.submit(() -> renderRepeatedly(runtime, 40)),
                    pool.submit(() -> renderRepeatedly(runtime, 40)),
                    pool.submit(() -> {
                        for (int attempt = 0; attempt < 10; attempt++) {
                            runtime.reload();
                        }
                        return null;
                    }));
            for (Future<?> future : running) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void writesAGoldenImageForEyeballing(@TempDir Path repo) throws Exception {
        RhinoGeneratorRuntime runtime = runtimeWith(repo, Map.of("golden.js", """
                defineGenerator({
                    id: "golden",
                    title: "Golden sample",
                    params: [{ key: "legend", label: "Legend", type: "text", default: "SLOW" }],
                    size: function () { return { width: 900, height: 600 }; },
                    render: function (ctx, p) {
                        ctx.roundedRect(0, 0, ctx.width, ctx.height, 40, "#0B4F9E");
                        ctx.strokeRoundedRect(20, 20, ctx.width - 40, ctx.height - 40, 24, 8, "#FFFFFF");
                        ctx.circle(150, 150, 70, "rgba(255, 255, 255, 0.9)");
                        ctx.ring(150, 150, 70, 16, "#C8102E");
                        ctx.polygon([[700, 90], [820, 300], [580, 300]], "#FFD100");
                        ctx.line(60, 380, 840, 380, 6, "#FFFFFF80");
                        ctx.save();
                        ctx.translate(0, 40);
                        ctx.text(p.legend, ctx.width / 2, 480, {
                            size: 130,
                            colour: "#FFFFFF",
                            align: "centre",
                            baseline: "middle",
                            tracking: 12,
                            scaleY: 1.4,
                        });
                        ctx.restore();
                    },
                });
                """));

        BufferedImage image = runtime.render("golden", Map.of("legend", "SLOW"));
        Path output = Path.of("build", "test-output", "golden-sign.png");
        Files.createDirectories(output.getParent());
        javax.imageio.ImageIO.write(image, "png", output.toFile());

        assertTrue(Files.size(output) > 0);
        assertEquals(0xFF, image.getRGB(450, 300) >>> 24, "the plate should be opaque in the middle");
        assertFalse(runtime.generators().isEmpty());
    }

    private static Object renderRepeatedly(RhinoGeneratorRuntime runtime, int times) throws GeneratorException {
        for (int attempt = 0; attempt < times; attempt++) {
            assertEquals(20, runtime.render("red", Map.of()).getWidth());
        }
        return null;
    }

    private static RhinoGeneratorRuntime runtimeWith(Path repo, Map<String, String> scripts) throws Exception {
        return runtimeWith(repo, scripts, 5_000);
    }

    private static RhinoGeneratorRuntime runtimeWith(Path repo, Map<String, String> scripts, long timeoutMillis)
            throws Exception {
        RhinoGeneratorRuntime runtime = build(repo, scripts, timeoutMillis);
        runtime.reload();
        return runtime;
    }

    /** For fixtures that are meant to fail: reload throws, but the good ones must still be there. */
    private static RhinoGeneratorRuntime brokenRuntimeWith(Path repo, Map<String, String> scripts)
            throws IOException {
        return brokenRuntimeWith(repo, scripts, 5_000);
    }

    private static RhinoGeneratorRuntime brokenRuntimeWith(Path repo, Map<String, String> scripts,
            long timeoutMillis) throws IOException {
        RhinoGeneratorRuntime runtime = build(repo, scripts, timeoutMillis);
        assertThrows(GeneratorException.class, runtime::reload);
        return runtime;
    }

    private static RhinoGeneratorRuntime build(Path repo, Map<String, String> scripts, long timeoutMillis)
            throws IOException {
        Files.createDirectories(repo.resolve("generators"));
        for (Map.Entry<String, String> script : scripts.entrySet()) {
            write(repo, script.getKey(), script.getValue());
        }
        return new RhinoGeneratorRuntime(repo, "generators", FONTS, timeoutMillis);
    }

    private static void write(Path repo, String name, String source) throws IOException {
        Files.writeString(repo.resolve("generators").resolve(name), source, StandardCharsets.UTF_8);
    }
}
