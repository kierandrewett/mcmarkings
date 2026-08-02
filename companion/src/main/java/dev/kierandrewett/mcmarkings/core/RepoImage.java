package dev.kierandrewett.mcmarkings.core;

import java.util.Locale;

/**
 * One PNG in the backing repository.
 *
 * <p>{@code path} is always repo-relative with forward slashes, because it is
 * also the path component of the raw.githubusercontent URL handed to ImageFrame.
 * {@code description} and {@code reference} come from signs/signs.json where the
 * image has an entry there, and are null otherwise.
 */
public record RepoImage(
        String path,
        String name,
        int width,
        int height,
        String description,
        String reference) {

    public double aspect() {
        return height == 0 ? 1.0 : (double) width / (double) height;
    }

    /** Human-readable label: the description if present, else the file name unslugged. */
    public String displayName() {
        if (description != null && !description.isBlank()) {
            return description;
        }
        return name.replace('_', ' ');
    }

    /** Lowercase haystack used by the browser search box. */
    public String searchKey() {
        StringBuilder builder = new StringBuilder(path).append(' ').append(name);
        if (description != null) {
            builder.append(' ').append(description);
        }
        if (reference != null) {
            builder.append(' ').append(reference);
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }
}
