package dev.kierandrewett.mcmarkings;

import dev.kierandrewett.mcmarkings.config.RepositoryEntry;
import dev.kierandrewett.mcmarkings.core.RepoImage;
import dev.kierandrewett.mcmarkings.js.GeneratorDef;
import dev.kierandrewett.mcmarkings.js.GeneratorException;
import dev.kierandrewett.mcmarkings.js.GeneratorRuntime;
import dev.kierandrewett.mcmarkings.repo.GitException;
import dev.kierandrewett.mcmarkings.repo.GitService;
import dev.kierandrewett.mcmarkings.repo.PullResult;
import dev.kierandrewett.mcmarkings.repo.RepoService;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The workspace used when no repository is configured.
 *
 * <p>A fresh install has no folder set up, which is a starting point rather than a
 * fault. Handing back empty results and refusals that explain themselves means a
 * screen opened before setup shows an empty list and a prompt, instead of throwing
 * from somewhere that cannot say what is wrong.
 */
final class EmptyWorkspace {

    private static final String NOTHING_CONFIGURED =
            "No repository is set up yet. Open the repositories screen and add a folder.";

    private EmptyWorkspace() {
    }

    static Workspace create() {
        RepositoryEntry entry = new RepositoryEntry("", "No repository", "", "main", "");
        return new Workspace(entry, new NoRepo(), new NoGit(), new NoGenerators(), NOTHING_CONFIGURED);
    }

    private static final class NoRepo implements RepoService {

        @Override
        public Path root() {
            return Path.of("");
        }

        @Override
        public Path resolve(String repoPath) {
            return Path.of(repoPath);
        }

        @Override
        public List<RepoImage> images() {
            return List.of();
        }

        @Override
        public Optional<RepoImage> byPath(String repoPath) {
            return Optional.empty();
        }

        @Override
        public List<RepoImage> search(String query, int limit) {
            return List.of();
        }

        @Override
        public void rescan() {
        }
    }

    private static final class NoGit implements GitService {

        @Override
        public String head() throws GitException {
            throw refuse("head");
        }

        @Override
        public String pinnableCommit() throws GitException {
            throw refuse("pinnable commit");
        }

        @Override
        public String currentBranch() throws GitException {
            throw refuse("current branch");
        }

        @Override
        public String remoteSlug() throws GitException {
            throw refuse("remote");
        }

        @Override
        public boolean isClean() throws GitException {
            throw refuse("status");
        }

        @Override
        public PullResult pull() throws GitException {
            throw refuse("pull");
        }

        @Override
        public String commitAndPush(List<Path> files, String message) throws GitException {
            throw refuse("publish");
        }

        private static GitException refuse(String operation) {
            return new GitException(operation, -1, NOTHING_CONFIGURED);
        }
    }

    private static final class NoGenerators implements GeneratorRuntime {

        @Override
        public void reload() {
        }

        @Override
        public List<GeneratorDef> generators() {
            return List.of();
        }

        @Override
        public Optional<GeneratorDef> byId(String id) {
            return Optional.empty();
        }

        @Override
        public BufferedImage render(String generatorId, Map<String, Object> params) throws GeneratorException {
            throw new GeneratorException(NOTHING_CONFIGURED);
        }
    }
}
