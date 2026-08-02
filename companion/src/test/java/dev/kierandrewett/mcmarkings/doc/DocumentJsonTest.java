package dev.kierandrewett.mcmarkings.doc;

import dev.kierandrewett.mcmarkings.core.GridSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentJsonTest {

    @Test
    @DisplayName("a document with every layer kind, groups included, survives a round trip")
    void roundTripsEveryLayerKind() throws IOException {
        Document original = fullDocument();

        Document reloaded = DocumentJson.read(DocumentJson.write(original));

        assertEquals(original, reloaded);
    }

    @Test
    @DisplayName("a group round trips its children, and their children")
    void roundTripsNestedGroups() throws IOException {
        Document reloaded = DocumentJson.read(DocumentJson.write(fullDocument()));

        Layer.Group outer = (Layer.Group) reloaded.byId("plate").orElseThrow();
        assertEquals(2, outer.children().size());
        assertInstanceOf(Layer.Text.class, outer.children().get(0));

        Layer.Group inner = assertInstanceOf(Layer.Group.class, outer.children().get(1));
        assertEquals("inner", inner.id());
        assertEquals(1, inner.children().size());
        assertEquals("inner-bar", inner.children().getFirst().id());
    }

    @Test
    @DisplayName("an empty layer list is a document, not a failure")
    void roundTripsAnEmptyLayerList() throws IOException {
        Document empty = Document.blank("nothing yet", new GridSize(3, 2), 256);

        String json = DocumentJson.write(empty);
        Document reloaded = DocumentJson.read(json);

        assertEquals(empty, reloaded);
        assertEquals(List.of(), reloaded.layers());
        assertTrue(json.contains("\"layers\": []"), json);
    }

    @Test
    @DisplayName("a layer kind this build has never heard of is skipped and named")
    void unknownKindIsSkippedAndReported() throws IOException {
        String json = """
                {
                  "name": "from the future",
                  "layers": [
                    { "kind": "shape", "id": "keep-me" },
                    { "kind": "hologram", "id": "drop-me", "shimmer": 3 },
                    { "kind": "text", "id": "keep-me-too" }
                  ]
                }
                """;

        DocumentJson.Result result = DocumentJson.readWithReport(json);

        assertEquals(List.of("keep-me", "keep-me-too"),
                result.document().layers().stream().map(Layer::id).toList());
        assertEquals(1, result.warnings().size(), result.warnings().toString());
        assertFalse(result.clean());

        String warning = result.warnings().getFirst();
        assertTrue(warning.contains("hologram"), warning);
        assertTrue(warning.contains("drop-me"), warning);
    }

    @Test
    @DisplayName("an unknown kind nested inside a group is skipped too, not the whole group")
    void unknownKindInsideAGroupIsSkipped() throws IOException {
        String json = """
                {
                  "layers": [
                    {
                      "kind": "group",
                      "id": "wrapper",
                      "children": [
                        { "kind": "particles", "id": "sparkle" },
                        { "kind": "shape", "id": "panel" }
                      ]
                    }
                  ]
                }
                """;

        DocumentJson.Result result = DocumentJson.readWithReport(json);

        Layer.Group wrapper = (Layer.Group) result.document().layers().getFirst();
        assertEquals(List.of("panel"), wrapper.children().stream().map(Layer::id).toList());
        assertTrue(result.warnings().getFirst().contains("particles"), result.warnings().toString());
    }

    @Test
    @DisplayName("a layer with no kind at all is skipped rather than guessed at")
    void layerWithoutAKindIsSkipped() throws IOException {
        DocumentJson.Result result = DocumentJson.readWithReport("""
                { "layers": [ { "id": "mystery", "name": "?" } ] }
                """);

        assertEquals(List.of(), result.document().layers());
        assertTrue(result.warnings().getFirst().contains("mystery"), result.warnings().toString());
    }

    @Test
    @DisplayName("fields this build does not know about are ignored, on the document and on a layer")
    void unknownFieldsAreIgnored() throws IOException {
        String json = """
                {
                  "name": "plate",
                  "grid": { "columns": 1, "rows": 1, "depth": 4 },
                  "shadowPass": true,
                  "layers": [
                    {
                      "kind": "text",
                      "id": "legend",
                      "text": "SLOW",
                      "blurRadius": 12,
                      "annotations": { "author": "someone" }
                    }
                  ]
                }
                """;

        DocumentJson.Result result = DocumentJson.readWithReport(json);

        Layer.Text legend = (Layer.Text) result.document().layers().getFirst();
        assertEquals("SLOW", legend.text());
        assertEquals(new GridSize(1, 1), result.document().grid());
        assertTrue(result.clean(), result.warnings().toString());
    }

    @Test
    @DisplayName("every missing field takes a usable default instead of a null")
    void missingFieldsTakeTheirDefaults() throws IOException {
        String json = """
                {
                  "layers": [
                    { "kind": "image" },
                    { "kind": "text" },
                    { "kind": "shape" },
                    { "kind": "group" }
                  ]
                }
                """;

        Document document = DocumentJson.read(json);

        assertEquals("untitled", document.name());
        assertEquals(new GridSize(1, 1), document.grid());
        assertEquals(GridSize.MAP_PIXELS, document.pixelsPerFrame());
        assertEquals(Document.TRANSPARENT, document.background());
        assertEquals(4, document.layers().size());

        for (Layer layer : document.layers()) {
            assertNotNull(layer.id());
            assertFalse(layer.id().isBlank(), "every layer needs an id");
            assertTrue(layer.visible(), "a layer with no visible flag should show");
            assertFalse(layer.locked());
            assertEquals(1.0, layer.opacity());
            assertEquals(Insets.NONE, layer.margins());
            assertEquals(new Layer.Bounds(0, 0, 0, 0), layer.bounds());
        }

        Layer.Image image = (Layer.Image) document.layers().get(0);
        assertEquals("image", image.name(), "a blank name falls back to the kind");
        assertEquals("", image.repoPath());
        assertEquals(Layer.Fit.CONTAIN, image.fit());

        Layer.Text text = (Layer.Text) document.layers().get(1);
        assertEquals("text", text.name());
        assertEquals("", text.text());
        assertEquals("sans-serif", text.font());
        assertEquals(100.0, text.size());
        assertEquals(0xFFFFFFFF, text.colour());
        assertEquals(Layer.HorizontalAlign.LEFT, text.horizontalAlign());
        assertEquals(Layer.VerticalAlign.TOP, text.verticalAlign());
        assertEquals(0.0, text.lineGap());
        assertEquals(0.0, text.tracking());
        assertEquals(1.0, text.verticalScale());

        Layer.Shape shape = (Layer.Shape) document.layers().get(2);
        assertEquals("shape", shape.name());
        assertEquals(Insets.NONE, shape.padding());
        assertEquals(0xFFFFFFFF, shape.fill());
        assertEquals(0, shape.cornerRadius());
        assertEquals(0x00000000, shape.borderColour(), "no border colour should draw nothing");
        assertEquals(0, shape.borderWidth());

        Layer.Group group = (Layer.Group) document.layers().get(3);
        assertEquals("group", group.name());
        assertEquals(Insets.NONE, group.padding());
        assertEquals(List.of(), group.children());
    }

    @Test
    @DisplayName("an empty object is a blank document rather than an error")
    void emptyObjectIsABlankDocument() throws IOException {
        Document document = DocumentJson.read("{}");

        assertEquals("untitled", document.name());
        assertEquals(List.of(), document.layers());
    }

    @Test
    @DisplayName("layers with no id get ids that are the same every time the file is read")
    void generatedIdsAreStableWithinAndAcrossReads() throws IOException {
        String json = """
                { "layers": [ { "kind": "shape" }, { "kind": "shape", "id": "named" }, { "kind": "shape" } ] }
                """;

        List<String> first = DocumentJson.read(json).layers().stream().map(Layer::id).toList();
        List<String> second = DocumentJson.read(json).layers().stream().map(Layer::id).toList();

        assertEquals(List.of("layer-1", "named", "layer-2"), first);
        assertEquals(first, second, "reading the same text twice should not renumber anything");
    }

    @Test
    @DisplayName("all three colour forms parse, in either case")
    void everyColourFormParses() throws IOException {
        String json = """
                {
                  "background": "#f00",
                  "layers": [
                    {
                      "kind": "shape",
                      "id": "panel",
                      "fill": "#00ff00",
                      "borderColour": "#0000FF80"
                    },
                    { "kind": "text", "id": "legend", "colour": "#ABC" }
                  ]
                }
                """;

        Document document = DocumentJson.read(json);

        assertEquals(0xFFFF0000, document.background(), "#RGB expands each nibble and is opaque");
        Layer.Shape panel = (Layer.Shape) document.layers().get(0);
        assertEquals(0xFF00FF00, panel.fill(), "#RRGGBB is opaque");
        assertEquals(0x800000FF, panel.borderColour(), "#RRGGBBAA carries alpha last, as CSS does");
        assertEquals(0xFFAABBCC, ((Layer.Text) document.layers().get(1)).colour());
    }

    @Test
    @DisplayName("colours are written as hex, and stay put once written")
    void coloursAreWrittenAsHexAndRoundTripStably() throws IOException {
        Document document = Document.blank("colours", new GridSize(1, 1), 128)
                .withBackground(0x80123456)
                .add(new Layer.Shape("panel", "Panel", new Layer.Bounds(0, 0, 8, 8), true, false, 1.0,
                        Insets.NONE, Insets.NONE, 0xFF1B5E20, 0, 0x00000000, 0));

        String json = DocumentJson.write(document);

        assertTrue(json.contains("\"background\": \"#12345680\""), json);
        assertTrue(json.contains("\"fill\": \"#1B5E20\""), json);
        assertTrue(json.contains("\"borderColour\": \"#00000000\""), json);
        assertFalse(json.contains("-2147483648"), "a signed decimal ARGB has no business in a template");

        Document reloaded = DocumentJson.read(json);
        assertEquals(document, reloaded);
        assertEquals(json, DocumentJson.write(reloaded));
    }

    @Test
    @DisplayName("a colour that is not a colour is a bad file, not a default")
    void badColourIsMalformed() {
        DocumentJson.FormatException failure = assertThrows(DocumentJson.FormatException.class,
                () -> DocumentJson.read("{ \"background\": \"forest green\" }"));

        assertTrue(failure.getMessage().contains("forest green"), failure.getMessage());
        assertTrue(failure.getMessage().contains("#RRGGBB"), failure.getMessage());
    }

    @Test
    @DisplayName("enums parse whatever case they are written in")
    void enumsAreCaseInsensitive() throws IOException {
        String json = """
                {
                  "layers": [
                    { "kind": "image", "id": "a", "fit": "COVER" },
                    { "kind": "text", "id": "b", "horizontalAlign": "Centre", "verticalAlign": "bottom" },
                    { "kind": "text", "id": "c", "horizontalAlign": "center" }
                  ]
                }
                """;

        DocumentJson.Result result = DocumentJson.readWithReport(json);

        assertEquals(Layer.Fit.COVER, ((Layer.Image) result.document().layers().get(0)).fit());

        Layer.Text second = (Layer.Text) result.document().layers().get(1);
        assertEquals(Layer.HorizontalAlign.CENTRE, second.horizontalAlign());
        assertEquals(Layer.VerticalAlign.BOTTOM, second.verticalAlign());

        Layer.Text third = (Layer.Text) result.document().layers().get(2);
        assertEquals(Layer.HorizontalAlign.CENTRE, third.horizontalAlign(),
                "\"center\" is accepted because the drawing API already accepts it");
        assertTrue(result.clean(), result.warnings().toString());
    }

    @Test
    @DisplayName("an enum value this build does not know falls back and says so")
    void unknownEnumValueFallsBackAndIsReported() throws IOException {
        String json = """
                {
                  "layers": [
                    { "kind": "image", "id": "a", "fit": "squish" },
                    { "kind": "text", "id": "b", "horizontalAlign": "justified", "verticalAlign": 7 }
                  ]
                }
                """;

        DocumentJson.Result result = DocumentJson.readWithReport(json);

        assertEquals(Layer.Fit.CONTAIN, ((Layer.Image) result.document().layers().get(0)).fit());
        Layer.Text text = (Layer.Text) result.document().layers().get(1);
        assertEquals(Layer.HorizontalAlign.LEFT, text.horizontalAlign());
        assertEquals(Layer.VerticalAlign.TOP, text.verticalAlign());

        assertEquals(3, result.warnings().size(), result.warnings().toString());
        assertTrue(result.warnings().getFirst().contains("squish"), result.warnings().toString());
        assertTrue(result.warnings().getFirst().contains("contain"), result.warnings().toString());
    }

    @Test
    @DisplayName("enums are written as lowercase words")
    void enumsAreWrittenLowercase() {
        String json = DocumentJson.write(Document.blank("signs", new GridSize(1, 1), 128)
                .add(new Layer.Image("a", "A", new Layer.Bounds(0, 0, 1, 1), true, false, 1.0, Insets.NONE,
                        "zebra.png", Layer.Fit.STRETCH))
                .add(new Layer.Text("b", "B", new Layer.Bounds(0, 0, 1, 1), true, false, 1.0, Insets.NONE,
                        "GIVE WAY", "transport", 40.0, 0xFF000000, Layer.HorizontalAlign.CENTRE,
                        Layer.VerticalAlign.MIDDLE, 0.0, 0.0, 1.0)));

        assertTrue(json.contains("\"fit\": \"stretch\""), json);
        assertTrue(json.contains("\"horizontalAlign\": \"centre\""), json);
        assertTrue(json.contains("\"verticalAlign\": \"middle\""), json);
    }

    @Test
    @DisplayName("broken JSON comes back as a checked failure, never as something unchecked")
    void malformedJsonIsACheckedFailure() {
        assertThrows(DocumentJson.FormatException.class, () -> DocumentJson.read("{ \"name\": \"half a file\""));
        assertThrows(DocumentJson.FormatException.class, () -> DocumentJson.read("[1, 2, 3]"));
        assertThrows(DocumentJson.FormatException.class, () -> DocumentJson.read(""));
        assertThrows(DocumentJson.FormatException.class, () -> DocumentJson.read(null));

        // Checked, and an IOException, so callers that already handle file trouble need
        // no second catch block.
        assertInstanceOf(IOException.class,
                assertThrows(DocumentJson.FormatException.class, () -> DocumentJson.read("nonsense")));
    }

    @Test
    @DisplayName("a field of the wrong type is reported with the key and the value")
    void wrongTypesAreReportedWithTheOffendingValue() {
        DocumentJson.FormatException failure = assertThrows(DocumentJson.FormatException.class,
                () -> DocumentJson.read("{ \"layers\": [ { \"kind\": \"shape\", \"opacity\": \"loud\" } ] }"));

        assertTrue(failure.getMessage().contains("opacity"), failure.getMessage());
        assertTrue(failure.getMessage().contains("loud"), failure.getMessage());

        assertThrows(DocumentJson.FormatException.class,
                () -> DocumentJson.read("{ \"layers\": { \"kind\": \"shape\" } }"));
        assertThrows(DocumentJson.FormatException.class,
                () -> DocumentJson.read("{ \"grid\": { \"columns\": 0, \"rows\": 1 } }"));
        assertThrows(DocumentJson.FormatException.class,
                () -> DocumentJson.read("{ \"pixelsPerFrame\": 0 }"));
    }

    @Test
    @DisplayName("an opacity outside 0 to 1 is clamped rather than losing the layer")
    void opacityIsClamped() throws IOException {
        Document document = DocumentJson.read("""
                { "layers": [ { "kind": "shape", "opacity": 2.5 }, { "kind": "shape", "opacity": -1 } ] }
                """);

        assertEquals(1.0, document.layers().get(0).opacity());
        assertEquals(0.0, document.layers().get(1).opacity());
    }

    @Test
    @DisplayName("writing the same document twice gives byte-identical text")
    void writingTwiceGivesTheSameText() throws IOException {
        Document document = fullDocument();

        String first = DocumentJson.write(document);
        String second = DocumentJson.write(document);

        assertEquals(first, second);
        // And a save after a load, which is what actually shows up in a git diff.
        assertEquals(first, DocumentJson.write(DocumentJson.read(first)));
    }

    @Test
    @DisplayName("keys come out in a fixed order so a template diffs cleanly")
    void keysAreOrdered() {
        String json = DocumentJson.write(fullDocument());

        assertOrdered(json, "\"version\"", "\"name\"", "\"grid\"", "\"pixelsPerFrame\"", "\"background\"",
                "\"layers\"");
        assertOrdered(json, "\"kind\": \"image\"", "\"id\": \"backdrop\"", "\"name\": \"Backdrop\"",
                "\"bounds\"", "\"visible\"", "\"locked\"", "\"opacity\"", "\"margins\"", "\"repoPath\"",
                "\"fit\"");
    }

    @Test
    @DisplayName("the file is pretty printed, ends in a newline and does not escape ordinary punctuation")
    void outputIsReadableOnDisk() {
        Document document = Document.blank("shop", new GridSize(1, 1), 128)
                .add(new Layer.Text("legend", "Legend", new Layer.Bounds(0, 0, 128, 40), true, false, 1.0,
                        Insets.NONE, "Sainsbury's <local>", "transport", 24.0, 0xFF000000,
                        Layer.HorizontalAlign.LEFT, Layer.VerticalAlign.TOP, 0.0, 0.0, 1.0));

        String json = DocumentJson.write(document);

        assertTrue(json.endsWith("\n"), "a text file in a repository should end with a newline");
        assertTrue(json.contains("\n  \"name\": \"shop\""), json);
        assertTrue(json.contains("Sainsbury's <local>"), "apostrophes and angle brackets stay readable: " + json);
    }

    @Test
    @DisplayName("the on-disk shape is exactly this")
    void onDiskShapeIsPinned() {
        Document document = Document.blank("give way", new GridSize(2, 1), 256)
                .withBackground(0xFFFFFFFF)
                .add(new Layer.Shape("plate", "Plate", new Layer.Bounds(0, 0, 512, 256), true, false, 1.0,
                        Insets.NONE, Insets.all(8), 0xFFD32F2F, 12, 0xFFFFFFFF, 4));

        String expected = """
                {
                  "version": 1,
                  "name": "give way",
                  "grid": {
                    "columns": 2,
                    "rows": 1
                  },
                  "pixelsPerFrame": 256,
                  "background": "#FFFFFF",
                  "layers": [
                    {
                      "kind": "shape",
                      "id": "plate",
                      "name": "Plate",
                      "bounds": {
                        "x": 0,
                        "y": 0,
                        "width": 512,
                        "height": 256
                      },
                      "visible": true,
                      "locked": false,
                      "opacity": 1.0,
                      "margins": {
                        "top": 0,
                        "right": 0,
                        "bottom": 0,
                        "left": 0
                      },
                      "padding": {
                        "top": 8,
                        "right": 8,
                        "bottom": 8,
                        "left": 8
                      },
                      "fill": "#D32F2F",
                      "cornerRadius": 12,
                      "borderColour": "#FFFFFF",
                      "borderWidth": 4
                    }
                  ]
                }
                """;

        assertEquals(expected, DocumentJson.write(document));
    }

    @Test
    @DisplayName("a template written by a newer build still reads, with a note about it")
    void newerVersionIsReadAndReported() throws IOException {
        DocumentJson.Result result = DocumentJson.readWithReport("""
                { "version": 99, "name": "future", "layers": [] }
                """);

        assertEquals("future", result.document().name());
        assertTrue(result.warnings().getFirst().contains("99"), result.warnings().toString());
    }

    /** Every kind, a group two deep, and no field left on its default. */
    private static Document fullDocument() {
        Layer.Text caption = new Layer.Text("caption", "Caption", new Layer.Bounds(16, 200, 480, 48),
                true, false, 0.9, Insets.of(2, 4), "MAX SPEED\n10 MPH", "transport", 36.5, 0xFF212121,
                Layer.HorizontalAlign.CENTRE, Layer.VerticalAlign.MIDDLE, 1.25, -0.5, 1.1);

        Layer.Shape bar = new Layer.Shape("inner-bar", "Inner bar", new Layer.Bounds(24, 260, 400, 12),
                false, true, 0.25, Insets.all(1), Insets.of(3, 6), 0x80D32F2F, 6, 0xFF000000, 2);

        Layer.Group inner = new Layer.Group("inner", "Inner", new Layer.Bounds(20, 250, 420, 40),
                true, false, 0.75, Insets.all(2), Insets.all(5), List.of(bar));

        Layer.Group plate = new Layer.Group("plate", "Plate", new Layer.Bounds(8, 8, 496, 300),
                true, false, 0.8, Insets.of(6, 12), Insets.all(10), List.of(caption, inner));

        Layer.Image backdrop = new Layer.Image("backdrop", "Backdrop", new Layer.Bounds(0, 0, 512, 256),
                true, false, 0.5, Insets.all(4), "signs/give_way.png", Layer.Fit.COVER);

        return new Document("speed limit", new GridSize(4, 2), 256, 0x401B5E20,
                List.of(backdrop, plate));
    }

    private static void assertOrdered(String json, String... fragments) {
        int previous = -1;
        String previousFragment = "the start of the file";
        for (String fragment : fragments) {
            int at = json.indexOf(fragment);
            assertTrue(at >= 0, fragment + " is missing from:\n" + json);
            assertTrue(at > previous, fragment + " should come after " + previousFragment + " in:\n" + json);
            previous = at;
            previousFragment = fragment;
        }
    }

    /**
     * The count is the fix. Two call sites had drifted apart and the one that named
     * only the first problem was the one shown when opening a placed map, which is
     * exactly where the number matters: whether one layer is missing or four decides
     * whether someone places it again.
     */
    @Test
    void countsTheProblemsItIsNotShowing() {
        String one = DocumentJson.describeWarnings("Give Way", List.of("unknown layer kind: arc"), 70);
        assertTrue(one.contains("unknown layer kind: arc"), one);
        assertFalse(one.contains("more"), "a single problem should not be counted at: " + one);

        String several = DocumentJson.describeWarnings("Give Way",
                List.of("unknown layer kind: arc", "bad colour", "bad colour", "missing bounds"), 70);
        assertTrue(several.contains("(+3 more)"), several);
    }

    @Test
    void shortensALongProblemRatherThanFillingTheStatusLine() {
        String described = DocumentJson.describeWarnings("Sign", List.of("x".repeat(200)), 70);
        assertTrue(described.endsWith("..."), described);
        assertTrue(described.length() < 120, "status lines have to fit: " + described.length());
    }

    @Test
    void saysNothingWhenNothingWentWrong() {
        assertEquals("", DocumentJson.describeWarnings("Sign", List.of(), 70));
        assertEquals("", DocumentJson.describeWarnings("Sign", null, 70));
    }
}
