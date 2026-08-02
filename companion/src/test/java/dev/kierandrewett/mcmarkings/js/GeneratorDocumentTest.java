package dev.kierandrewett.mcmarkings.js;

import dev.kierandrewett.mcmarkings.doc.Document;
import dev.kierandrewett.mcmarkings.doc.Layer;
import dev.kierandrewett.mcmarkings.render.FontRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generators that describe themselves as layers.
 *
 * <p>The point of this path is that a generated image stops being a dead end. A
 * script that can say what it is made of produces something editable and saveable
 * as a template, rather than something that can only be regenerated with different
 * parameters. Scripts that only draw must keep working exactly as before.
 */
class GeneratorDocumentTest {

    private static RhinoGeneratorRuntime runtimeWith(Path root, String fileName, String script) throws Exception {
        Path generators = root.resolve("generators");
        Files.createDirectories(generators);
        Files.writeString(generators.resolve(fileName), script, StandardCharsets.UTF_8);

        RhinoGeneratorRuntime runtime =
                new RhinoGeneratorRuntime(root, "generators", new FontRegistry(List.of()));
        runtime.reload();
        return runtime;
    }

    private static final String DRAW_ONLY = """
            defineGenerator({
                id: "plain",
                title: "Plain",
                params: [],
                size: function (params) { return { width: 64, height: 64 }; },
                render: function (ctx, params) { ctx.fillRect(0, 0, 64, 64, "#FF0000"); },
            });
            """;

    private static final String DESCRIBES_ITSELF = """
            defineGenerator({
                id: "plate",
                title: "Plate",
                params: [
                    { key: "legend", label: "Legend", type: "text", default: "30 mph" },
                ],
                size: function (params) { return { width: 512, height: 256 }; },
                render: function (ctx, params) { ctx.fillRect(0, 0, ctx.width, ctx.height, "#0B3F8F"); },
                document: function (params) {
                    return {
                        name: "Plate",
                        grid: { columns: 2, rows: 1 },
                        pixelsPerFrame: 256,
                        background: "#0B3F8F",
                        layers: [
                            {
                                kind: "shape",
                                name: "Panel",
                                bounds: { x: 8, y: 8, width: 496, height: 240 },
                                fill: "#0B3F8F",
                                cornerRadius: 16,
                                borderColour: "#FFFFFF",
                                borderWidth: 6,
                            },
                            {
                                kind: "text",
                                name: "Legend",
                                bounds: { x: 32, y: 32, width: 448, height: 192 },
                                text: params.legend,
                                font: "sans-serif",
                                size: 96,
                                colour: "#FFFFFF",
                                horizontalAlign: "centre",
                                verticalAlign: "middle",
                            },
                        ],
                    };
                },
            });
            """;

    @Test
    @DisplayName("a script that describes itself produces editable layers")
    void describesItselfAsLayers(@TempDir Path root) throws Exception {
        RhinoGeneratorRuntime runtime = runtimeWith(root, "plate.js", DESCRIBES_ITSELF);

        Document document = runtime.document("plate", Map.of("legend", "50 mph")).orElseThrow();

        assertEquals("Plate", document.name());
        assertEquals(512, document.width());
        assertEquals(256, document.height());
        assertEquals(2, document.layers().size());

        assertInstanceOf(Layer.Shape.class, document.layers().getFirst());
        Layer.Text legend = assertInstanceOf(Layer.Text.class, document.layers().getLast());
        assertEquals("50 mph", legend.text(), "parameters should reach the document, not just the drawing");
        assertEquals(Layer.HorizontalAlign.CENTRE, legend.horizontalAlign());
    }

    @Test
    @DisplayName("colours and enums go through the same rules as a saved template")
    void reusesTheTemplateCodecsRules(@TempDir Path root) throws Exception {
        RhinoGeneratorRuntime runtime = runtimeWith(root, "plate.js", DESCRIBES_ITSELF);

        Document document = runtime.document("plate", Map.of()).orElseThrow();
        Layer.Shape panel = (Layer.Shape) document.layers().getFirst();

        // Written as "#0B3F8F" in the script and parsed by the codec that reads
        // templates, so scripts and files cannot disagree about what a colour is.
        assertEquals(0xFF0B3F8F, panel.fill());
        assertEquals(0xFFFFFFFF, panel.borderColour());
        assertEquals(16, panel.cornerRadius());
    }

    @Test
    @DisplayName("a script that only draws still works and simply has no document")
    void drawOnlyGeneratorsAreUnaffected(@TempDir Path root) throws Exception {
        RhinoGeneratorRuntime runtime = runtimeWith(root, "plain.js", DRAW_ONLY);

        assertTrue(runtime.document("plain", Map.of()).isEmpty(),
                "absent document() must not be an error, or every existing generator breaks");

        // And it still renders, which is the whole point of not breaking it.
        assertEquals(64, runtime.render("plain", Map.of()).getWidth());
    }

