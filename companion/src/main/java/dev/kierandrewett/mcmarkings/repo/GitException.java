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
}
