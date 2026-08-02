package dev.kierandrewett.mcmarkings.doc;

import dev.kierandrewett.mcmarkings.render.FontRegistry;
import dev.kierandrewett.mcmarkings.render.ImageComposer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The templates this repository ships open, and are worth opening.
 *
 * <p>The editor's empty state offers "Open a saved template" above a comment saying
 * that starting from something is the difference between a tool you open and a tool
 * you use. There were no templates. Clicking it gave an empty list, which is the
 * exact opposite of what that button is for.
 *
 * <p>Three now, built through the same store the editor saves through so they cannot
 * be a shape it would not write, and rendered and looked at before committing. What
 * is checked here is that they still load whole and still draw something: a template
 * that opens short or renders blank is worse than no template, because it looks like
 * the editor is broken rather than like the file is.
 */
class StarterTemplatesTest {

    private static TemplateStore store;

    @BeforeAll
    static void find() {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(root.resolve("templates")),
                "run from a checkout with the templates present");
        store = new TemplateStore(root);
    }

    @Test
    @DisplayName("every shipped template opens whole")
    void theyAllLoad() throws IOException {
        List<TemplateStore.Entry> entries = store.list();
        assertTrue(entries.size() >= 3, "expected the starter templates, found " + entries.size());

        for (TemplateStore.Entry entry : entries) {
            DocumentJson.Result result;
            try {
                result = store.readWithReport(entry.file());
            } catch (DocumentJson.FormatException malformed) {
                throw new AssertionError(entry.name() + " will not parse", malformed);
            }
            assertEquals(List.of(), result.warnings(), entry.name() + " did not open whole");
            assertTrue(!result.document().layers().isEmpty(), entry.name() + " has no layers");
        }
    }

    /**
     * Loads an image layer's file the way the mod does.
     *
     * <p>This used to resolve everything to null, which was fine while the only
     * templates here were made of shapes and text, and stopped being fine the moment
     * somebody saved one with a sign in it: the template was perfectly good and the
     * check called it broken, because the check had decided in advance that no
     * template would ever contain an image.
     *
     * <p>Reading them for real is also the more useful test. A saved template holds a
     * repository-relative path, and a path that no longer resolves is exactly the
     * failure worth catching, since what it produces in game is a layer that silently
     * draws nothing.
     */
    private static BufferedImage imageFromRepository(String path) {
        try {
            Path file = Path.of("..").toAbsolutePath().normalize().resolve(path);
            return Files.isRegularFile(file) ? ImageIO.read(file.toFile()) : null;
        } catch (IOException unreadable) {
            return null;
        }
    }

    /**
     * And that they draw. A template of invisible layers loads without complaint and
     * puts an empty canvas in front of somebody who just asked for a starting point.
     */
    @Test
    @DisplayName("every shipped template draws something")
    void theyAllRender() throws IOException {
        FontRegistry fonts = new FontRegistry(List.of(
                System.getProperty("user.home") + "/.local/share/fonts", "/usr/share/fonts"));

        for (TemplateStore.Entry entry : store.list()) {
            Document document = store.load(entry);
            DocumentRenderer renderer = new DocumentRenderer(fonts, new ImageComposer());
            BufferedImage rendered = renderer.render(document, StarterTemplatesTest::imageFromRepository);

            assertEquals(List.of(), renderer.problems(),
                    entry.name() + " reported problems while drawing");
            assertTrue(opaquePixels(rendered) > rendered.getWidth() * rendered.getHeight() / 10,
                    entry.name() + " draws almost nothing, so it opens as a blank canvas");
        }
    }

    /**
     * A starter is only a starter if it is cheap to place. One that wanted a wall of
     * frames would be a demonstration rather than a beginning.
     */
    @Test
    @DisplayName("no starter costs more than a couple of item frames")
    void theyAreCheapToPlace() throws IOException {
        for (TemplateStore.Entry entry : store.list()) {
            Document document = store.load(entry);
            assertTrue(document.grid().frameCount() <= 4,
                    entry.name() + " needs " + document.grid().frameCount() + " frames to place");
        }
    }

    private static int opaquePixels(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                if ((image.getRGB(x, y) >>> 24) > 128) {
                    count += 4;
                }
            }
        }
        return count;
    }
}
