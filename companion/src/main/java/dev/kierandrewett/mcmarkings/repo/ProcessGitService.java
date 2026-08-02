package dev.kierandrewett.mcmarkings.repo;

import dev.kierandrewett.mcmarkings.McMarkingsCompanion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Git driven as real subprocesses against the local clone.
 *
 * <p>Every command is an argument array handed straight to {@link ProcessBuilder}.
 * There is no shell anywhere in this class: a commit message or a file name with
 * a quote, a space or a semicolon in it would otherwise be a command injection,
 * and sign file names are generated from descriptions.
 *
 * <p>This class never reads or writes git configuration. If a commit fails for a
 * missing identity, that failure is surfaced with git's own words rather than
 * quietly patched up, because the user's git config is theirs to own.
 */
public class ProcessGitService implements GitService {

    /** Local plumbing is either instant or wedged; there is no slow-but-fine case. */
    private static final Duration LOCAL_TIMEOUT = Duration.ofSeconds(15);

    /** Fetch and push have to cross the network and can legitimately take a while. */
    private static final Duration NETWORK_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Whether this JVM is inside a Flatpak sandbox.
     *
     * <p>Flatpak writes this file into every sandbox and it exists nowhere else, so
     * it is the standard way to ask. It matters because the Flatpak runtime has no
     * git of its own and commands have to be handed to the host instead.
     */
    private static final boolean SANDBOXED = Files.exists(Path.of("/.flatpak-info"));

    /** The sandbox's own application id, so advice can name it rather than guess. */
    private static final String SANDBOX_APP_ID =
            System.getenv().getOrDefault("FLATPAK_ID", "<your-launcher-id>");

    private final Path root;

    private volatile boolean repoVerified;

    private volatile Optional<GitFiles> gitFiles;

    private volatile Boolean binaryPresent;

