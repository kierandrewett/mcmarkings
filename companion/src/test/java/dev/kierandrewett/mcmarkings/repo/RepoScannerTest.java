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
        writePng(root.resolve("signs/give_way.png"), 200, 200);
        writePng(root.resolve("build/output.png"), 16, 16);
        writePng(root.resolve("node_modules/pkg/logo.png"), 16, 16);
        writePng(root.resolve("target/classes/logo.png"), 16, 16);
        writePng(root.resolve("out/logo.png"), 16, 16);
        writePng(root.resolve("dist/logo.png"), 16, 16);
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
    void ignoredDirectoriesComeFromTheCallerNotAFixedList(@TempDir Path root) throws IOException {
        writePng(root.resolve("keep.png"), 10, 10);
        writePng(root.resolve("vendor/skip.png"), 10, 10);
        // Nothing about node_modules is baked in, so a caller that does not name it
        // gets its images. That is the whole point of taking the list from config.
        writePng(root.resolve("node_modules/pkg/keep_too.png"), 10, 10);

        RepoScanner scanner = new RepoScanner(root, List.of("vendor"));
        scanner.rescan();

        assertEquals(
                List.of("keep.png", "node_modules/pkg/keep_too.png"),
                scanner.images().stream().map(RepoImage::path).toList());
    }

    @Test
    void ignoredDirectoriesAreMatchedCaseInsensitively(@TempDir Path root) throws IOException {
        writePng(root.resolve("keep.png"), 10, 10);
        writePng(root.resolve("Vendor/skip.png"), 10, 10);

        RepoScanner scanner = new RepoScanner(root, List.of("VENDOR"));
        scanner.rescan();

        assertEquals(List.of("keep.png"), scanner.images().stream().map(RepoImage::path).toList());
    }

    @Test
    void dottedDirectoriesAreSkippedWhateverTheCallerAsksFor(@TempDir Path root) throws IOException {
        writePng(root.resolve("keep.png"), 10, 10);
        writePng(root.resolve(".git/blob.png"), 10, 10);
        writePng(root.resolve(".cache/thumb.png"), 10, 10);

        RepoScanner scanner = new RepoScanner(root, List.of());
        scanner.rescan();

        assertEquals(List.of("keep.png"), scanner.images().stream().map(RepoImage::path).toList());
    }

    @Test
    void aNullIgnoreListIsTreatedAsEmpty(@TempDir Path root) throws IOException {
        writePng(root.resolve("build/output.png"), 10, 10);

        RepoScanner scanner = new RepoScanner(root, null);
        scanner.rescan();

        assertEquals(List.of("build/output.png"), scanner.images().stream().map(RepoImage::path).toList());
    }

    @Test
    void scanMergesASidecarSittingWithItsImages(@TempDir Path root) throws IOException {
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
    void scanReadsASidecarWhoseNamesAreNothingLikeThisRepository(@TempDir Path root) throws IOException {
        writePng(root.resolve("artwork/harbour_noir.png"), 800, 600);
        Files.writeString(root.resolve("artwork/catalogue.json"), """
                {
                  "generated": "2026-01-01",
                  "items": [
                    {
                      "filename": "harbour_noir.png",
                      "title": "Harbour at night",
                      "ref": "ART-014"
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);

        RepoScanner scanner = new RepoScanner(root);
        scanner.rescan();

        RepoImage image = scanner.byPath("artwork/harbour_noir.png").orElseThrow();
        assertEquals("Harbour at night", image.description());
        assertEquals("ART-014", image.reference());
        assertEquals(1, scanner.search("harbour at night", 10).size());
    }

    @Test
    void scanReadsAManifestAtTheRepositoryRoot(@TempDir Path root) throws IOException {
        writePng(root.resolve("tiles/grass.png"), 64, 64);
        writePng(root.resolve("tiles/stone.png"), 64, 64);
        Files.writeString(root.resolve("manifest.json"), """
                {
                  "tiles": [
                    {"path": "tiles/grass.png", "summary": "Grass tile", "id": "T-1"},
                    {"path": "tiles/stone.png", "summary": "Stone tile", "id": "T-2"}
                  ]
                }
                """, StandardCharsets.UTF_8);

        RepoScanner scanner = new RepoScanner(root);
        scanner.rescan();

        assertEquals("Grass tile", scanner.byPath("tiles/grass.png").orElseThrow().description());
        assertEquals("T-2", scanner.byPath("tiles/stone.png").orElseThrow().reference());
    }

    @Test
    void aSidecarBeatsARootManifestForTheSameImage(@TempDir Path root) throws IOException {
        writePng(root.resolve("tiles/grass.png"), 64, 64);
        Files.writeString(root.resolve("manifest.json"), """
                {"tiles": [{"path": "tiles/grass.png", "description": "From the manifest"}]}
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("tiles/tiles.json"), """
                {"tiles": [{"file": "grass.png", "description": "From the sidecar"}]}
                """, StandardCharsets.UTF_8);

        RepoScanner scanner = new RepoScanner(root);
        scanner.rescan();

        assertEquals("From the sidecar", scanner.byPath("tiles/grass.png").orElseThrow().description());
    }

    @Test
    void scanIgnoresJsonThatIsNotMetadata(@TempDir Path root) throws IOException {
        writePng(root.resolve("a.png"), 10, 10);
        Files.writeString(root.resolve("package.json"), """
                {
                  "name": "some-project",
                  "version": "1.0.0",
                  "files": ["dist"],
                  "keywords": ["images", "png"],
                  "scripts": {"build": "node build.js"},
                  "dependencies": {"left-pad": "^1.3.0"}
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("tsconfig.json"), """
                {"references": [{"path": "./sub"}], "compilerOptions": {"strict": true}}
                """, StandardCharsets.UTF_8);

        RepoScanner scanner = new RepoScanner(root);
        scanner.rescan();

        RepoImage image = scanner.byPath("a.png").orElseThrow();
        assertNull(image.description());
        assertNull(image.reference());
    }

    @Test
    void anEntryForAMissingFileDoesNotInventAnImage(@TempDir Path root) throws IOException {
        writePng(root.resolve("real.png"), 10, 10);
        Files.writeString(root.resolve("data.json"), """
                {"images": [
                  {"file": "real.png", "description": "Really here"},
                  {"file": "ghost.png", "description": "Never existed"}
                ]}
                """, StandardCharsets.UTF_8);

        RepoScanner scanner = new RepoScanner(root);
        scanner.rescan();

        assertEquals(List.of("real.png"), scanner.images().stream().map(RepoImage::path).toList());
        assertEquals("Really here", scanner.byPath("real.png").orElseThrow().description());
        assertTrue(scanner.byPath("ghost.png").isEmpty());
    }

    @Test
    void aMalformedJsonDoesNotFailTheScan(@TempDir Path root) throws IOException {
        writePng(root.resolve("a.png"), 10, 10);
        writePng(root.resolve("b.png"), 10, 10);
        Files.writeString(root.resolve("broken.json"), "{ \"images\": [ { \"file\": ", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("good.json"), """
                {"images": [{"file": "b.png", "description": "Still read"}]}
                """, StandardCharsets.UTF_8);

        RepoScanner scanner = new RepoScanner(root);
        scanner.rescan();

        assertEquals(List.of("a.png", "b.png"), scanner.images().stream().map(RepoImage::path).toList());
        assertEquals("Still read", scanner.byPath("b.png").orElseThrow().description());
    }

    @Test
    void aRepositoryWithNoMetadataStillScansAndSearchesByFilename(@TempDir Path root) throws IOException {
        writePng(root.resolve("zebra_crossing.png"), 64, 32);
        writePng(root.resolve("bus_stop.png"), 64, 32);
        writePng(root.resolve("nested/give_way.png"), 64, 32);

        RepoScanner scanner = new RepoScanner(root);
        scanner.rescan();

        assertEquals(3, scanner.images().size());
        RepoImage image = scanner.byPath("zebra_crossing.png").orElseThrow();
        assertNull(image.description());
        assertNull(image.reference());
        assertEquals("zebra crossing", image.displayName());

        assertEquals(
                List.of("zebra_crossing.png"),
                scanner.search("zebra crossing", 10).stream().map(RepoImage::path).toList());
        assertEquals(
                List.of("nested/give_way.png"),
                scanner.search("give_way", 10).stream().map(RepoImage::path).toList());
    }

    @Test
    void referenceIsReadFromWhicheverAliasTheRepositoryUses(@TempDir Path root) throws IOException {
        writePng(root.resolve("arc_flash_hazard.png"), 10, 10);
        writePng(root.resolve("wait_here.png"), 10, 10);
        writePng(root.resolve("numbered.png"), 10, 10);
        Files.writeString(root.resolve("data.json"), """
                {"entries": [
                  {"file": "arc_flash_hazard.png", "code": "W042", "description": "Arc flash hazard"},
                  {"file": "wait_here.png", "diagram": "7011.1-V1", "description": "Wait here"},
                  {"file": "numbered.png", "code": 42, "description": "Numbered"}
                ]}
                """, StandardCharsets.UTF_8);

        RepoScanner scanner = new RepoScanner(root);
        scanner.rescan();

        assertEquals("W042", scanner.byPath("arc_flash_hazard.png").orElseThrow().reference());
        assertEquals("7011.1-V1", scanner.byPath("wait_here.png").orElseThrow().reference());
        assertEquals("42", scanner.byPath("numbered.png").orElseThrow().reference());
    }

    @Test
    void aSidecarCannotDescribeAFileOutsideTheRepository(@TempDir Path root) {
        Map<String, RepoScanner.Metadata> target = new HashMap<>();

        RepoScanner.readSidecar(target, root, root, """
                {"images": [{"file": "../escaped.png", "description": "Somewhere else"}]}
                """);

        assertTrue(target.isEmpty());
    }

    @Test
    void metadataReaderIgnoresRubbish(@TempDir Path root) {
        Map<String, RepoScanner.Metadata> target = new HashMap<>();

        RepoScanner.readSidecar(target, root, root, "[1, 2, 3]");
        RepoScanner.readSidecar(target, root, root, "\"just a string\"");
        RepoScanner.readSidecar(target, root, root, "{\"signs\": \"not an array\"}");
        RepoScanner.readSidecar(target, root, root, "{\"signs\": [{\"description\": \"no file field\"}]}");
        RepoScanner.readSidecar(target, root, root, "{\"signs\": [{\"file\": \"a.png\"}]}");
        RepoScanner.readSidecar(target, root, root, "");

        assertTrue(target.isEmpty());
    }

    @Test
    void metadataReaderSkipsPastAnArrayThatYieldsNothing(@TempDir Path root) {
        Map<String, RepoScanner.Metadata> target = new HashMap<>();

        RepoScanner.readSidecar(target, root, root, """
                {
                  "files": ["a.txt", "b.txt"],
                  "authors": [{"name": "Someone"}],
                  "images": [{"file": "a.png", "description": "The real entries"}]
                }
                """);

        assertEquals("The real entries", target.get("a.png").description());
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
