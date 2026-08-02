package dev.kierandrewett.mcmarkings.repo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The URL shapes here were checked against live public instances of each forge,
 * not recalled. Every expected string in this file matched a real 200.
 */
class RawUrlsTest {

    /** Long enough and hex, so it passes the "this is a commit, not a branch" guard. */
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    private static String url(String remote, String path) {
        return RawUrls.resolve(remote, "", "").pinned(COMMIT, path);
    }

    @Test
    void buildsGitHubUrlsFromEitherRemoteForm() {
        String expected = "https://raw.githubusercontent.com/example-owner/example-repo/"
                + COMMIT + "/signs/give-way.png";

        assertEquals(expected, url("https://github.com/example-owner/example-repo.git", "signs/give-way.png"));
        assertEquals(expected, url("git@github.com:example-owner/example-repo.git", "signs/give-way.png"));
        assertEquals(expected, url("ssh://git@github.com/example-owner/example-repo", "signs/give-way.png"));
    }

    @Test
    void buildsGitLabUrlsFromEitherRemoteForm() {
        String expected = "https://gitlab.com/example-owner/example-repo/-/raw/"
                + COMMIT + "/signs/give-way.png";

        assertEquals(expected, url("https://gitlab.com/example-owner/example-repo.git", "signs/give-way.png"));
        assertEquals(expected, url("git@gitlab.com:example-owner/example-repo.git", "signs/give-way.png"));
    }

    @Test
    void buildsForgejoUrlsForCodebergFromEitherRemoteForm() {
        String expected = "https://codeberg.org/example-owner/example-repo/raw/commit/"
                + COMMIT + "/signs/give-way.png";

        assertEquals(expected, url("https://codeberg.org/example-owner/example-repo.git", "signs/give-way.png"));
        assertEquals(expected, url("git@codeberg.org:example-owner/example-repo.git", "signs/give-way.png"));
    }

    @Test
    void buildsGiteaUrlsFromEitherRemoteForm() {
        String expected = "https://gitea.com/example-owner/example-repo/raw/commit/"
                + COMMIT + "/signs/give-way.png";

        assertEquals(expected, url("https://gitea.com/example-owner/example-repo.git", "signs/give-way.png"));
        assertEquals(expected, url("git@gitea.com:example-owner/example-repo.git", "signs/give-way.png"));
    }

    @Test
    void fallsBackToTheGiteaShapeForAnUnknownHostAndSaysItIsGuessing() {
        RawUrls.Target target = RawUrls.resolve("https://git.example.com/example-owner/example-repo.git", "", "");

        assertEquals(RawUrls.Forge.GITEA, target.forge());
        assertTrue(target.guessed(), "an unrecognised host is a guess and has to be reported as one");
        assertEquals("https://git.example.com/example-owner/example-repo/raw/commit/"
                + COMMIT + "/plate.png", target.pinned(COMMIT, "plate.png"));
        assertTrue(target.describe().startsWith("guessed "), target.describe());
        assertEquals("git.example.com", target.urlHost());
    }

    @Test
    void readsGitlabOutOfASelfHostedHostnameButStillCallsItAGuess() {
        RawUrls.Target target =
                RawUrls.resolve("git@gitlab.example.com:example-owner/example-repo.git", "", "");

        assertEquals(RawUrls.Forge.GITLAB, target.forge());
        assertTrue(target.guessed(), "the hostname is a hint, not a fact");
        assertEquals("https://gitlab.example.com/example-owner/example-repo/-/raw/"
                + COMMIT + "/plate.png", target.pinned(COMMIT, "plate.png"));
    }

    @Test
    void knownHostsAreNotReportedAsGuesses() {
        assertFalse(RawUrls.resolve("https://github.com/example-owner/example-repo", "", "").guessed());
        assertFalse(RawUrls.resolve("https://gitlab.com/example-owner/example-repo", "", "").guessed());
        assertFalse(RawUrls.resolve("https://codeberg.org/example-owner/example-repo", "", "").guessed());
    }

    @Test
    void keepsAWebPortButDropsAnSshOne() {
        assertEquals("https://git.example.com:8443/example-owner/example-repo/raw/commit/"
                        + COMMIT + "/plate.png",
                url("https://git.example.com:8443/example-owner/example-repo.git", "plate.png"));

        // 2222 is where sshd listens, and says nothing about the web server.
        assertEquals("https://git.example.com/example-owner/example-repo/raw/commit/" + COMMIT + "/plate.png",
                url("ssh://git@git.example.com:2222/example-owner/example-repo.git", "plate.png"));
    }