    public ProcessGitService(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /**
     * Read-only facts come from the files in .git before the binary is tried.
     *
     * <p>Not just an optimisation. A Flatpak Prism Launcher has no git binary and
     * no permission can add one, so reading the files is the only way browsing and
     * placing signs work there at all. It is also faster, since it avoids spawning
     * a process for something that is a few bytes on disk.
     */
    @Override
    public String head() throws GitException {
        Optional<String> fromFiles = files().flatMap(GitFiles::head);
        if (fromFiles.isPresent()) {
            return fromFiles.get();
        }
        ensureRepository();
        return firstLine(exec(LOCAL_TIMEOUT, "rev-parse", "HEAD"));
    }

    @Override
    public String currentBranch() throws GitException {
        Optional<String> fromFiles = files().flatMap(GitFiles::currentBranch);
        if (fromFiles.isPresent()) {
            return fromFiles.get();
        }
        ensureRepository();
        return firstLine(exec(LOCAL_TIMEOUT, "rev-parse", "--abbrev-ref", "HEAD"));
    }

    @Override
    public String remoteSlug() throws GitException {
        String slug = parseSlug(remoteUrl());
        if (slug == null) {
            // Never quotes the URL back. It can hold an access token, and this
            // message is shown on screen.
            throw new GitException("remote get-url origin", -1,
                    "could not read owner/repo from the origin remote");
        }
        return slug;
    }

    @Override
    public String remoteUrl() throws GitException {
        Optional<String> fromFiles = files().flatMap(GitFiles::remoteUrl);
        if (fromFiles.isPresent()) {
            return fromFiles.get();
        }
        ensureRepository();
        String url = firstLine(exec(LOCAL_TIMEOUT, "remote", "get-url", "origin"));
        if (url.isBlank()) {
            throw new GitException("remote get-url origin", -1, "this repository has no origin remote");
        }
        return url;
    }

    @Override
    public String pinnableCommit() throws GitException {
        Optional<GitFiles> files = files();
        if (files.isPresent()) {
            Optional<String> remote = files.get().currentBranch().flatMap(files.get()::remoteHead);
            if (remote.isPresent()) {
                return remote.get();
            }
        }

        // No remote-tracking ref to go on. HEAD is the best guess left, and if it
        // has not been pushed the fetch will fail loudly at the server rather than
        // silently serving the wrong image.
        return head();
    }

    /**
     * Assembles the argument array for one git invocation.
     *
     * <p>Two details matter and both are easy to get wrong. Inside a Flatpak the
     * runtime has no git, so the call is handed to the host through flatpak-spawn.
     * And {@code -C} has to carry the repository path rather than relying on the
     * working directory, because a host process spawned out of the sandbox does not
     * inherit the sandbox's cwd. {@code -C} also has to sit before the subcommand,
     * since it is a git option and not an argument to the subcommand.
     */
    static List<String> buildCommand(boolean sandboxed, Path root, String... arguments) {
        List<String> command = new ArrayList<>(arguments.length + 5);
        if (sandboxed) {
            command.add("flatpak-spawn");
            command.add("--host");
        }
        command.add("git");
        command.add("-C");
        command.add(root.toString());
        command.addAll(List.of(arguments));
        return command;
    }

    private Optional<GitFiles> files() {
        if (gitFiles == null) {
            gitFiles = GitFiles.at(root);
        }
        return gitFiles;
    }

    /** True when a git binary is actually runnable, which a Flatpak client may not have. */
    public boolean binaryAvailable() {
        if (binaryPresent == null) {
            try {
                exec(LOCAL_TIMEOUT, "--version");
                binaryPresent = Boolean.TRUE;
            } catch (GitException exception) {
                binaryPresent = Boolean.FALSE;
            }
        }
        return binaryPresent;
    }

    private void requireBinary(String operation) throws GitException {
        if (binaryAvailable()) {
            return;
        }
        String remedy = SANDBOXED
                ? "This client is sandboxed, so git has to run on the host through flatpak-spawn. "
                        + "Grant it with:\n"
                        + "  flatpak override --user --talk-name=org.freedesktop.Flatpak "
                        + SANDBOX_APP_ID + "\n"
                        + "and make sure git is installed on the host."
                : "Install git and make sure it is on the PATH.";
        throw new GitException(operation, -1,
                operation + " needs a git binary and none is reachable. "
                        + "Browsing and placing signs still work without one. " + remedy);
    }

    @Override
    public boolean isClean() throws GitException {
        ensureRepository();
        return exec(LOCAL_TIMEOUT, "status", "--porcelain").isBlank();
    }

    @Override
    public PullResult pull() throws GitException {
        requireBinary("pull");
        ensureRepository();
        String oldHead = head();
        exec(NETWORK_TIMEOUT, "pull", "--ff-only");
        String newHead = head();

        // A no-op pull is the common case, and asking git to diff a commit against
        // itself just to get an empty list is wasted work.
        if (oldHead.equals(newHead)) {
            McMarkingsCompanion.LOGGER.info("[mcmarkings] pull left HEAD at {}", shortSha(oldHead));
            return new PullResult(oldHead, newHead, List.of());
        }

        String diff = exec(LOCAL_TIMEOUT, "diff", "--name-only", oldHead + ".." + newHead);
        List<String> changed = new ArrayList<>();
        for (String line : diff.split("\n")) {
            String path = line.trim();
            if (!path.isEmpty() && path.toLowerCase(Locale.ROOT).endsWith(".png")) {
                changed.add(path);
            }
        }

        McMarkingsCompanion.LOGGER.info(
                "[mcmarkings] pulled {} -> {}, {} png(s) changed",
                shortSha(oldHead), shortSha(newHead), changed.size());
        return new PullResult(oldHead, newHead, List.copyOf(changed));
    }

    @Override
    public String commitAndPush(List<Path> files, String message) throws GitException {
        requireBinary("commit and push");
        ensureRepository();
        if (files == null || files.isEmpty()) {
            throw new GitException("add", -1, "no files given to commit");
        }
        if (message == null || message.isBlank()) {
            throw new GitException("commit", -1, "a commit message is required");
        }

        // "add -- <paths>" rather than "add -A": the working tree may hold unrelated
        // edits the user is midway through, and staging those would be theft.
        List<String> add = new ArrayList<>(List.of("add", "--"));
        for (Path file : files) {
            add.add(toRepoRelative(file));
        }
        exec(LOCAL_TIMEOUT, add.toArray(new String[0]));

        exec(LOCAL_TIMEOUT, "commit", "-m", message);
        exec(NETWORK_TIMEOUT, "push");

        String newHead = head();
        McMarkingsCompanion.LOGGER.info(
                "[mcmarkings] committed {} file(s) and pushed as {}", files.size(), shortSha(newHead));
        return newHead;
    }

    /**
     * Pulls "owner/repo" out of a remote URL.
     *
     * <p>Handles both forms every forge hands out: {@code https://host/owner/repo.git}
     * and the scp-like {@code git@host:owner/repo.git}, with or without the
     * {@code .git} suffix.
     *
     * @return the slug, or null when the URL has no owner and repo in it
     */
    static String parseSlug(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return null;
        }

        String url = remoteUrl.trim();
        int scheme = url.indexOf("://");
        if (scheme >= 0) {
            url = url.substring(scheme + 3);
        }
        int credentials = url.indexOf('@');
        if (credentials >= 0) {
            url = url.substring(credentials + 1);
        }

        // What is left starts with the host, separated from the path by ':' in the
        // scp-like form and by '/' everywhere else.
        int colon = url.indexOf(':');
        int slash = url.indexOf('/');
        if (colon >= 0 && (slash < 0 || colon < slash)) {
            url = url.substring(colon + 1);
        } else if (slash >= 0) {
            url = url.substring(slash + 1);
        }

        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.toLowerCase(Locale.ROOT).endsWith(".git")) {
            url = url.substring(0, url.length() - ".git".length());
        }

