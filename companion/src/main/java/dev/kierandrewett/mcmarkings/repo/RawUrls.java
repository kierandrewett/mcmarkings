package dev.kierandrewett.mcmarkings.repo;

import java.util.Locale;
import java.util.Set;

/**
 * Builds the URLs ImageFrame fetches server-side.
 *
 * <p>Always pin to a commit SHA rather than a branch. Forges cache branch raw URLs
 * for minutes, so a freshly pushed image would come back as the previous version or
 * a 404; a commit URL is immutable and live as soon as the push lands. A template
 * without {@code {commit}} in it is refused for that reason, and so is a ref that
 * does not look like a SHA.
 *
 * <p>Which forge serves a repository is worked out from that repository's own
 * origin remote, because every forge lays raw file URLs out differently and nothing
 * here should assume GitHub. A host nobody recognises falls back to the Gitea shape,
 * which is by far the most common thing people self-host, and says so rather than
 * pretending to know. A repository can override the template outright when the guess
 * is wrong or the clone is served through a proxy.
 */
public final class RawUrls {

    /** Everything a template is allowed to ask for. Anything else is a typo. */
    private static final Set<String> PLACEHOLDERS = Set.of("{host}", "{slug}", "{commit}", "{path}");

    /**
     * Shortest ref that could plausibly be an abbreviated SHA.
     *
     * <p>Guards the pinning rule from the other side: a template can promise a
     * {@code {commit}} and still be handed a branch name.
     */
    private static final int MIN_COMMIT_LENGTH = 7;

    /**
     * The raw-file URL shape each forge serves.
     *
     * <p>Every one of these was checked against a live public instance rather than
     * recalled, since the difference between them is one path segment and a wrong
     * guess is an unexplained 404 in-game.
     */
    public enum Forge {

        /**
         * Raw files come off a separate host entirely, so {@code {host}} is unused.
         *
         * <p>Confirmed: {@code raw.githubusercontent.com/torvalds/linux/v6.6/README}
         * returns 200.
         */
        GITHUB("GitHub", "https://raw.githubusercontent.com/{slug}/{commit}/{path}"),

        /**
         * The {@code /-/} segment separates project paths from GitLab's own routes.
         *
         * <p>Confirmed: {@code gitlab.com/gitlab-org/gitlab-foss/-/raw/<sha>/README.md}
         * returns 200. The older form without {@code /-/} still resolves, but it is
         * the deprecated spelling, so the current one is what gets built.
         */
        GITLAB("GitLab", "{host}/{slug}/-/raw/{commit}/{path}"),

        /**
         * Gitea names the kind of ref in the path, so a SHA needs {@code raw/commit}.
         *
         * <p>Confirmed on both Gitea and Forgejo:
         * {@code gitea.com/gitea/tea/raw/commit/<sha>/README.md} and
         * {@code codeberg.org/forgejo/forgejo/raw/commit/<sha>/README.md} return 200.
         * Dropping the {@code commit} segment returns a 303 instead, and ImageFrame
         * should not be relied on to follow one.
         */
        GITEA("Gitea or Forgejo", "{host}/{slug}/raw/commit/{commit}/{path}"),

        /** A per-repository override. Carries no template of its own. */
        CUSTOM("custom template", "");

        private final String label;

        private final String template;

        Forge(String label, String template) {
            this.label = label;
            this.template = template;
        }

        public String label() {
            return label;
        }

        public String template() {
            return template;
        }
    }

    /**
     * The raw URL shape for one repository, and how much of it is known rather than
     * assumed.
     *
     * @param forge    where the template came from
     * @param origin   scheme, host and port of the remote, substituted for {@code {host}}
     * @param template the template itself, placeholders unexpanded
     * @param slug     "owner/repo" substituted for {@code {slug}}
     * @param guessed  true when the host was not recognised, so the UI can say so
     */
    public record Target(Forge forge, String origin, String template, String slug, boolean guessed) {

