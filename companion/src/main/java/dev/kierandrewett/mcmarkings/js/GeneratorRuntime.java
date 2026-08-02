package dev.kierandrewett.mcmarkings.js;

import dev.kierandrewett.mcmarkings.doc.Document;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads and runs the sign generator scripts from the repository's
 * {@code generators/} directory.
 *
 * <p>Scripts live in the repo rather than the mod so a new sign type is a commit,
 * not a rebuild.
 */
public interface GeneratorRuntime {

    /** Re-read every script from disk. Safe to call while the UI is open. */
    void reload() throws GeneratorException;

    List<GeneratorDef> generators();

    Optional<GeneratorDef> byId(String id);

    /**
     * Run a generator and return its image.
     *
     * <p>{@code params} is keyed by {@link ParamDef#key()}. Values are Strings for
     * text, select and colour params, {@code List<String>} for LINES, Double for
     * NUMBER and Boolean for BOOLEAN. Implementations must not mutate the map.
     */
    BufferedImage render(String generatorId, Map<String, Object> params) throws GeneratorException;

    /**
     * The generator's output as editable layers, when it can describe itself that
     * way.
     *
     * <p>A script that defines {@code document(params)} produces something that can
     * be opened in the editor and saved as a template, rather than a flat image
     * that can only be regenerated. Empty when the script only draws, which keeps
     * every existing generator working untouched.
     */
    default Optional<Document> document(String generatorId, Map<String, Object> params)
            throws GeneratorException {
        return Optional.empty();
    }
}
