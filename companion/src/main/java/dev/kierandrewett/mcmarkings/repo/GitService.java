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

    /** Checked-out branch name. */
    String currentBranch() throws GitException;

    /** "owner/repo" parsed from origin, for building raw.githubusercontent URLs. */
    String remoteSlug() throws GitException;

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
