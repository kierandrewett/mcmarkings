package dev.kierandrewett.mcmarkings;

import dev.kierandrewett.mcmarkings.config.RepositoryEntry;
import dev.kierandrewett.mcmarkings.js.GeneratorRuntime;
import dev.kierandrewett.mcmarkings.repo.GitException;
import dev.kierandrewett.mcmarkings.repo.GitService;
import dev.kierandrewett.mcmarkings.repo.RawUrls;
import dev.kierandrewett.mcmarkings.doc.TemplateStore;
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

    /**
     * Templates for this repository.
     *
     * <p>Per workspace rather than global, because a template refers to images by
     * repository-relative path and means nothing in a repository that lacks them.
     */
    private final TemplateStore templates;

    /** Non-fatal problems found while opening, shown in the repositories screen. */
    private final String warning;

    public Workspace(RepositoryEntry entry, RepoService repo, GitService git,
            GeneratorRuntime generators, String warning) {
        this.entry = entry;
        this.repo = repo;
        this.git = git;
        this.generators = generators;
        this.templates = new TemplateStore(repo.root());
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

    public TemplateStore templates() {
        return templates;
    }

    /**
     * How raw file URLs are built for this repository, and which forge they point at.
     *
     * <p>Resolved per call rather than cached. A remote can be added or repointed
     * while the game is running, and working it out is a couple of file reads.
     */
    public RawUrls.Target rawUrls() throws GitException {
        // A repository that spells out both the slug and the template needs nothing
        // from the remote, so do not demand one it may not have.
        boolean needsRemote = entry.slugOverride().isBlank() || entry.rawUrlTemplate().isBlank();
        String remote = needsRemote ? git.remoteUrl() : "";

        try {
            return RawUrls.resolve(remote, entry.slugOverride(), entry.rawUrlTemplate());
        } catch (IllegalArgumentException exception) {
            // Callers already handle a repository that cannot produce URLs; a second
            // failure type on the same path would just be one more thing to forget.
            throw new GitException("raw url", -1, exception.getMessage());
        }
    }

    public String warning() {
        return warning;
    }

    public boolean hasWarning() {
        return warning != null && !warning.isBlank();
    }
}