        /** A commit-pinned URL for one file in this repository. */
        public String pinned(String commitSha, String repoPath) {
            if (commitSha == null || commitSha.isBlank()) {
                throw new IllegalArgumentException("a commit sha is required");
            }
            if (repoPath == null || repoPath.isBlank()) {
                throw new IllegalArgumentException("a repository path is required");
            }

            String commit = commitSha.trim();
            if (!looksLikeCommit(commit)) {
                throw new IllegalArgumentException("\"" + commit + "\" is not a commit sha. "
                        + "A branch URL is cached for minutes and would serve a stale image or a 404.");
            }

            return template
                    .replace("{host}", origin == null ? "" : origin)
                    .replace("{slug}", slug)
                    .replace("{commit}", commit)
                    .replace("{path}", normalise(repoPath));
        }

        /** The host these URLs are actually fetched from, which is the diagnosable bit. */
        public String urlHost() {
            String expanded = template.replace("{host}", origin == null ? "" : origin);
            int scheme = expanded.indexOf("://");
            String rest = scheme < 0 ? expanded : expanded.substring(scheme + 3);
            int slash = rest.indexOf('/');
            return slash < 0 ? rest : rest.substring(0, slash);
        }

        /** One line for the UI, saying plainly when the forge was only guessed at. */
        public String describe() {
            if (forge == Forge.CUSTOM) {
                return Forge.CUSTOM.label() + ", " + urlHost();
            }
            return (guessed ? "guessed " : "") + forge.label() + ", " + urlHost();
        }
    }

    private RawUrls() {
    }

    /**
     * Works out how to build URLs for one repository.
     *
     * <p>Overrides come first and are taken at face value: someone who has written a
     * template down knows something detection does not. Detection then reads the
     * remote, and only falls back to a guess when the host is genuinely unfamiliar.
     *
     * @param remoteUrl       the origin remote, or blank when overrides cover everything
     * @param slugOverride    "owner/repo" from config, or blank to read it from the remote
     * @param templateOverride raw URL template from config, or blank to detect one
     * @throws IllegalArgumentException when no usable URL could be built, rather than
     *                                  returning one that is quietly wrong
     */
    public static Target resolve(String remoteUrl, String slugOverride, String templateOverride) {
        String origin = parseOrigin(remoteUrl);

        String slug = blank(slugOverride) ? ProcessGitService.parseSlug(remoteUrl) : slugOverride.trim();
        if (blank(slug)) {
            // Deliberately does not echo the remote: it can carry an access token,
            // and this message ends up on screen.
            throw new IllegalArgumentException("Could not read owner/repo from the origin remote. "
                    + "Set a slug override for this repository.");
        }

        if (!blank(templateOverride)) {
            String template = templateOverride.trim();
            validate(template, origin);
            return new Target(Forge.CUSTOM, origin, template, slug, false);
        }

        if (blank(origin)) {
            throw new IllegalArgumentException("Could not read a host from the origin remote. "
                    + "Set a raw URL template for this repository.");
        }

        String host = hostOf(origin);
        Forge known = knownForge(host);
        Forge forge = known == null ? guessForge(host) : known;
        return new Target(forge, origin, forge.template(), slug, known == null);
    }

    /**
     * Rejects a template that could not build a working, commit-pinned URL.
     *
     * <p>Checked when the target is resolved rather than when a URL is built, so a
     * typo in config surfaces on the screen that reports the repository instead of
     * on the command that places a sign.
     */
    static void validate(String template, String origin) {
        int open = template.indexOf('{');
        while (open >= 0) {
            int close = template.indexOf('}', open);
            if (close < 0) {
                throw new IllegalArgumentException("Raw URL template has an unclosed placeholder: " + template);
            }
            String placeholder = template.substring(open, close + 1);
            if (!PLACEHOLDERS.contains(placeholder)) {
                throw new IllegalArgumentException("Raw URL template uses an unknown placeholder "
                        + placeholder + ". Known ones are " + String.join(", ", PLACEHOLDERS) + ".");
            }
            open = template.indexOf('{', close + 1);
        }

        if (!template.contains("{commit}")) {
            throw new IllegalArgumentException("Raw URL template must contain {commit}. "
                    + "A branch URL is cached for minutes and would serve a stale image or a 404.");
        }
        if (!template.contains("{path}")) {
            throw new IllegalArgumentException("Raw URL template must contain {path}.");
        }
        if (template.contains("{host}") && blank(origin)) {
            throw new IllegalArgumentException("Raw URL template uses {host}, but no host could be read "
                    + "from the origin remote. Write the host into the template instead.");
        }
    }

