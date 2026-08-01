package dev.kierandrewett.mcmarkings.repo;

/**
 * Builds the URLs ImageFrame fetches server-side.
 *
 * <p>Always pin to a commit SHA rather than a branch. Branch URLs on
 * raw.githubusercontent are cached for around five minutes, so a freshly pushed
 * image would come back as the previous version or a 404; a commit URL is
 * immutable and live as soon as the push lands.
 */
public final class RawUrls {

    private static final String RAW_HOST = "https://raw.githubusercontent.com";

    private RawUrls() {
    }

    public static String pinned(String slug, String commitSha, String repoPath) {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("repository slug is required");
        }
        if (commitSha == null || commitSha.isBlank()) {
            throw new IllegalArgumentException("commit sha is required");
        }
        return RAW_HOST + "/" + slug + "/" + commitSha + "/" + normalise(repoPath);
    }

    private static String normalise(String repoPath) {
        String path = repoPath.replace('\\', '/');
        return path.startsWith("/") ? path.substring(1) : path;
    }
}
