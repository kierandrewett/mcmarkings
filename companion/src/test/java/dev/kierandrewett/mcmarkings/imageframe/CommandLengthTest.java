package dev.kierandrewett.mcmarkings.imageframe;

import dev.kierandrewett.mcmarkings.core.GridSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No command goes out longer than a server will take.
 *
 * <p>What an oversized one costs is not an error. The server fails to decode the
 * packet and closes the connection, so the first anybody knows is being thrown out of
 * their world, and the second is that the mod looks like it did nothing.
 *
 * <p>The command carries the map name and then a URL with the same name inside it, so
 * a long name is spent twice. Two hundred and twenty nine of the fourteen hundred
 * images in the sign repository produced a command past the limit, which is one in
 * six, so this was not an edge case waiting to be found.
 *
 * <p>The first fix I reached for was shortening the commit in the URL, which takes
 * those two hundred and twenty nine down to ten and solves nothing: a longer name puts
 * it straight back. The name is the only part anybody can change, so the name is what
 * gives, by exactly the amount it is over.
 */
class CommandLengthTest {

    private static final String ALIAS = "imageframe";

    /** The longest name in the repository this was written against. */
    private static final String LONGEST =
            "vehicles_carrying_dangerous_goods_within_the_tunnel_restriction_code_622_10_2";

    private static String urlFor(String name) {
        return "https://raw.githubusercontent.com/kierandrewett/mcmarkings/"
                + "91e1d00457d2e61cdac1dfed5b43027b9fec9e5d/signs/" + name + ".png";
    }

    @Test
    @DisplayName("a name that already fits is left exactly as it was")
    void shortNamesAreUntouched() {
        String fitted = ImageFrameCommands.fitName(ALIAS, "no_entry", urlFor("no_entry"),
                new GridSize(2, 2));

        assertEquals("no_entry", fitted,
                "a name that fits must not be rewritten, or every existing map would be "
                        + "renamed and refreshing one would make a second");
    }

    @Test
    @DisplayName("the longest real name is brought under the limit")
    void longNamesAreBroughtUnder() {
        String url = urlFor(LONGEST);
        assertTrue(ImageFrameCommands.create(ALIAS, LONGEST, url, new GridSize(2, 2)).length()
                        > ImageFrameCommands.MAX_COMMAND_LENGTH,
                "this name is supposed to be one that does not fit, so the test below proves "
                        + "nothing if it does");

        String fitted = ImageFrameCommands.fitName(ALIAS, LONGEST, url, new GridSize(2, 2));
        int length = ImageFrameCommands.create(ALIAS, fitted, url, new GridSize(2, 2)).length();

        assertTrue(length <= ImageFrameCommands.MAX_COMMAND_LENGTH,
                "still " + length + " characters after fitting, so the server would drop the "
                        + "connection rather than place the sign");
        assertTrue(fitted.length() < LONGEST.length(), "nothing was actually shortened");
    }

    /**
     * Two long names that start the same must not end up as one name.
     *
     * <p>Truncating alone would do that, and the consequence is not a clash anybody
     * sees: the second one refreshes the first, so a sign on a wall silently becomes a
     * different sign. This repository is full of names that agree for fifty characters
     * and differ at the end, which is exactly the shape that breaks.
     */
    @Test
    @DisplayName("two long names that share a prefix stay different")
    void similarLongNamesDoNotCollide() {
        String first = "vehicles_carrying_dangerous_goods_within_the_tunnel_restriction_code_622_10_1";
        String second = "vehicles_carrying_dangerous_goods_within_the_tunnel_restriction_code_622_10_2";

        String fittedFirst = ImageFrameCommands.fitName(ALIAS, first, urlFor(first), new GridSize(2, 2));
        String fittedSecond = ImageFrameCommands.fitName(ALIAS, second, urlFor(second), new GridSize(2, 2));

        assertNotEquals(fittedFirst, fittedSecond,
                "both shortened to the same name, so placing the second would refresh the first "
                        + "and the sign already on the wall would quietly become the other one");
    }

    /** The same image must shorten the same way every time, or refresh makes a second map. */
    @Test
    @DisplayName("shortening is stable across calls")
    void shorteningIsDeterministic() {
        String url = urlFor(LONGEST);
        assertEquals(ImageFrameCommands.fitName(ALIAS, LONGEST, url, new GridSize(2, 2)),
                ImageFrameCommands.fitName(ALIAS, LONGEST, url, new GridSize(2, 2)),
                "a name that changes between calls would refresh nothing and create a new map "
                        + "every time");
    }

    /**
     * And when no name could help, it says so rather than inventing one.
     *
     * <p>A URL longer than the whole limit is where the file lives rather than what it
     * is called, and no amount of shortening reaches it. Blank, so the caller has to
     * decide what to tell somebody instead of sending a command that cannot work.
     */
    @Test
    @DisplayName("a URL too long on its own returns nothing to fit")
    void animpossibleUrlGivesUp() {
        String enormous = "https://example.com/" + "x".repeat(ImageFrameCommands.MAX_COMMAND_LENGTH);

        assertEquals("", ImageFrameCommands.fitName(ALIAS, "sign", enormous, new GridSize(1, 1)),
                "there is no name short enough here, and pretending otherwise sends a command "
                        + "the server drops the connection over");
    }
}
