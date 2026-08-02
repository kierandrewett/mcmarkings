package dev.kierandrewett.mcmarkings.repo;

import dev.kierandrewett.mcmarkings.core.RepoImage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What comes back first, against the real repository.
 *
 * <p>Search is the most used thing in the mod and the one where being nearly right
 * is worthless: with 1445 images, the answer is either near the top or it may as
 * well not be there. The ranking looked sound read on its own, so this was written
 * by running real queries and looking at the results rather than by reasoning about
 * the code, which is how both faults below turned up.
 *
 * <p>Against the checked-in repository on purpose. Fixtures would have agreed with
 * whatever the ranking already did; the ISO codes and the 300-yard signs that broke
 * it are only there because the real set is messy.
 */
class SearchRelevanceTest {

    private static RepoScanner scanner;

    @BeforeAll
    static void scan() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(root.resolve("signs")),
                "run from a checkout with the images present");
        scanner = new RepoScanner(root, List.of("node_modules", "build", "companion"));
        scanner.rescan();
    }

    private static List<String> namesFor(String query, int limit) {
        return scanner.search(query, limit).stream().map(RepoImage::name).toList();
    }

    private static void assertWithin(String query, int places, String expected) {
        List<String> names = namesFor(query, places);
        assertTrue(names.contains(expected),
                () -> "\"" + query + "\" should offer " + expected + " within " + places
                        + ", got " + names);
    }

    /**
     * Every speed limit, because every one of them was broken.
     *
     * <p>The whole-word rule was written for "30" and tested on "30", and it worked.
     * It did not generalise: 40, 50, 60 and 70 returned no speed limit anywhere in
     * the first five, and "60" returned the sign for stop. British diagram numbers
     * put stop at 601.1, give way at 602 and turn left at 606, and a reference that
     * merely began with the query used to rank as highly as a name that began with
     * it, so those three coincidences outranked "max speed 60 mph".
     *
     * <p>A number is the most likely thing anybody types at a set of road signs, and
     * five of the six numbers they would type were wrong. Fixing the one I happened
     * to test is what let that stand, so all six are here now.
     */
    @Test
    @DisplayName("every speed limit comes back for its own number")
    void everySpeedLimitIsFindable() {
        assertWithin("20", 5, "max_speed_20_mph");
        assertWithin("30", 5, "max_speed_30_mph");
        assertWithin("40", 5, "max_speed_40_mph_wales");
        assertWithin("50", 5, "max_speed_50_mph_wales");
        assertWithin("60", 5, "maximum_speed_limit_of_60_miles_per_hour");
        assertWithin("70", 5, "70mph_sign_sometimes_used_in_scotland_and_uk_special_roads");
    }

    /**
     * And that a diagram number typed in full still wins outright.
     *
     * <p>The fix pushes a reference that merely starts with the query below the
     * names. An exact one has to stay at the top, or somebody who knows the
     * catalogue loses the fastest way to reach a sign.
     */
    @Test
    @DisplayName("an exact diagram number still comes first")
    void anExactReferenceStillWins() {
        assertWithin("601.1", 1, "stop");
    }

    /**
     * The query that exposed all of this.
     *
     * <p>"30" is about the most likely thing anyone types at a set of British road
     * signs, and the 30 roundel did not come back at all. "30" is equally a
     * substring of the ISO codes e030 and w030 and of every 300 yards sign, so a
     * laser warning scored exactly as well as the speed limit and the alphabet
     * settled the rest.
     */
    @Test
    @DisplayName("a number finds the sign showing that number, not codes that contain it")
    void numbersFindTheSignShowingThem() {
        assertWithin("30", 3, "max_speed_30_mph");

        List<String> top = namesFor("30", 5);
        assertTrue(top.stream().noneMatch(name -> name.contains("w030") || name.contains("e030")),
                () -> "an ISO code is outranking the speed limit: " + top);
    }

    /**
     * The second fault, which the first one hid.
     *
     * <p>Everything in a rank is equally good by that rank's reckoning, so something
     * has to break the tie, and it was the path. Searching "parking" led with
     * parking_allowed_up_to_the_time_limit_specified_during because "allowed" sorts
     * before "place".
     */
    @Test
    @DisplayName("the plainest name for a thing comes before its qualified variants")
    void plainNamesComeFirst() {
        assertWithin("parking", 1, "parking_place");
        assertWithin("bus", 1, "bus_stop");
        assertWithin("roundabout", 1, "roundabout_ahead");
    }

    @Test
    @DisplayName("an exact name is the first result")
    void exactNamesWin() {
        for (String name : List.of("stop", "no_entry", "keep_left", "give_way")) {
            List<String> names = namesFor(name.replace('_', ' '), 1);
            assertTrue(names.contains(name),
                    () -> "\"" + name.replace('_', ' ') + "\" should lead with " + name + ", got " + names);
        }
    }

    @Test
    @DisplayName("every word has to match, so two words narrow rather than widen")
    void allWordsMustMatch() {
        List<String> names = namesFor("school ahead", 10);
        assertTrue(names.contains("school_ahead"), () -> "got " + names);
        assertTrue(names.stream().allMatch(name -> name.contains("school")),
                () -> "a result is missing one of the words: " + names);
    }
}
