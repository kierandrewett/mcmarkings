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

    /**
     * Repository stand-in for entries written before the registry knew that there
     * could be more than one repository.
     *
     * <p>A marker rather than a guess. An old file records a repo-relative path and
     * nothing else, so quietly attaching those maps to whichever repository happens
     * to be first would be wrong the moment someone adds a second one, and wrong in
     * a way nobody would notice until a refresh hit the other repository's PNG.
     * Marked entries stay visible, keep working through {@link #byRepoPath}, and can
     * be adopted into a real repository once the user says which one they meant.
     */
    String UNKNOWN_REPOSITORY = "unknown-repository";

    Optional<MapEntry> byName(String imageFrameName);

    /**
     * Every map created from a given repo path; a PNG can back several maps.
     *
     * <p>Deliberately not scoped to a repository, so maps carried over from before
     * repositories existed still come back and still get refreshed after a pull.
     */
    List<MapEntry> byRepoPath(String repoPath);

    /** Every map belonging to one repository, including {@link #UNKNOWN_REPOSITORY}. */
    List<MapEntry> byRepository(String repositoryId);

    List<MapEntry> all();

    void put(MapEntry entry);

    void remove(String imageFrameName);

    /**
     * Bumped whenever the contents change, so a cached answer can tell.
     *
     * <p>Anything derived from this registry is worked out once and read every frame,
     * and the trigger that recomputes it is rarely the only thing that changes it.
     * The browser learned that the hard way: it cached which maps come from an image
     * and refreshed on selection, so deleting one from another tab left the answer
     * wrong until you happened to click away and back.
     *
     * <p>A counter rather than listeners, because the only question anyone asks is
     * "has this changed since I looked", and comparing two numbers is cheap enough to
     * do while drawing.
     */
    int generation();

    void load() throws IOException;

    void save() throws IOException;
}
