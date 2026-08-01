package dev.kierandrewett.mcmarkings.js;

import java.util.List;

/**
 * One input a generator asks for. The form UI is built entirely from these, so
 * a generator can add a field without any Java change.
 */
public record ParamDef(
        String key,
        String label,
        ParamType type,
        List<String> options,
        String defaultValue,
        String help) {

    public enum ParamType {
        /** Single-line string. */
        TEXT,
        /** Multi-line string; each line is one line of sign text. */
        LINES,
        /** One of {@link #options}. */
        SELECT,
        NUMBER,
        BOOLEAN,
        /** Hex colour, "#RRGGBB". */
        COLOUR,
        /** A repo-relative image path, picked from the browser. */
        IMAGE
    }
}
