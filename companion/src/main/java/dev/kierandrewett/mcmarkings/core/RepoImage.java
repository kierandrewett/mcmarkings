package dev.kierandrewett.mcmarkings.core;

import java.util.Locale;

/**
 * One PNG in the backing repository.
 *
 * <p>{@code path} is always repo-relative with forward slashes, because it is
 * also the path component of the raw file URL handed to ImageFrame.
 * {@code description} and {@code reference} come from whatever JSON sidecar in the
 * repository describes this image, and are null when nothing does.
 */
public record RepoImage(
        String path,
        String name,
        int width,
        int height,
        String description,
        String reference,

        /**
         * The licence the repository records for this file, or blank.
         *
         * <p>Read because a repository of borrowed images has an answer to "may I use
         * this" and the mod was throwing it away. Both sets here carry one for every
         * entry, OGL for the road signs and CC0 for the safety ones, and someone
         * putting an image on a public server has a reason to know which.
         */
        String licence,

        /**
         * What the repository files this image under, or blank.
         *
         * <p>Both sets here carry one and they are the obvious way to narrow eleven
         * hundred signs: warning, regulatory, prohibition, fire safety. Searchable
         * rather than a dropdown, because a repository that has no categories should
         * not grow a control that does nothing, and typing "warning" is what someone
         * does anyway.
         */
        String category) {

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
        if (category != null) {
            // Underscores read as spaces, as the name already is: the repository writes
            // safe_condition and nobody types that.
            builder.append(' ').append(category.replace('_', ' '));
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }
}
