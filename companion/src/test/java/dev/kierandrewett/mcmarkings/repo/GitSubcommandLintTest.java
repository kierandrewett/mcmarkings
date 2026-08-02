package dev.kierandrewett.mcmarkings.repo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which git subcommands this mod is allowed to run.
 *
 * <p>The service documents that it never reads or writes git configuration at any
 * scope, and the user's own rules say the same in stronger terms. That promise was
 * written in three places and checked in none, which is the state a promise is least
 * useful in: nothing stops the next change quietly adding a {@code git config
 * user.email} to make a failing commit go through.
 *
 * <p>An allowlist rather than a search for the word "config", so it fails for
 * anything new rather than only for the one mistake anticipated. Adding a subcommand
 * means adding it here, which is exactly the moment someone should have to think
 * about whether the mod ought to be running it at all.
 *
 * <p>Source-scanned rather than exercised, because the alternative is running git,
 * and the point is to catch the call before it ever runs. There is precedent: the
 * generator lint does the same for a rule that documentation alone failed to
 * enforce.
 */
class GitSubcommandLintTest {

    /**
     * Everything the mod may ask git to do.
     *
     * <p>All read-only except the three that publishing needs. None of them touches
     * configuration, and none of them can rewrite history.
     */
    private static final Set<String> ALLOWED = Set.of(
            "rev-parse",
            "remote",
            "status",
            "diff",
            "pull",
            "add",
            "commit",
            "push");

    /** {@code exec(SOME_TIMEOUT, "subcommand", ...)}, which is how every call is made. */
    private static final Pattern EXEC = Pattern.compile("exec\\(\\w+,\\s*\"([a-z][a-z-]*)\"");

    /** A command assembled into a list first, as staging paths has to be. */
    private static final Pattern LIST = Pattern.compile("List\\.of\\(\"([a-z][a-z-]*)\",\\s*\"--\"");

    @Test
    @DisplayName("no git subcommand outside the allowlist")
    void onlyAllowedSubcommands() throws IOException {
        Set<String> found = subcommandsIn(source());

        assertTrue(found.stream().allMatch(ALLOWED::contains), () -> """
                A git subcommand outside the allowlist. If this is deliberate, add it to \
                ALLOWED and say why in the commit; if it is "config" in any form, it is not \
                deliberate, because this mod must never read or write git configuration at \
                any scope.
                found: """ + found + "\nallowed: " + ALLOWED);
    }

    @Test
    @DisplayName("configuration is never touched, by any spelling")
    void configurationIsNeverTouched() throws IOException {
        String source = source();

        // "config" as a subcommand is the obvious one. "-c" is the quiet one: it sets
        // a config value for a single command, which is still deciding something that
        // belongs to whoever owns the machine.
        assertTrue(!source.contains("\"config\""), "git config must never be run");
        assertTrue(!source.contains("\"-c\""), "git -c overrides configuration for one command");
        assertTrue(!source.contains("\"--global\"") && !source.contains("\"--local\""),
                "a config scope flag has no business here");
    }

    @Test
    @DisplayName("the check would actually catch a new subcommand")
    void theCheckCatchesTheThingItExistsFor() {
        // A lint nobody has seen fail is a lint nobody should trust.
        Set<String> found = subcommandsIn("""
                exec(LOCAL_TIMEOUT, "rev-parse", "HEAD");
                exec(NETWORK_TIMEOUT, "config", "user.email", "someone@example.com");
                List.of("add", "--")
                """);

        assertTrue(found.contains("config"), "the scan missed a config call: " + found);
        assertTrue(found.contains("rev-parse") && found.contains("add"), found.toString());
        assertTrue(!ALLOWED.containsAll(found), "the allowlist should reject it");
    }

    private static Set<String> subcommandsIn(String source) {
        Set<String> found = new LinkedHashSet<>();
        for (Pattern pattern : List.of(EXEC, LIST)) {
            Matcher matcher = pattern.matcher(source);
            while (matcher.find()) {
                found.add(matcher.group(1));
            }
        }
        return found;
    }

    private static String source() throws IOException {
        Path file = Path.of("src/main/java/dev/kierandrewett/mcmarkings/repo/ProcessGitService.java");
        assertTrue(Files.isRegularFile(file), "cannot find " + file.toAbsolutePath());
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
