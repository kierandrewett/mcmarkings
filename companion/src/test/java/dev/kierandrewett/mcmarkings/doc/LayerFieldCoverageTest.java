package dev.kierandrewett.mcmarkings.doc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kierandrewett.mcmarkings.core.GridSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every field of every layer kind reaches the file.
 *
 * <p>The round trip test next door is thorough and cannot catch the thing this one
 * is for. It builds one document with a non-default value in every field, so a
 * serialiser that drops a field fails it today. Add a field tomorrow, forget the
 * writer, and that fixture simply will not mention it: both sides get the default,
 * the documents compare equal, and the test stays green while every saved template
 * quietly loses the new setting.
 *
 * <p>This asks the records themselves what fields they have, so a field nobody has
 * written a fixture for still has to be written out. It is the one check that gets
 * stronger rather than staler as the model grows.
 *
 * <p>Compared by name, which means the JSON key has to match the component. That is
 * already the convention, and holding it is worth more than the freedom to rename.
 */
class LayerFieldCoverageTest {

    /**
     * Fields deliberately not written.
     *
     * <p>Empty on purpose. Anything added here should come with a reason, because
     * "not serialised" and "lost on save" are the same thing from where a person is
     * sitting.
     */
    private static final List<String> NOT_WRITTEN = List.of();

    private static Layer.Bounds bounds() {
        return new Layer.Bounds(1, 2, 3, 4);
    }

    private static List<Layer> oneOfEachKind() {
        return List.of(
                new Layer.Image("i", "i", bounds(), true, false, 1.0, Insets.NONE, "a.png", Layer.Fit.COVER),
                new Layer.Text("t", "t", bounds(), true, false, 1.0, Insets.NONE, "hi", "sans", 12.0,
                        0xFF000000, Layer.HorizontalAlign.LEFT, Layer.VerticalAlign.TOP, 1.0, 0.0, 1.0),
                new Layer.Shape("s", "s", bounds(), true, false, 1.0, Insets.NONE, Insets.NONE,
                        0xFF000000, 0, 0xFF000000, 0),
                new Layer.Group("g", "g", bounds(), true, false, 1.0, Insets.NONE, Insets.NONE, List.of()));
    }

    @Test
    @DisplayName("no layer field is missing from the written document")
    void everyComponentIsSerialised() {
        List<String> missing = new ArrayList<>();

        for (Layer layer : oneOfEachKind()) {
            Document document = new Document("doc", new GridSize(1, 1), 128,
                    Document.TRANSPARENT, List.of(layer));
            JsonObject written = JsonParser.parseString(DocumentJson.write(document))
                    .getAsJsonObject().getAsJsonArray("layers").get(0).getAsJsonObject();

            for (RecordComponent component : layer.getClass().getRecordComponents()) {
                String name = component.getName();
                if (NOT_WRITTEN.contains(name) || written.has(name)) {
                    continue;
                }
                missing.add(layer.getClass().getSimpleName() + "." + name);
            }
        }

        assertTrue(missing.isEmpty(), () -> """
                A layer field never reaches the file, so it is lost the moment a template \
                is saved and reopened. Add it to DocumentJson's writer and reader, or to \
                NOT_WRITTEN with a reason if it genuinely should not persist.
                missing: """ + missing);
    }

    @Test
    @DisplayName("no document field is missing either")
    void everyDocumentComponentIsSerialised() {
        Document document = new Document("doc", new GridSize(2, 3), 256, 0xFF102030, List.of());
        JsonObject written = JsonParser.parseString(DocumentJson.write(document)).getAsJsonObject();

        List<String> missing = new ArrayList<>();
        for (RecordComponent component : Document.class.getRecordComponents()) {
            if (!written.has(component.getName())) {
                missing.add("Document." + component.getName());
            }
        }

        assertTrue(missing.isEmpty(), () -> "missing: " + missing);
    }

    @Test
    @DisplayName("the check would notice a field that was not written")
    void theCheckCatchesWhatItExistsFor() {
        // A guard nobody has seen fail is a guard nobody should trust. Bounds is
        // written as a nested object, so its own components are the natural stand-in
        // for a field that exists on the record and not at the top level of the JSON.
        JsonObject written = JsonParser.parseString(DocumentJson.write(
                        new Document("doc", new GridSize(1, 1), 128, Document.TRANSPARENT,
                                List.of(oneOfEachKind().getFirst()))))
                .getAsJsonObject().getAsJsonArray("layers").get(0).getAsJsonObject();

        assertTrue(written.has("bounds"), "sanity: bounds is written as an object");
        assertTrue(!written.has("width"), "if this ever passes, the scan is looking at the wrong level");
    }
}
