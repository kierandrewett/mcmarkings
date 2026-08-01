package dev.kierandrewett.mcmarkings.js;

import org.mozilla.javascript.Callable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

/**
 * Conversions between Rhino values and Java, in one place so the runtime and the
 * canvas binding coerce arguments the same way and report the same kind of error.
 *
 * <p>Everything here throws messages aimed at the script author rather than at us.
 */
final class JsValues {

    private JsValues() {
    }

    /** False for absent, null and undefined, which scripts treat as the same thing. */
    static boolean present(Object value) {
        return value != null && value != Scriptable.NOT_FOUND && !Undefined.isUndefined(value);
    }

    /** @return the property, or null when it is absent, null or undefined */
    static Object property(Scriptable object, String key) {
        Object value = ScriptableObject.getProperty(object, key);
        return present(value) ? value : null;
    }

    static Object arg(Object[] args, int index) {
        return index < args.length ? args[index] : Undefined.instance;
    }

    static double number(Object value, String what) {
        double number = ScriptRuntime.toNumber(value);
        if (!Double.isFinite(number)) {
            throw new IllegalArgumentException(what + " must be a finite number, got " + text(value));
        }
        return number;
    }

    static double numberArg(Object[] args, int index, String what) {
        Object value = arg(args, index);
        if (!present(value)) {
            throw new IllegalArgumentException(what + " is required");
        }
        return number(value, what);
    }

    static String stringArg(Object[] args, int index, String what) {
        Object value = arg(args, index);
        if (!present(value)) {
            throw new IllegalArgumentException(what + " is required");
        }
        return ScriptRuntime.toString(value);
    }

    static String text(Object value) {
        return ScriptRuntime.toString(value);
    }

    static boolean bool(Object value) {
        return ScriptRuntime.toBoolean(value);
    }

    static Scriptable object(Context cx, Scriptable scope, Object... keysAndValues) {
        Scriptable object = cx.newObject(scope);
        for (int index = 0; index < keysAndValues.length; index += 2) {
            ScriptableObject.putProperty(object, (String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return object;
    }

    static Scriptable array(Context cx, Scriptable scope, Object[] values) {
        return cx.newArray(scope, values);
    }

    /**
     * Wraps a Java body as a JS function, turning our own argument complaints into
     * proper JS Errors so the script author sees a normal exception with a line
     * number. Everything else, including the Error the timeout uses to abort,
     * propagates untouched so a runaway script cannot swallow its own kill signal.
     */
    static Function fn(Scriptable scope, String name, int arity, Callable body) {
        Callable guarded = (cx, callScope, thisObj, args) -> {
            try {
                return body.call(cx, callScope, thisObj, args);
            } catch (IllegalArgumentException | IllegalStateException expected) {
                throw ScriptRuntime.constructError("Error", name + "(): " + expected.getMessage());
            }
        };
        return new LambdaFunction(scope, name, arity, guarded);
    }
}
