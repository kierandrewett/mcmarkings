package dev.kierandrewett.mcmarkings.config;

import java.nio.file.Path;
import java.util.Locale;

/**
 * One image repository the mod knows about.
 *
 * <p>The id is derived from the absolute path rather than generated, so adding the
 * same folder twice is idempotent and a config file edited by hand still lines up
 * with what the GUI wrote.
 */
public record RepositoryEntry(
        String id,
        String name,
        String path,
        String branch,
        String slugOverride) {

    public static RepositoryEntry of(Path directory, String name, String branch) {
        Path absolute = directory.toAbsolutePath().normalize();
        return new RepositoryEntry(idFor(absolute), name, absolute.toString(), branch, "");
    }

    public static RepositoryEntry of(Path directory) {
        Path absolute = directory.toAbsolutePath().normalize();
        Path fileName = absolute.getFileName();
        return of(absolute, fileName == null ? absolute.toString() : fileName.toString(), "main");
    }

    /** Stable, readable, and unique per folder. */
    public static String idFor(Path directory) {
        String cleaned = directory.toAbsolutePath().normalize().toString()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return cleaned.isBlank() ? "repository" : cleaned;
    }

    public Path root() {
        return Path.of(path);
    }

    public String displayName() {
        return name == null || name.isBlank() ? root().getFileName().toString() : name;
    }

    public RepositoryEntry withName(String newName) {
        return new RepositoryEntry(id, newName, path, branch, slugOverride);
    }

    public RepositoryEntry withBranch(String newBranch) {
        return new RepositoryEntry(id, name, path, newBranch, slugOverride);
    }
}
