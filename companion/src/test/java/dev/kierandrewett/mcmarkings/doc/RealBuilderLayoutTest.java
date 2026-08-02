package dev.kierandrewett.mcmarkings.doc;

import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.repo.RepoScanner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The importer against the one real builder layout in this repository.
 *
 * <p>The importer exists because deleting the builder would otherwise have stranded
 * this file, and every test of it so far has been written against JSON the test made
 * up. That proves the arithmetic and says nothing about whether it opens the thing it
 * was written to save.
 *
 * <p>Skipped when the repository is not beside the mod.
 */
class RealBuilderLayoutTest {

    private static Path root;
    private static Path layout;

    @BeforeAll
    static void findLayout() {
        root = Path.of("..").toAbsolutePath().normalize();
        layout = root.resolve("generated/composition.layout.json");
        Assumptions.assumeTrue(Files.isRegularFile(layout),
                "generated/composition.layout.json is not here, skipping");
    }

    private static BuilderLayout.Result converted(int pixelsPerFrame) throws IOException {
        return BuilderLayout.read(Files.readString(layout, StandardCharsets.UTF_8),
                "composition", pixelsPerFrame,
                repoPath -> {
                    int[] size = RepoScanner.readDimensions(root.resolve(repoPath));
                    return size == null ? null : new BuilderLayout.Size(size[0], size[1]);
                });
    }

    @Test
    void opensWithEverythingStillInIt() throws IOException {
        BuilderLayout.Result result = converted(GridSize.MAP_PIXELS);

        assertEquals(List.of(), result.missing(),
                "an image this composition refers to is no longer in the repository");
        assertEquals(3, result.document().layers().size(),
                "the file has three items and the document should have three layers");
        assertEquals(new GridSize(1, 2), result.document().grid());
    }

    @Test
    void everyLayerHasARealSize() throws IOException {
        // The scale in the file multiplies the source image, so a layer with no size
        // means the resolver never found the PNG and the sign would open empty.
        BuilderLayout.Result result = converted(GridSize.MAP_PIXELS);

        for (Layer layer : result.document().layers()) {
            assertTrue(layer.bounds().width() > 1 && layer.bounds().height() > 1,
                    layer.name() + " came back " + layer.bounds().width()
                            + " by " + layer.bounds().height());
        }
    }

    @Test
    void exportResolutionScalesTheWholeComposition() throws IOException {
        // The property the conversion turns on: at twice the frame resolution every
        // coordinate and size doubles, so republishing lands on the same pixels the
        // builder would have produced rather than moving everything.
        Document small = converted(GridSize.MAP_PIXELS).document();
        Document large = converted(GridSize.MAP_PIXELS * 2).document();

        assertEquals(small.width() * 2, large.width());
        for (int index = 0; index < small.layers().size(); index++) {
            Layer.Bounds before = small.layers().get(index).bounds();
            Layer.Bounds after = large.layers().get(index).bounds();

            assertEquals(before.width() * 2, after.width(), 1, "layer " + index + " width");
            assertEquals(before.x() * 2, after.x(), 1, "layer " + index + " x");
        }
    }

    @Test
    void itIsRecognisedAsALayoutRatherThanADocument() throws IOException {
        // The routing decision that stopped an editor-published document being read as
        // a builder layout and opening blank. This file is the other side of it.
        assertTrue(BuilderLayout.looksLikeLayout(Files.readString(layout, StandardCharsets.UTF_8)));
    }
}
