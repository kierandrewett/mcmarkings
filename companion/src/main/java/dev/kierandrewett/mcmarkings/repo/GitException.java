package dev.kierandrewett.mcmarkings.repo;

/**
 * A git command failed. {@code output} carries the combined stdout/stderr
 * verbatim so the UI can show git's own words rather than a paraphrase.
 */
public class GitException extends Exception {

    private final String command;
    private final int exitCode;
    private final String output;

    public GitException(String command, int exitCode, String output) {
        super("git " + command + " failed (exit " + exitCode + "): " + output);
        this.command = command;
        this.exitCode = exitCode;
        this.output = output;
    }

    public GitException(String command, Throwable cause) {
        super("git " + command + " could not be run: " + cause.getMessage(), cause);
        this.command = command;
        this.exitCode = -1;
        this.output = cause.getMessage() == null ? "" : cause.getMessage();
    }

    public String command() {
        return command;
    }

    public int exitCode() {
        return exitCode;
    }

    public String output() {
        return output;
    }

    /**
     * The most useful thing there is to show someone, never empty.
     *
     * <p>Callers want git's own words and mostly reach for {@link #output()}, which is
     * blank when git printed nothing or when the failure was not git speaking at all,
     * such as the binary being missing. Three of the four places that report one of
     * these built "git: " and then appended nothing, which is the least helpful
     * message a program can produce about the most likely failure on a fresh setup.
     *
     * <p>Decided here rather than at each call site, because one of the four already
     * had a fallback and the other three did not, and that is the shape of a rule
     * that lives in the wrong place.
     */
    public String describe() {
        if (output != null && !output.isBlank()) {
            return output;
        }
        String message = getMessage();
        return message == null || message.isBlank() ? "git could not be run" : message;
    }
}
