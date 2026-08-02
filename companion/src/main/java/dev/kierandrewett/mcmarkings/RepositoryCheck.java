package dev.kierandrewett.mcmarkings;

import dev.kierandrewett.mcmarkings.repo.GitFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * What a folder looks like before it is added as a repository.
 *
 * <p>Exists so the picker can tell someone what they are about to add, and why it
 * might not do what they expect, instead of accepting anything and failing later
 * from inside a screen that cannot explain itself.
 *
 * <p>Deliberately permissive. Only a folder that does not exist is refused
 * outright: a repository with no images yet is perfectly reasonable if you intend
 * to generate into it, and one without a git remote is fine until you try to
 * place a sign on a server.
 */
public record RepositoryCheck(
        boolean exists,
        boolean readable,
        boolean isGitRepository,
        boolean hasRemote,
        String remoteSlug,
        int imageCount,
        boolean hasGenerators,
        List<String> notes) {

    /** True when the folder can be added at all. */
    public boolean usable() {
        return exists && readable;
    }

    /** True when placing signs on a server will actually work. */
    public boolean readyToPlace() {
        return usable() && isGitRepository && hasRemote && imageCount > 0;
    }

    public static RepositoryCheck inspect(Path directory) {
        List<String> notes = new ArrayList<>();

        if (directory == null || !Files.exists(directory)) {
            notes.add("That folder does not exist.");
            return new RepositoryCheck(false, false, false, false, "", 0, false, List.copyOf(notes));
        }
        if (!Files.isDirectory(directory)) {
            notes.add("That is a file, not a folder.");
            return new RepositoryCheck(true, false, false, false, "", 0, false, List.copyOf(notes));
        }
        if (!Files.isReadable(directory)) {
            notes.add("That folder cannot be read. If Minecraft is sandboxed, it may need permission to see it.");
            return new RepositoryCheck(true, false, false, false, "", 0, false, List.copyOf(notes));
        }

        Optional<GitFiles> git = GitFiles.at(directory);
        String slug = git.flatMap(GitFiles::remoteSlug).orElse("");
        boolean isRepository = git.isPresent();
        boolean hasRemote = !slug.isBlank();

        int images = countImages(directory);
        boolean generators = Files.isDirectory(directory.resolve("generators"));

        if (!isRepository) {
            notes.add("Not a git repository. You can still browse images, but placing them needs a "
                    + "repository with a GitHub remote, because the server fetches them over HTTP.");
        } else if (!hasRemote) {
            notes.add("No origin remote. Images cannot be placed until this repository has one on GitHub.");
        }
        if (images == 0) {
            notes.add("No PNGs found yet. That is fine if you plan to generate signs into it.");
        }
        if (!generators) {
            notes.add("No generators folder, so the generator screen will be empty for this repository.");
        }

        return new RepositoryCheck(true, true, isRepository, hasRemote, slug, images, generators,
                List.copyOf(notes));
    }

    /**
     * Counts PNGs, stopping early. This runs while someone is clicking through a
     * folder list, so it has to stay responsive on a large tree rather than walk
     * every file to produce an exact number nobody reads.
     */
    private static int countImages(Path directory) {
        try (Stream<Path> walk = Files.walk(directory, 4)) {
            return (int) walk
                    .filter(path -> path.toString().toLowerCase().endsWith(".png"))
                    .limit(500)
                    .count();
        } catch (IOException | RuntimeException exception) {
            return 0;
        }
    }
}
