package dev.kierandrewett.mcmarkings.repo;

import dev.kierandrewett.mcmarkings.core.RepoImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepoScannerTest {

    @Test
    void parsesWidthAndHeightFromIhdr() {
        byte[] header = pngHeader(1024, 733);

        int[] size = RepoScanner.parseIhdr(header, header.length);

        assertNotNull(size);
        assertEquals(1024, size[0]);
        assertEquals(733, size[1]);
    }

    @Test
    void rejectsHeaderWithWrongSignature() {
        byte[] header = pngHeader(64, 64);
        header[1] = 'X';

        assertNull(RepoScanner.parseIhdr(header, header.length));
    }

    @Test
    void rejectsHeaderWhoseFirstChunkIsNotIhdr() {
        byte[] header = pngHeader(64, 64);
        header[12] = 'I';
        header[13] = 'D';
        header[14] = 'A';
        header[15] = 'T';

        assertNull(RepoScanner.parseIhdr(header, header.length));
    }

    @Test
    void rejectsTruncatedHeader() {
        byte[] header = pngHeader(64, 64);

        assertNull(RepoScanner.parseIhdr(header, 20));
        assertNull(RepoScanner.parseIhdr(null, 24));
    }

    @Test
    void rejectsZeroDimensions() {
        byte[] header = pngHeader(0, 64);

        assertNull(RepoScanner.parseIhdr(header, header.length));
    }

    @Test
    void readsDimensionsFromARealPngWithoutDecoding(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("sample.png");
        writePng(file, 37, 19);

        int[] size = RepoScanner.readDimensions(file);

        assertNotNull(size);
        assertEquals(37, size[0]);
        assertEquals(19, size[1]);
    }

    @Test
    void readDimensionsReturnsNullForANonImage(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("not_really.png");
        Files.writeString(file, "this is not a png at all, not even close");

        assertNull(RepoScanner.readDimensions(file));
    }

    @Test
    void scanFindsPngsAndSkipsIgnoredDirectories(@TempDir Path root) throws IOException {
        writePng(root.resolve("zebra.png"), 100, 50);
        Files.createDirectories(root.resolve("signs"));
        writePng(root.resolve("signs/give_way.png"), 200, 200);
        Files.createDirectories(root.resolve("companion/src"));
        writePng(root.resolve("companion/src/icon.png"), 16, 16);
        Files.createDirectories(root.resolve("build"));
        writePng(root.resolve("build/output.png"), 16, 16);
        Files.createDirectories(root.resolve("node_modules/pkg"));
        writePng(root.resolve("node_modules/pkg/logo.png"), 16, 16);
        Files.createDirectories(root.resolve(".git"));
        writePng(root.resolve(".git/blob.png"), 16, 16);
        Files.writeString(root.resolve("readme.txt"), "ignored");

        RepoScanner scanner = new RepoScanner(root);
        scanner.rescan();

        assertEquals(
                List.of("signs/give_way.png", "zebra.png"),
                scanner.images().stream().map(RepoImage::path).toList());
        assertEquals(200, scanner.byPath("signs/give_way.png").orElseThrow().width());
    }

    @Test
    void scanMergesSignsMetadata(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("signs"));
        writePng(root.resolve("signs/stop_and_give_way.png"), 1024, 733);
        Files.writeString(root.resolve("signs/signs.json"), """
                {
                  "count": 1,
                  "signs": [
                    {
                      "name": "stop_and_give_way",
                      "file": "stop_and_give_way.png",
                      "reference": "601.1",
                      "description": "Stop and give way",
                      "width": 1024,
                      "height": 733
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);

        RepoScanner scanner = new RepoScanner(root);
        scanner.rescan();

        RepoImage image = scanner.byPath("signs/stop_and_give_way.png").orElseThrow();
        assertEquals("Stop and give way", image.description());
        assertEquals("601.1", image.reference());
        assertEquals("Stop and give way", image.displayName());
    }

    @Test
    void scanLeavesMetadataNullWhenTheSidecarHasNoEntry(@TempDir Path root) throws IOException {
        writePng(root.resolve("zebra.png"), 64, 32);

        RepoScanner scanner = new RepoScanner(root);
        scanner.rescan();

        RepoImage image = scanner.byPath("zebra.png").orElseThrow();
        assertNull(image.description());
        assertNull(image.reference());
        assertEquals("zebra", image.displayName());
    }

    @Test
    void metadataReaderAcceptsIsoCodeAsTheDiagram() {
        Map<String, RepoScanner.Metadata> target = new HashMap<>();

        RepoScanner.readMetadataInto(target, "iso", """
                {"signs": [{"file": "arc_flash_hazard.png", "code": "W042", "description": "Arc flash hazard"}]}
                """);

        RepoScanner.Metadata metadata = target.get("iso/arc_flash_hazard.png");
        assertNotNull(metadata);
        assertEquals("W042", metadata.reference());
        assertEquals("Arc flash hazard", metadata.description());
    }

    @Test
    void metadataReaderIgnoresRubbish() {
        Map<String, RepoScanner.Metadata> target = new HashMap<>();

        RepoScanner.readMetadataInto(target, "signs", "[1, 2, 3]");
        RepoScanner.readMetadataInto(target, "signs", "{\"signs\": \"not an array\"}");
        RepoScanner.readMetadataInto(target, "signs", "{\"signs\": [{\"description\": \"no file field\"}]}");

        assertTrue(target.isEmpty());
    }

    @Test
    void rescanRebuildsTheCache(@TempDir Path root) throws IOException {
        writePng(root.resolve("one.png"), 10, 10);
        RepoScanner scanner = new RepoScanner(root);
        scanner.rescan();
        assertEquals(1, scanner.images().size());

        writePng(root.resolve("two.png"), 10, 10);
        assertEquals(1, scanner.images().size(), "cached until rescan");

        scanner.rescan();
        assertEquals(2, scanner.images().size());
    }

    @Test
    void imagesListIsImmutable(@TempDir Path root) throws IOException {
        writePng(root.resolve("one.png"), 10, 10);
        RepoScanner scanner = new RepoScanner(root);
        scanner.rescan();

        assertThrows(UnsupportedOperationException.class, () -> scanner.images().clear());
    }

    @Test
    void rescanRejectsAMissingRoot(@TempDir Path root) {
        RepoScanner scanner = new RepoScanner(root.resolve("nowhere"));

        assertThrows(IOException.class, scanner::rescan);
    }

    @Test
    void searchRanksExactThenPrefixThenSubstring() {
        RepoImage exact = image("signs/give_way.png", "give_way", null, null);
        RepoImage prefix = image("signs/give_way_line.png", "give_way_line", null, null);
        RepoImage substring = image("signs/stop_give_way_ahead.png", "stop_give_way_ahead", null, null);
        RepoImage descriptionOnly = image("a/other.png", "other", "Give way to oncoming traffic", null);

        List<RepoImage> pool = List.of(descriptionOnly, substring, prefix, exact);
        List<RepoImage> results = rank(pool, "give_way");

        assertEquals(
                List.of("signs/give_way.png", "signs/give_way_line.png", "signs/stop_give_way_ahead.png"),
                results.stream().map(RepoImage::path).toList());
    }

    @Test
    void searchIsCaseInsensitive(@TempDir Path root) throws IOException {
        writePng(root.resolve("Give_Way.png"), 10, 10);
        RepoScanner scanner = new RepoScanner(root);
        scanner.rescan();

        assertEquals(1, scanner.search("GIVE_way", 10).size());
        assertEquals(1, scanner.search("  give_WAY  ", 10).size());
    }

    @Test
    void searchRequiresEveryToken() {
        RepoImage image = image("signs/give_way.png", "give_way", "Give way", "602");

        assertTrue(RepoScanner.rank(image, "give way", new String[] { "give", "way" }) >= 0);
        assertEquals(-1, RepoScanner.rank(image, "give zebra", new String[] { "give", "zebra" }));
    }

    @Test
    void searchTreatsUnderscoresInNamesAsSpaces() {
        RepoImage exact = image("signs/give_way.png", "give_way", "Give way", null);
        RepoImage incidental = image("signs/end_of_give_way.png", "end_of_give_way", "End of give way", null);

        String[] tokens = { "give", "way" };
        assertEquals(0, RepoScanner.rank(exact, "give way", tokens));
        assertEquals(2, RepoScanner.rank(incidental, "give way", tokens));
    }

    @Test
    void searchMatchesTheDiagramCodeExactly() {
        RepoImage image = image("signs/wait_here.png", "wait_here", "Wait here", "7011.1-v1");

        assertEquals(0, RepoScanner.rank(image, "7011.1-v1", new String[] { "7011.1-v1" }));
    }

    @Test
    void emptySearchReturnsTheHeadOfTheList(@TempDir Path root) throws IOException {
        writePng(root.resolve("a.png"), 10, 10);
        writePng(root.resolve("b.png"), 10, 10);
        writePng(root.resolve("c.png"), 10, 10);

        RepoScanner scanner = new RepoScanner(root);
        scanner.rescan();

        assertEquals(List.of("a.png", "b.png"), scanner.search("   ", 2).stream().map(RepoImage::path).toList());
        assertEquals(3, scanner.search("", 10).size());
        assertTrue(scanner.search("", 0).isEmpty());
    }

    @Test
    void searchHonoursTheLimit(@TempDir Path root) throws IOException {
        for (int index = 0; index < 5; index++) {
            writePng(root.resolve("sign_" + index + ".png"), 10, 10);
        }

        RepoScanner scanner = new RepoScanner(root);
        scanner.rescan();

        assertEquals(2, scanner.search("sign", 2).size());
    }

    @Test
    void byPathIsEmptyForAnUnknownPath(@TempDir Path root) throws IOException {
        writePng(root.resolve("a.png"), 10, 10);
        RepoScanner scanner = new RepoScanner(root);
        scanner.rescan();

        Optional<RepoImage> missing = scanner.byPath("nope.png");

        assertTrue(missing.isEmpty());
        assertFalse(scanner.byPath("a.png").isEmpty());
    }

    @Test
    void resolveJoinsOntoTheRoot(@TempDir Path root) {
        RepoScanner scanner = new RepoScanner(root);

        assertEquals(root.resolve("signs/a.png").toAbsolutePath().normalize(), scanner.resolve("signs/a.png"));
    }

    /** Mirrors what {@link RepoScanner#search} does, without needing files on disk. */
    private static List<RepoImage> rank(List<RepoImage> pool, String query) {
        String[] tokens = query.split("\\s+");
        return pool.stream()
                .map(image -> Map.entry(image, RepoScanner.rank(image, query, tokens)))
                .filter(entry -> entry.getValue() >= 0)
                .sorted(java.util.Comparator
                        .<Map.Entry<RepoImage, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(entry -> entry.getKey().path()))
                .map(Map.Entry::getKey)
                .toList();
    }

    private static RepoImage image(String path, String name, String description, String reference) {
        return new RepoImage(path, name, 100, 100, description, reference);
    }

    private static byte[] pngHeader(int width, int height) {
        byte[] header = new byte[24];
        byte[] signature = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
        System.arraycopy(signature, 0, header, 0, signature.length);
        writeInt(header, 8, 13);
        header[12] = 'I';
        header[13] = 'H';
        header[14] = 'D';
        header[15] = 'R';
        writeInt(header, 16, width);
        writeInt(header, 20, height);
        return header;
    }

    private static void writeInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    private static void writePng(Path file, int width, int height) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(image, "png", file.toFile());
    }
}
