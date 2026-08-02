package dev.kierandrewett.mcmarkings.repo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Reads the handful of git facts this mod needs straight out of {@code .git},
 * without running git.
 *
 * <p>This exists because the client may not be able to run git at all. Prism
 * Launcher installed as a Flatpak is the case that forced it: the runtime ships
 * no git binary, and no sandbox permission adds one. Everything on the read path
 * (which commit to pin a URL to, which repository it belongs to) is plain text on
 * disk, so browsing and placing signs work regardless.
 *
 * <p>Only pulling and pushing genuinely need the binary, and those say so plainly
 * when it is missing rather than failing obscurely.
 */
public final class GitFiles {

    private final Path gitDir;

    private GitFiles(Path gitDir) {
        this.gitDir = gitDir;
    }

    /** Resolves the git directory for a working tree, or empty when there is not one. */
    public static Optional<GitFiles> at(Path repoRoot) {
        Path dotGit = repoRoot.resolve(".git");

        if (Files.isDirectory(dotGit)) {
            return Optional.of(new GitFiles(dotGit));
        }

        // A worktree or submodule has .git as a file pointing elsewhere.
        if (Files.isRegularFile(dotGit)) {
            try {
                String contents = Files.readString(dotGit, StandardCharsets.UTF_8).trim();
                if (contents.startsWith("gitdir:")) {
                    Path target = Path.of(contents.substring("gitdir:".length()).trim());
                    Path resolved = target.isAbsolute() ? target : repoRoot.resolve(target).normalize();
                    if (Files.isDirectory(resolved)) {
                        return Optional.of(new GitFiles(resolved));
                    }
                }
            } catch (IOException ignored) {
                // Fall through; the caller will fall back to the git binary.
            }
        }

        return Optional.empty();
    }

    /** Commit SHA at HEAD, following the symbolic ref when there is one. */
    public Optional<String> head() {
        Optional<String> raw = readTrimmed(gitDir.resolve("HEAD"));
        if (raw.isEmpty()) {
            return Optional.empty();
        }

        String contents = raw.get();
        if (!contents.startsWith("ref:")) {
            // Detached HEAD holds the SHA directly.
            return isSha(contents) ? Optional.of(contents) : Optional.empty();
        }

        String ref = contents.substring("ref:".length()).trim();
        Optional<String> loose = readTrimmed(gitDir.resolve(ref));
        if (loose.isPresent() && isSha(loose.get())) {
            return loose;
        }
        return packedRef(ref);
    }

    /**
     * The commit origin last known to have, for the given branch.
     *
     * <p>This, not HEAD, is what a public raw URL has to point at. ImageFrame
     * fetches over HTTP from the server, so a commit sitting unpushed on this
     * machine is a guaranteed 404 no matter how correct the URL looks.
     */
    public Optional<String> remoteHead(String branch) {
        String ref = "refs/remotes/origin/" + branch;
        Optional<String> loose = readTrimmed(gitDir.resolve(ref));
        if (loose.isPresent() && isSha(loose.get())) {
            return loose;
        }
        return packedRef(ref);
    }

    /** Checked-out branch name, or empty when HEAD is detached. */
    public Optional<String> currentBranch() {
        return readTrimmed(gitDir.resolve("HEAD"))
                .filter(contents -> contents.startsWith("ref:"))
                .map(contents -> contents.substring("ref:".length()).trim())
                .filter(ref -> ref.startsWith("refs/heads/"))
                .map(ref -> ref.substring("refs/heads/".length()));
    }

    /** "owner/repo" taken from the origin remote in the config file. */
    public Optional<String> remoteSlug() {
        return remoteUrl().map(ProcessGitService::parseSlug).filter(slug -> slug != null && !slug.isBlank());
    }

    /**
     * Minimal INI walk over .git/config looking for origin's url. Good enough for a
     * file git itself wrote, and not a general purpose config parser.
     */
    private Optional<String> remoteUrl() {
        List<String> lines;
        try {
            lines = Files.readAllLines(gitDir.resolve("config"), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return Optional.empty();
        }

        boolean inOrigin = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[")) {
                inOrigin = trimmed.replace("\"", "").replaceAll("\\s+", " ")
                        .equalsIgnoreCase("[remote origin]");
                continue;
            }
            if (inOrigin && trimmed.startsWith("url")) {
                int equals = trimmed.indexOf('=');
                if (equals >= 0) {
                    return Optional.of(trimmed.substring(equals + 1).trim());
                }
            }
        }
        return Optional.empty();
    }

    /** Refs get packed away by git gc, at which point the loose file is gone. */
    private Optional<String> packedRef(String ref) {
        List<String> lines;
        try {
            lines = Files.readAllLines(gitDir.resolve("packed-refs"), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return Optional.empty();
        }

        for (String line : lines) {
            if (line.startsWith("#") || line.startsWith("^")) {
                continue;
            }
            int space = line.indexOf(' ');
            if (space > 0 && line.substring(space + 1).trim().equals(ref)) {
                String sha = line.substring(0, space).trim();
                if (isSha(sha)) {
                    return Optional.of(sha);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> readTrimmed(Path path) {
        try {
            return Optional.of(Files.readString(path, StandardCharsets.UTF_8).trim());
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private static boolean isSha(String value) {
        return value.length() >= 40 && value.chars().allMatch(c -> Character.digit(c, 16) >= 0);
    }
}
