package dev.kierandrewett.mcmarkings.registry;

import dev.kierandrewett.mcmarkings.core.MapEntry;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Client-side record of maps created on the server, persisted as JSON in the
 * mod's config directory.
 *
 * <p>This is what makes "pull, then refresh whatever changed" possible: the repo
 * knows which PNGs moved, and this knows which ImageFrame maps point at them.
 */
public interface MapRegistry {

    Optional<MapEntry> byName(String imageFrameName);

    /** Every map created from a given repo path; a PNG can back several maps. */
    List<MapEntry> byRepoPath(String repoPath);

    List<MapEntry> all();

    void put(MapEntry entry);

    void remove(String imageFrameName);

    void load() throws IOException;

    void save() throws IOException;
}
