package dev.kierandrewett.mcmarkings.core;

import java.util.Locale;

/**
 * Turning a failure into something someone can act on.
 *
 * <p>Written for one failure in particular, which everybody who ever updates this
 * mod will meet. Replacing the jar while the game is running does not reload it: the
 * classes already in memory carry on, and the first time the mod needs one it has
 * not touched yet, it goes to the jar on disk and finds a different file. What comes
 * back is "Failed to load class file for
 * dev.kierandrewett.mcmarkings.repo.PullResult", which names a class nobody has heard
 * of and says nothing about what to do.
 *
 * <p>The answer is always the same and it is never "reopen the window", which is
 * what the error banner used to suggest. The window is not the problem; the game has
 * half a mod loaded.
 */
public final class Diagnosis {

    private Diagnosis() {
    }

    /**
     * A sentence to add to a failure, or empty when there is nothing useful to say.
     *
     * <p>Empty rather than a guess. Most failures are ordinary and an explanation
     * invented for them would be worse than the message they came with.
     */
    public static String adviceFor(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (looksLikeAStaleJar(cause)) {
                return "The mod's files changed while the game was running, so it has "
                        + "half of one version loaded and half of another. Restart Minecraft.";
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return "";
    }

    /** The same question about a message, for the places that only kept the text. */
    public static String adviceFor(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (text.contains("failed to load class file") || text.contains("noclassdeffound")
                || text.contains("classnotfound")) {
            return "The mod's files changed while the game was running, so it has "
                    + "half of one version loaded and half of another. Restart Minecraft.";
        }
        return "";
    }

    /**
     * Whether this is the game failing to read a class rather than the mod failing.
     *
     * <p>Both the error and the exception, because the loader raises different ones
     * depending on where it gave up, and the message is checked as well because Fabric
     * wraps some of them in a plain RuntimeException on the way past.
     */
    private static boolean looksLikeAStaleJar(Throwable cause) {
        if (cause instanceof NoClassDefFoundError || cause instanceof ClassNotFoundException
                || cause instanceof LinkageError) {
            return true;
        }
        String message = cause.getMessage();
        return message != null
                && message.toLowerCase(Locale.ROOT).contains("failed to load class file");
    }
}