        String[] segments = url.split("/");
        if (segments.length < 2) {
            return null;
        }
        String owner = segments[segments.length - 2];
        String repository = segments[segments.length - 1];
        if (owner.isBlank() || repository.isBlank()) {
            return null;
        }
        return owner + "/" + repository;
    }

    private String toRepoRelative(Path file) {
        Path absolute = file.isAbsolute() ? file.normalize() : root.resolve(file).normalize();
        if (!absolute.startsWith(root)) {
            // Let git reject it rather than guessing; a path outside the clone is a bug upstream.
            return file.toString().replace('\\', '/');
        }
        return root.relativize(absolute).toString().replace('\\', '/');
    }

    private void ensureRepository() throws GitException {
        if (repoVerified) {
            return;
        }
        if (!Files.isDirectory(root)) {
            throw new GitException("rev-parse", -1, "repository root is not a directory: " + root);
        }
        String inside = firstLine(exec(LOCAL_TIMEOUT, "rev-parse", "--is-inside-work-tree"));
        if (!"true".equals(inside)) {
            throw new GitException("rev-parse --is-inside-work-tree", -1, "not a git work tree: " + root);
        }
        repoVerified = true;
    }

    /**
     * Runs git and returns stdout. Both pipes are drained on their own threads,
     * since reading one to completion while the other fills its buffer deadlocks.
     */
    private String exec(Duration timeout, String... arguments) throws GitException {
        String label = String.join(" ", arguments);

        List<String> command = buildCommand(SANDBOXED, root, arguments);

        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(root.toFile());
            process = builder.start();
        } catch (IOException exception) {
            throw new GitException(label, exception);
        }

        AtomicReference<String> stdout = new AtomicReference<>("");
        AtomicReference<String> stderr = new AtomicReference<>("");
        Thread outReader = drain(process.getInputStream(), stdout);
        Thread errReader = drain(process.getErrorStream(), stderr);

        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                joinQuietly(outReader);
                joinQuietly(errReader);
                throw new GitException(label, -1,
                        "timed out after " + timeout.toSeconds() + "s\n" + combine(stdout.get(), stderr.get()));
            }
            joinQuietly(outReader);
            joinQuietly(errReader);
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new GitException(label, exception);
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new GitException(label, exitCode, combine(stdout.get(), stderr.get()));
        }
        return stdout.get();
    }

    private static Thread drain(InputStream stream, AtomicReference<String> sink) {
        return Thread.ofVirtual().start(() -> {
            try (InputStream source = stream) {
                sink.set(new String(source.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException exception) {
                // The process was killed mid-read; whatever it said is lost and that is fine.
            }
        });
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(Duration.ofSeconds(5));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String combine(String stdout, String stderr) {
        if (stdout.isEmpty()) {
            return stderr;
        }
        if (stderr.isEmpty()) {
            return stdout;
        }
        return stdout + stderr;
    }

    private static String firstLine(String output) {
        String trimmed = output.strip();
        int newline = trimmed.indexOf('\n');
        return newline < 0 ? trimmed : trimmed.substring(0, newline).strip();
    }

    private static String shortSha(String sha) {
        return sha.length() <= 8 ? sha : sha.substring(0, 8);
    }
}