    /**
     * The web origin a remote URL points at: scheme, host and, where it means
     * anything, port.
     *
     * <p>Credentials are dropped rather than carried through. A remote can hold a
     * personal access token, and these URLs end up in a chat command typed at a
     * server, so carrying one through would publish it.
     *
     * <p>An ssh port is dropped too. {@code ssh://git@host:2222/owner/repo} says
     * nothing about which port the web server answers on, and putting 2222 into an
     * https URL would guarantee a failure.
     *
     * @return the origin, or null when there is no host in the URL
     */
    static String parseOrigin(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return null;
        }

        String url = remoteUrl.trim();
        String scheme = "https";
        boolean web = false;

        int separator = url.indexOf("://");
        if (separator >= 0) {
            String protocol = url.substring(0, separator).toLowerCase(Locale.ROOT);
            web = protocol.equals("http") || protocol.equals("https");
            // Plain http is worth preserving. A Gitea on a LAN often has no TLS, and
            // silently upgrading it would fail to connect rather than fetch.
            scheme = protocol.equals("http") ? "http" : "https";
            url = url.substring(separator + 3);
        }

        int path = url.indexOf('/');
        String authority = path < 0 ? url : url.substring(0, path);

        int credentials = authority.lastIndexOf('@');
        if (credentials >= 0) {
            authority = authority.substring(credentials + 1);
        }

        int colon = authority.indexOf(':');
        if (colon >= 0) {
            String tail = authority.substring(colon + 1);
            // In the scp-like form the colon starts the path, not a port, and only a
            // run of digits could be a port anyway.
            boolean port = !tail.isEmpty() && tail.chars().allMatch(Character::isDigit);
            authority = port && web ? authority : authority.substring(0, colon);
        }

        if (authority.isBlank()) {
            return null;
        }
        return scheme + "://" + authority.toLowerCase(Locale.ROOT);
    }

    /** Bare hostname out of an origin, for matching against the forges we know. */
    private static String hostOf(String origin) {
        int scheme = origin.indexOf("://");
        String rest = scheme < 0 ? origin : origin.substring(scheme + 3);
        int port = rest.lastIndexOf(':');
        return port < 0 ? rest : rest.substring(0, port);
    }

    /** Hosts whose layout is a fact rather than an inference. */
    private static Forge knownForge(String host) {
        return switch (host) {
            case "github.com", "www.github.com" -> Forge.GITHUB;
            case "gitlab.com", "www.gitlab.com" -> Forge.GITLAB;
            // Codeberg runs Forgejo, which kept Gitea's raw URL layout when it forked.
            case "codeberg.org", "gitea.com" -> Forge.GITEA;
            default -> null;
        };
    }

    /**
     * Best effort for a host nobody recognises. Always reported as a guess.
     *
     * <p>Gitea and Forgejo are the default because they are what people self-host,
     * and the shape covers both. A self-hosted GitLab is nearly always at
     * {@code gitlab.<domain>} though, and the Gitea shape would 404 there, so the
     * name is worth reading even if it proves nothing.
     */
    private static Forge guessForge(String host) {
        if (host.equals("gitlab") || host.startsWith("gitlab.")) {
            return Forge.GITLAB;
        }
        return Forge.GITEA;
    }

    private static boolean looksLikeCommit(String ref) {
        return ref.length() >= MIN_COMMIT_LENGTH && ref.chars().allMatch(c -> Character.digit(c, 16) >= 0);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalise(String repoPath) {
        String path = repoPath.replace('\\', '/');
        return path.startsWith("/") ? path.substring(1) : path;
    }
}
