package dev.kierandrewett.mcmarkings.registry;

import dev.kierandrewett.mcmarkings.core.GridSize;
import dev.kierandrewett.mcmarkings.core.MapEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One image placed in several places.
 *
 * <p>The browser named every map after its image, and ImageFrame keys a map by name,
 * so the same sign could only ever be in the world once. With a name field it can be
 * at as many junctions as you like, which means everything that looks an image up by
 * its path has to answer with all of them rather than the first.
 *
 * <p>It already did. The registry keys by name and returns a list by path, and both
 * callers stream the whole thing: the browser lists every name a sign is under, and a
 * pull refreshes every placement of a changed image rather than one. This pins that,
 * because until now nothing could produce a second entry and a lookup returning only
 * the first would have looked correct.
 */
class SeveralPlacementsTest {

    private static MapEntry at(String name, String path) {
        return new MapEntry(name, "repo-1", path, new GridSize(1, 1), "abc123", 1L);
    }

    @Test
    @DisplayName("one image can be placed under several names")
    void severalNamesForOneImage(@TempDir Path directory) {
        JsonMapRegistry registry = new JsonMapRegistry(directory.resolve("registry.json"));

        registry.put(at("no_entry", "signs/no_entry.png"));
        registry.put(at("no_entry_high_street", "signs/no_entry.png"));
        registry.put(at("no_entry_mill_lane", "signs/no_entry.png"));

        List<MapEntry> placed = registry.byRepoPath("signs/no_entry.png");
        assertEquals(3, placed.size(), "placing the same sign again replaced the first one");
        assertTrue(placed.stream().map(MapEntry::imageFrameName).toList()
                .containsAll(List.of("no_entry", "no_entry_high_street", "no_entry_mill_lane")));
    }

    /**
     * And that the same name still replaces, since that is what refreshing one is.
     */
    @Test
    @DisplayName("the same name is still one map")
    void oneNameIsOneMap(@TempDir Path directory) {
        JsonMapRegistry registry = new JsonMapRegistry(directory.resolve("registry.json"));

        registry.put(at("no_entry", "signs/no_entry.png"));
        registry.put(at("no_entry", "signs/no_entry.png"));

        assertEquals(1, registry.byRepoPath("signs/no_entry.png").size());
    }

    /**
     * A pull refreshes what it finds by path, so all of them have to be there or the
     * ones it misses keep showing the old picture with nothing saying why.
     */
    @Test
    @DisplayName("every placement of a changed image is found")
    void allPlacementsAreFoundByPath(@TempDir Path directory) {
        JsonMapRegistry registry = new JsonMapRegistry(directory.resolve("registry.json"));

        registry.put(at("first", "signs/give_way.png"));
        registry.put(at("second", "signs/give_way.png"));
        registry.put(at("elsewhere", "signs/stop.png"));

        assertEquals(2, registry.byRepoPath("signs/give_way.png").size());
        assertEquals(1, registry.byRepoPath("signs/stop.png").size());
    }
}
