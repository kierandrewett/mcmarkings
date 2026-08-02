package dev.kierandrewett.mcmarkings.doc;

import dev.kierandrewett.mcmarkings.core.GridSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bringing old builder compositions across.
 *
 * <p>The builder is being deleted, so this is the only thing standing between a
 * published {@code .layout.json} and it being unopenable. The geometry matters more
 * than anything else here: a conversion that moves everything by a few pixels is
 * worse than one that refuses, because it looks like it worked.
 */
class BuilderLayoutTest {

    /** Every image is 64x32, so a wrong axis shows up rather than cancelling out. */
    private static final BuilderLayout.SizeResolver SIZES =
            path -> path.startsWith("missing") ? null : new BuilderLayout.Size(64, 32);

    private static final String SAMPLE = """
            {
              "columns": 1,
              "rows": 2,
              "items": [
                { "repoPath": "signs/a.png", "x": 0.0,  "y": 118.5, "scale": 0.125, "z": 0 },
                { "repoPath": "signs/b.png", "x": 16.0, "y": 4.0,   "scale": 0.5,   "z": 1 }
              ]
            }
            """;

    @Test
    @DisplayName("the grid comes across as it was")
    void gridIsPreserved() throws IOException {
        Document document = BuilderLayout.read(SAMPLE, "composition", 128, SIZES).document();

        assertEquals(new GridSize(1, 2), document.grid());
        assertEquals(128, document.width());
        assertEquals(256, document.height());
    }

    @Test
    @DisplayName("at map resolution the geometry is untouched")
    void geometryIsExactAtMapResolution() throws IOException {
        // 128 pixels per frame is the builder's own coordinate space, so nothing
        // should move at all. If this drifts, everything below is measuring the wrong
        // thing against the wrong baseline.
        Document document = BuilderLayout.read(SAMPLE, "composition", 128, SIZES).document();
        Layer.Image first = (Layer.Image) document.layers().getFirst();

        assertEquals(0, first.bounds().x());
        assertEquals(119, first.bounds().y(), "118.5 rounds to 119");
        assertEquals(8, first.bounds().width(), "64 at scale 0.125");
        assertEquals(4, first.bounds().height(), "32 at scale 0.125");
    }

    @Test
    @DisplayName("a higher export resolution scales position and size together")
    void geometryScalesWithResolution() throws IOException {
        // This is the whole point of the conversion. The builder exported by
        // multiplying everything by pixelsPerFrame/128, so reopening at 256 and
        // publishing again has to land on the same pixels it did before.
        Document document = BuilderLayout.read(SAMPLE, "composition", 256, SIZES).document();
        Layer.Image second = (Layer.Image) document.layers().get(1);

        assertEquals(32, second.bounds().x(), "16 doubled");
        assertEquals(8, second.bounds().y(), "4 doubled");
        assertEquals(64, second.bounds().width(), "64 at scale 0.5, doubled");
        assertEquals(32, second.bounds().height(), "32 at scale 0.5, doubled");
        assertEquals(512, document.height());
    }

    @Test
    @DisplayName("order is kept, because it is what sits on top")
    void orderIsPreserved() throws IOException {
        Document document = BuilderLayout.read(SAMPLE, "composition", 128, SIZES).document();

        assertEquals(2, document.layers().size());
        assertEquals("a", document.layers().getFirst().name());
        assertEquals("b", document.layers().get(1).name());
    }

    @Test
    @DisplayName("stretch is the only fit that cannot change what was drawn")
    void fitIsStretch() throws IOException {
        Document document = BuilderLayout.read(SAMPLE, "composition", 128, SIZES).document();
        Layer.Image first = (Layer.Image) document.layers().getFirst();

        // The bounds are already exactly the scaled source. Contain would letterbox a
        // rounding difference into a visible shift.
        assertEquals(Layer.Fit.STRETCH, first.fit());
        assertEquals("signs/a.png", first.repoPath());
    }

    @Test
    @DisplayName("one deleted image does not cost the whole composition")
    void missingImagesAreReportedNotFatal() throws IOException {
        String json = """
                {
                  "columns": 1, "rows": 1,
                  "items": [
                    { "repoPath": "missing/gone.png", "x": 0, "y": 0, "scale": 1 },
                    { "repoPath": "signs/a.png", "x": 0, "y": 0, "scale": 1 }
                  ]
                }
                """;

        BuilderLayout.Result result = BuilderLayout.read(json, "composition", 128, SIZES);

        assertEquals(1, result.document().layers().size(), "the surviving image should still be there");
        assertEquals(List.of("missing/gone.png"), result.missing());
    }

    @Test
    @DisplayName("a layout with nothing in it is not an error")
    void emptyLayoutIsFine() throws IOException {
        BuilderLayout.Result result = BuilderLayout.read(
                "{ \"columns\": 2, \"rows\": 1, \"items\": [] }", "empty", 128, SIZES);

        assertTrue(result.document().layers().isEmpty());
        assertEquals(new GridSize(2, 1), result.document().grid());
    }

    @Test
    @DisplayName("missing fields fall back rather than throwing")
    void missingFieldsFallBack() throws IOException {
        // These files were written by a tool that has since been deleted, so nothing
        // will ever fix one up. Being lenient is the only option that keeps them open.
        BuilderLayout.Result result = BuilderLayout.read(
                "{ \"items\": [ { \"repoPath\": \"signs/a.png\" } ] }", "sparse", 128, SIZES);
        Layer.Image only = (Layer.Image) result.document().layers().getFirst();

        assertEquals(new GridSize(1, 1), result.document().grid());
        assertEquals(0, only.bounds().x());
        assertEquals(64, only.bounds().width(), "no scale means full size");
    }

    @Test
    @DisplayName("an item with no path is skipped rather than becoming a broken layer")
    void pathlessItemsAreSkipped() throws IOException {
        BuilderLayout.Result result = BuilderLayout.read(
                "{ \"items\": [ { \"x\": 1, \"y\": 2 } ] }", "sparse", 128, SIZES);

        assertTrue(result.document().layers().isEmpty());
        assertTrue(result.missing().isEmpty(), "there was nothing to miss");
    }

    @Test
    @DisplayName("a zero scale still produces something you can see and fix")
    void degenerateSizesStayUsable() throws IOException {
        // A layer of zero width cannot be clicked, so it could never be repaired.
        BuilderLayout.Result result = BuilderLayout.read(
                "{ \"items\": [ { \"repoPath\": \"signs/a.png\", \"scale\": 0 } ] }", "tiny", 128, SIZES);
        Layer.Image only = (Layer.Image) result.document().layers().getFirst();

        assertTrue(only.bounds().width() >= 1);
        assertTrue(only.bounds().height() >= 1);
    }

    @Test
    @DisplayName("a layout is told apart from a document by shape")
    void layoutsAreDistinguishableFromDocuments() {
        assertTrue(BuilderLayout.looksLikeLayout(SAMPLE));
        assertFalse(BuilderLayout.looksLikeLayout(
                DocumentJson.write(Document.blank("d", new GridSize(1, 1), 128))));
        assertFalse(BuilderLayout.looksLikeLayout("not json at all"));
    }
}
