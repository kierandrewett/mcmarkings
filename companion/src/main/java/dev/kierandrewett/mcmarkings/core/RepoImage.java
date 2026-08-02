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
        String category,

        /**
         * Where the repository says this file came from, or blank.
         *
         * <p>The other half of the licence. Knowing an image is OGL or CC0 tells you
         * the terms and not who to credit, and every entry in both sets here records
         * the page it was taken from. Not searched: a URL is not something anyone
         * types to find a picture, and indexing fourteen hundred of them would put
         * "commons" and "wikimedia" in every single search key.
         */
        String source) {

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

    /**
     * The short label for a grid cell, where there is room for a few words.
     *
     * <p>Not {@link #displayName()}, which prefers the description. Measured against
     * this repository: 84% of descriptions are too long for a cell, and what survives
     * the cut is often the part every neighbouring image shares. Four hundred and
     * sixty four of them begin "UK traffic sign", and whole runs of the ISO set read
     * "Fire safety sign F...", "Prohibition sign P...", so a screenful of cells
     * carried the same caption and none of it said which image was which.
     *
     * <p>The file name is the opposite shape and always has been: short, written to
     * distinguish one image from its neighbours, and unique here for all but one of
     * 1445 files. "risk of stumbling" fits whole where "Warning sign W007: Risk of
     * stumbling" does not.
     *
     * <p>Nothing here knows about road signs, and this does not either. Prose is for
     * the tooltip and the detail pane, which have room for it; a caption gets the
     * name, whatever the repository happens to hold.
     */
    public String shortName() {
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
