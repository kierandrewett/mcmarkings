package dev.kierandrewett.mcmarkings.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
        String slugOverride,
        String rawUrlTemplate) {

    /**
     * Compact constructor keeping older config files loadable.
     *
     * <p>Gson leaves absent fields null, and a null template has to mean "work it
     * out from the remote" rather than producing a null in a URL.
     */
    public RepositoryEntry {
        rawUrlTemplate = rawUrlTemplate == null ? "" : rawUrlTemplate;
        slugOverride = slugOverride == null ? "" : slugOverride;
    }

    public static RepositoryEntry of(Path directory, String name, String branch) {
        Path absolute = directory.toAbsolutePath().normalize();
        return new RepositoryEntry(idFor(absolute), name, absolute.toString(), branch, "", "");
    }

    public static RepositoryEntry of(Path directory) {
        Path absolute = directory.toAbsolutePath().normalize();
        Path fileName = absolute.getFileName();
        return of(absolute, fileName == null ? absolute.toString() : fileName.toString(), "main");
    }

    /**
     * Stable, readable, and genuinely unique per folder.
     *
     * <p>The readable part alone is not enough: collapsing every run of punctuation
     * to a dash maps {@code /srv/signs/uk} and {@code /srv/signs-uk} onto the same
     * string, and two folders sharing an id would merge their map lists. A short
     * digest of the full path is appended so distinct folders always differ, while
     * the leading text keeps the config file legible.
     */
    public static String idFor(Path directory) {
        String absolute = directory.toAbsolutePath().normalize().toString();

        String readable = absolute.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (readable.isBlank()) {
            readable = "repository";
        }
        if (readable.length() > 40) {
            // Keep the tail, since the last segments are what identify a folder.
            readable = readable.substring(readable.length() - 40).replaceAll("^-+", "");
        }

        return readable + "-" + digestOf(absolute);
    }

    private static String digestOf(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(8);
            for (int index = 0; index < 4; index++) {
                hex.append(String.format(Locale.ROOT, "%02x", hash[index]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 is mandated by the platform, so this cannot happen; fall back
            // rather than making every caller handle an impossible failure.
            return Integer.toHexString(value.hashCode());
        }
    }

    public Path root() {
        return Path.of(path);
    }

    public String displayName() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        // A filesystem root has no file name, so fall back to the path itself.
        Path fileName = root().getFileName();
        return fileName == null ? path : fileName.toString();
    }

    public RepositoryEntry withName(String newName) {
        return new RepositoryEntry(id, newName, path, branch, slugOverride, rawUrlTemplate);
    }

    public RepositoryEntry withBranch(String newBranch) {
        return new RepositoryEntry(id, name, path, newBranch, slugOverride, rawUrlTemplate);
    }
}
