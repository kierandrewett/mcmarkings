package dev.kierandrewett.mcmarkings.js;

import java.util.List;

/** A generator script's self-declared identity and parameter list. */
public record GeneratorDef(
        String id,
        String title,
        String description,
        List<ParamDef> params) {
}