    @Test
    @DisplayName("a generator that does not exist is reported rather than returning nothing")
    void unknownGeneratorFails(@TempDir Path root) throws Exception {
        RhinoGeneratorRuntime runtime = runtimeWith(root, "plain.js", DRAW_ONLY);

        GeneratorException thrown = assertThrows(GeneratorException.class,
                () -> runtime.document("nope", Map.of()));

        assertTrue(thrown.getMessage().contains("nope"));
    }

    @Test
    @DisplayName("a document that is not a document fails with the script named")
    void nonsenseDocumentFailsClearly(@TempDir Path root) throws Exception {
        RhinoGeneratorRuntime runtime = runtimeWith(root, "bad.js", """
                defineGenerator({
                    id: "bad",
                    title: "Bad",
                    params: [],
                    size: function (params) { return { width: 8, height: 8 }; },
                    render: function (ctx, params) { },
                    document: function (params) { return { grid: { columns: 0, rows: 0 } }; },
                });
                """);

        GeneratorException thrown = assertThrows(GeneratorException.class,
                () -> runtime.document("bad", Map.of()));

        assertTrue(thrown.getMessage().contains("bad.js"), "the message should name the script: " + thrown.getMessage());
    }

    @Test
    @DisplayName("returning nothing is the same as having no document, not a failure")
    void returningUndefinedIsEmpty(@TempDir Path root) throws Exception {
        RhinoGeneratorRuntime runtime = runtimeWith(root, "maybe.js", """
                defineGenerator({
                    id: "maybe",
                    title: "Maybe",
                    params: [],
                    size: function (params) { return { width: 8, height: 8 }; },
                    render: function (ctx, params) { },
                    document: function (params) { return undefined; },
                });
                """);

        assertEquals(Optional.empty(), runtime.document("maybe", Map.of()));
    }

    @Test
    @DisplayName("a runaway document() is stopped like any other script")
    void runawayScriptIsAborted(@TempDir Path root) throws Exception {
        Path generators = root.resolve("generators");
        Files.createDirectories(generators);
        Files.writeString(generators.resolve("loop.js"), """
                defineGenerator({
                    id: "loop",
                    title: "Loop",
                    params: [],
                    size: function (params) { return { width: 8, height: 8 }; },
                    render: function (ctx, params) { },
                    document: function (params) { while (true) { } },
                });
                """, StandardCharsets.UTF_8);

        RhinoGeneratorRuntime runtime =
                new RhinoGeneratorRuntime(root, "generators", new FontRegistry(List.of()), 400);
        runtime.reload();

        assertThrows(GeneratorException.class, () -> runtime.document("loop", Map.of()));
    }

    @Test
    @DisplayName("the document can be saved and reloaded as a template unchanged")
    void documentSurvivesBeingSavedAsATemplate(@TempDir Path root) throws Exception {
        RhinoGeneratorRuntime runtime = runtimeWith(root, "plate.js", DESCRIBES_ITSELF);
        Document generated = runtime.document("plate", Map.of("legend", "Ford")).orElseThrow();

        // The whole reason for describing a generator as layers: what it produces
        // is an ordinary document, so it goes into the template store like any other.
        var store = new dev.kierandrewett.mcmarkings.doc.TemplateStore(root);
        store.save(generated);
        Document reloaded = store.load(store.byName("Plate").orElseThrow());

        assertEquals(generated.layers().size(), reloaded.layers().size());
        assertEquals("Ford", ((Layer.Text) reloaded.layers().getLast()).text());
        assertFalse(reloaded.name().isBlank());
    }

    @Test
    @DisplayName("scripts still cannot reach Java through the returned object")
    void javaCannotLeakThroughTheDocument(@TempDir Path root) throws IOException {
        Path generators = root.resolve("generators");
        Files.createDirectories(generators);
        Files.writeString(generators.resolve("evil.js"), """
                defineGenerator({
                    id: "evil",
                    title: "Evil",
                    params: [],
                    size: function (params) { return { width: 8, height: 8 }; },
                    render: function (ctx, params) { },
                    document: function (params) { return { name: String(java.lang.System) }; },
                });
                """, StandardCharsets.UTF_8);

        RhinoGeneratorRuntime runtime =
                new RhinoGeneratorRuntime(root, "generators", new FontRegistry(List.of()));

        // The reference sits inside a function body, so nothing happens until it is
        // called. What matters is that calling it fails rather than handing a script
        // a Java class through the new path.
        assertDoesNotThrow(runtime::reload);

        GeneratorException thrown = assertThrows(GeneratorException.class,
                () -> runtime.document("evil", Map.of()));
        assertTrue(thrown.getMessage().toLowerCase().contains("java"),
                "should fail on the unreachable name: " + thrown.getMessage());
    }
}
