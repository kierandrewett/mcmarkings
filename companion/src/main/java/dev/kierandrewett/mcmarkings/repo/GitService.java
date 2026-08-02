package dev.kierandrewett.mcmarkings.repo;

import java.nio.file.Path;
import java.util.List;

/**
 * Git operations against the local clone, run as real subprocesses.
 *
 * <p>Implementations must never read or write git configuration at any scope.
 * If identity or credentials are missing, surface the failure and stop.
 */
public interface GitService {

    /** Current HEAD commit SHA. */
    String head() throws GitException;

    /**
     * The commit a public raw URL should be pinned to.
     *
     * <p>Deliberately not HEAD. ImageFrame fetches images over HTTP from the
     * server, so a commit that exists only on this machine is a guaranteed 404;
     * this is the newest commit known to be on the remote.
     */
    String pinnableCommit() throws GitException;

    /** Checked-out branch name. */
    String currentBranch() throws GitException;

    /** "owner/repo" parsed from origin, for the slug half of a raw file URL. */
    String remoteSlug() throws GitException;

    /**
     * The origin remote's URL, verbatim.
     *
     * <p>Needed as well as the slug because which forge serves the raw file, and at
     * which host, is only knowable from the URL. Callers must not put the result on
     * screen or into a command: a remote can carry an access token.
     */
    String remoteUrl() throws GitException;

    /** True when the working tree has no uncommitted changes. */
    boolean isClean() throws GitException;

    PullResult pull() throws GitException;

    /**
     * Stage exactly {@code files}, commit with {@code message}, push, and return
     * the new HEAD SHA. Only the given paths are staged, so unrelated dirty files
     * in the tree are left alone.
     */
    String commitAndPush(List<Path> files, String message) throws GitException;
}