    @Test
    void keepsPlainHttpBecauseALanForgeOftenHasNoTls() {
        assertEquals("http://git.example.com:3000/example-owner/example-repo/raw/commit/" + COMMIT + "/plate.png",
                url("http://git.example.com:3000/example-owner/example-repo.git", "plate.png"));
    }

    @Test
    void aPerRepositoryTemplateWinsOverDetection() {
        RawUrls.Target target = RawUrls.resolve(
                "https://github.com/example-owner/example-repo.git",
                "",
                "https://mirror.example.com/{slug}/at/{commit}/{path}");

        assertEquals(RawUrls.Forge.CUSTOM, target.forge());
        assertFalse(target.guessed(), "a written-down template is not a guess");
        assertEquals("https://mirror.example.com/example-owner/example-repo/at/" + COMMIT + "/plate.png",
                target.pinned(COMMIT, "plate.png"));
    }

    @Test
    void aPerRepositorySlugWinsOverTheRemote() {
        assertEquals("https://raw.githubusercontent.com/other-owner/other-repo/" + COMMIT + "/plate.png",
                RawUrls.resolve("https://github.com/example-owner/example-repo.git", "other-owner/other-repo", "")
                        .pinned(COMMIT, "plate.png"));
    }

    @Test
    void overridesTogetherNeedNoRemoteAtAll() {
        RawUrls.Target target = RawUrls.resolve(
                "", "example-owner/example-repo", "https://files.example.com/{slug}/{commit}/{path}");

        assertEquals("https://files.example.com/example-owner/example-repo/" + COMMIT + "/plate.png",
                target.pinned(COMMIT, "plate.png"));
    }

    @Test
    void credentialsInTheRemoteNeverReachTheUrl() {
        String withToken = "https://example-user:ghp_exampletokenvalue@git.example.com/example-owner/example-repo.git";

        RawUrls.Target target = RawUrls.resolve(withToken, "", "");
        String built = target.pinned(COMMIT, "plate.png");

        assertFalse(built.contains("ghp_exampletokenvalue"), built);
        assertFalse(built.contains("example-user"), built);
        assertFalse(built.contains("@"), built);
        assertEquals("https://git.example.com/example-owner/example-repo/raw/commit/" + COMMIT + "/plate.png", built);
        assertFalse(target.describe().contains("ghp_exampletokenvalue"), target.describe());
    }

    @Test
    void aTokenOnlyRemoteIsAlsoStripped() {
        assertEquals("https://raw.githubusercontent.com/example-owner/example-repo/" + COMMIT + "/plate.png",
                url("https://ghp_exampletokenvalue@github.com/example-owner/example-repo.git", "plate.png"));
    }

    @Test
    void aMissingRemoteFailsRatherThanBuildingSomethingMalformed() {
        assertThrows(IllegalArgumentException.class, () -> RawUrls.resolve(null, "", ""));
        assertThrows(IllegalArgumentException.class, () -> RawUrls.resolve("", "", ""));
        assertThrows(IllegalArgumentException.class, () -> RawUrls.resolve("   ", "", ""));
    }

    @Test
    void anUnparseableRemoteFailsRatherThanBuildingSomethingMalformed() {
        // Host but no owner and repo.
        assertThrows(IllegalArgumentException.class,
                () -> RawUrls.resolve("https://git.example.com/", "", ""));
        assertThrows(IllegalArgumentException.class,
                () -> RawUrls.resolve("git@git.example.com:example-repo.git", "", ""));
        // Slug but no host, and no template to make up for it.
        assertThrows(IllegalArgumentException.class,
                () -> RawUrls.resolve("", "example-owner/example-repo", ""));
    }

    @Test
    void failureMessagesNeverQuoteTheRemoteBack() {
        String withToken = "https://ghp_exampletokenvalue@git.example.com/";

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> RawUrls.resolve(withToken, "", ""));

