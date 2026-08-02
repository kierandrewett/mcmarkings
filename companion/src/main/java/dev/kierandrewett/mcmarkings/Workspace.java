package dev.kierandrewett.mcmarkings;

import dev.kierandrewett.mcmarkings.config.RepositoryEntry;
import dev.kierandrewett.mcmarkings.js.GeneratorRuntime;
import dev.kierandrewett.mcmarkings.repo.GitService;
import dev.kierandrewett.mcmarkings.repo.RepoService;

/**
 * One repository and the services bound to it.
 *
 * <p>Repositories are independent: each has its own clone, its own remote and its
 * own generator scripts, so scanning, git and the script runtime are all per
 * repository rather than global. Only the texture cache, the command sink and the
 * map registry are shared, because those belong to the client rather than to any
 * one folder.
 */
public final class Workspace {

    private final RepositoryEntry entry;
    private final RepoService repo;
    private final GitService git;
    private final GeneratorRuntime generators;

    /** Non-fatal problems found while opening, shown in the repositories screen. */
    private final String warning;

    public Workspace(RepositoryEntry entry, RepoService repo, GitService git,
            GeneratorRuntime generators, String warning) {
        this.entry = entry;
        this.repo = repo;
        this.git = git;
        this.generators = generators;
        this.warning = warning;
    }

    public RepositoryEntry entry() {
        return entry;
    }

    public String id() {
        return entry.id();
    }

    public RepoService repo() {
        return repo;
    }

    public GitService git() {
        return git;
    }

    public GeneratorRuntime generators() {
        return generators;
    }

    public String warning() {
        return warning;
    }

    public boolean hasWarning() {
        return warning != null && !warning.isBlank();
    }
}
