package dev.kierandrewett.mcmarkings.js;

/**
 * A generator script failed to load or render. The message is shown to the user
 * as-is, so it should carry the script name and, where the engine gives one, the
 * line number.
 */
public class GeneratorException extends Exception {

    public GeneratorException(String message) {
        super(message);
    }

    public GeneratorException(String message, Throwable cause) {
        super(message, cause);
    }
}