        assertFalse(failure.getMessage().contains("ghp_exampletokenvalue"), failure.getMessage());
    }

    @Test
    void aTemplateWithoutACommitPlaceholderIsRefused() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RawUrls.resolve("https://github.com/example-owner/example-repo", "",
                        "https://mirror.example.com/{slug}/main/{path}"));

        assertTrue(failure.getMessage().contains("{commit}"), failure.getMessage());
    }

    @Test
    void aTemplateWithoutAPathPlaceholderIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> RawUrls.resolve("https://github.com/example-owner/example-repo", "",
                        "https://mirror.example.com/{slug}/{commit}"));
    }

    @Test
    void aTemplateWithAMisspeltPlaceholderIsRefused() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RawUrls.resolve("https://github.com/example-owner/example-repo", "",
                        "https://mirror.example.com/{slug}/{sha}/{commit}/{path}"));

        assertTrue(failure.getMessage().contains("{sha}"), failure.getMessage());
    }

    @Test
    void aTemplateAskingForAHostThatCouldNotBeReadIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> RawUrls.resolve("", "example-owner/example-repo", "{host}/{slug}/{commit}/{path}"));
    }

    @Test
    void everyBuiltInTemplatePinsToACommit() {
        for (RawUrls.Forge forge : RawUrls.Forge.values()) {
            if (forge == RawUrls.Forge.CUSTOM) {
                continue;
            }
            assertTrue(forge.template().contains("{commit}"), forge + " must pin to a commit");
            assertTrue(forge.template().contains("{path}"), forge + " must carry the file path");
            assertFalse(forge.template().contains("{branch}"), forge + " must never point at a branch");
        }
    }

    @Test
    void aBranchNameIsRefusedWhereACommitIsExpected() {
        RawUrls.Target target = RawUrls.resolve("https://github.com/example-owner/example-repo", "", "");

        assertThrows(IllegalArgumentException.class, () -> target.pinned("main", "plate.png"));
        assertThrows(IllegalArgumentException.class, () -> target.pinned("release/2026", "plate.png"));
        assertThrows(IllegalArgumentException.class, () -> target.pinned("", "plate.png"));
        assertThrows(IllegalArgumentException.class, () -> target.pinned(null, "plate.png"));
    }

    @Test
    void aMissingPathIsRefused() {
        RawUrls.Target target = RawUrls.resolve("https://github.com/example-owner/example-repo", "", "");

        assertThrows(IllegalArgumentException.class, () -> target.pinned(COMMIT, null));
        assertThrows(IllegalArgumentException.class, () -> target.pinned(COMMIT, "  "));
    }

    @Test
    void repositoryPathsAreNormalisedRatherThanDoublingUpSlashes() {
        assertEquals("https://raw.githubusercontent.com/example-owner/example-repo/" + COMMIT + "/signs/plate.png",
                url("https://github.com/example-owner/example-repo", "/signs/plate.png"));
        assertEquals("https://raw.githubusercontent.com/example-owner/example-repo/" + COMMIT + "/signs/plate.png",
                url("https://github.com/example-owner/example-repo", "signs\\plate.png"));
    }

    @Test
    void hostsAreMatchedWithoutCaringAboutCase() {
        assertEquals(RawUrls.Forge.GITHUB,
                RawUrls.resolve("https://GitHub.com/Example-Owner/Example-Repo.git", "", "").forge());
        assertEquals("https://raw.githubusercontent.com/Example-Owner/Example-Repo/" + COMMIT + "/plate.png",
                url("https://GitHub.com/Example-Owner/Example-Repo.git", "plate.png"));
    }

    @Test
    void describesTheHostTheUrlWillActuallyBeFetchedFrom() {
        assertEquals("GitHub, raw.githubusercontent.com",
                RawUrls.resolve("https://github.com/example-owner/example-repo", "", "").describe());
        assertEquals("GitLab, gitlab.com",
                RawUrls.resolve("https://gitlab.com/example-owner/example-repo", "", "").describe());
        assertEquals("guessed Gitea or Forgejo, git.example.com",
                RawUrls.resolve("https://git.example.com/example-owner/example-repo", "", "").describe());
        assertEquals("custom template, mirror.example.com",
                RawUrls.resolve("https://github.com/example-owner/example-repo", "",
                        "https://mirror.example.com/{slug}/{commit}/{path}").describe());
    }

    @Test
    void readsTheOriginOutOfEveryRemoteFormGitAccepts() {
        assertEquals("https://github.com", RawUrls.parseOrigin("https://github.com/example-owner/example-repo.git"));
        assertEquals("https://github.com", RawUrls.parseOrigin("git@github.com:example-owner/example-repo.git"));
        assertEquals("https://github.com", RawUrls.parseOrigin("ssh://git@github.com/example-owner/example-repo"));
        assertEquals("https://git.example.com", RawUrls.parseOrigin("git://git.example.com/example-owner/repo.git"));
        assertNull(RawUrls.parseOrigin(null));
        assertNull(RawUrls.parseOrigin("   "));
    }
}
