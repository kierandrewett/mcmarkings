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

    /**
     * How far down a cause chain to look.
     *
     * <p>Deeper than anything real. A wrapped exception here is two or three deep and
     * the number is only there so a chain that loops cannot walk forever.
     */
    private static final int MAX_CAUSE_DEPTH = 32;

    private Diagnosis() {
    }

    /**
     * A sentence to add to a failure, or empty when there is nothing useful to say.
     *
     * <p>Empty rather than a guess. Most failures are ordinary and an explanation
     * invented for them would be worse than the message they came with.
     */
    public static String adviceFor(Throwable failure) {
        // Bounded rather than watched for a self-reference. That only catches a cause
        // pointing straight at itself, and a chain going round in a longer circle
        // walks forever: the check I wrote first passed its test by hanging, which a
        // build turns into a timeout rather than a failure and is the worst way for a
        // diagnostic to go wrong.
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (looksLikeAStaleJar(cause)) {
                return "The mod's files changed while the game was running, so it has "
                        + "half of one version loaded and half of another. Restart Minecraft.";
            }
            cause = cause.getCause();
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
