package dev.kierandrewett.mcmarkings.js;

import java.util.List;

/** A generator script's self-declared identity and parameter list. */
public record GeneratorDef(
        String id,
        String title,
        String description,
        List<ParamDef> params,

        /**
         * Whether the script can describe its output as layers.
         *
         * <p>Here rather than discovered by running it, because the interface has to
         * know before offering the choice. A button that might turn out to do nothing
         * is worse than one that is plainly not available.
         */
        boolean editable) {

    /** For scripts and tests that only draw. */
    public GeneratorDef(String id, String title, String description, List<ParamDef> params) {
        this(id, title, description, params, false);
    }
}
