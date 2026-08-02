package dev.kierandrewett.mcmarkings.js;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonElement;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

/**
 * Converts a value a script returned into JSON.
 *
 * <p>Exists so a generator that describes a document can hand back a plain object
 * and have it become a {@code Document} through the codec that already parses
 * templates. That codec knows every field alias, every default and every way a
 * value can be wrong, and reproducing that judgement for scripts would mean two
 * sets of rules to keep in step and two places for them to disagree.
 *
 * <p>The conversion is deliberately shallow in what it trusts: anything that is not
 * a plain object, array, string, number or boolean is dropped rather than coerced,
 * because a Java object leaking through would only fail further downstream where
 * the cause is no longer obvious.
 */
final class ScriptJson {

    /** Guards against a script returning a structure that refers to itself. */
    private static final int MAX_DEPTH = 32;

    private ScriptJson() {
    }

    static JsonElement of(Object value) throws GeneratorException {
        return convert(value, 0);
    }

    private static JsonElement convert(Object value, int depth) throws GeneratorException {
        if (depth > MAX_DEPTH) {
            throw new GeneratorException("the returned document is nested too deeply to be real");
        }

        if (value == null || value == Scriptable.NOT_FOUND || Undefined.isUndefined(value)) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof CharSequence text) {
            return new JsonPrimitive(text.toString());
        }
        if (value instanceof Boolean flag) {
            return new JsonPrimitive(flag);
        }
        if (value instanceof Number number) {
            return new JsonPrimitive(number);
        }
        if (value instanceof NativeArray array) {
            return convertArray(array, depth);
        }
        if (value instanceof Scriptable object) {
            return convertObject(object, depth);
        }

        // Anything else is a Java object that should never have reached a script.
        return JsonNull.INSTANCE;
    }

    private static JsonArray convertArray(NativeArray array, int depth) throws GeneratorException {
        JsonArray result = new JsonArray();
        long length = array.getLength();
        for (long index = 0; index < length; index++) {
            result.add(convert(ScriptableObject.getProperty(array, (int) index), depth + 1));
        }
        return result;
    }

    private static JsonObject convertObject(Scriptable object, int depth) throws GeneratorException {
        JsonObject result = new JsonObject();
        for (Object id : object.getIds()) {
            if (!(id instanceof String key)) {
                // A numeric key on a plain object is not something the document
                // format has any meaning for.
                continue;
            }
            result.add(key, convert(ScriptableObject.getProperty(object, key), depth + 1));
        }
        return result;
    }
}
