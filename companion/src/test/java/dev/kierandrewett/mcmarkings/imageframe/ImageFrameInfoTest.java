package dev.kierandrewett.mcmarkings.imageframe;

import dev.kierandrewett.mcmarkings.core.GridSize;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a map's real size out of what the plugin says about it.
 *
 * <p>Every line below is copied from a real reply rather than written from the shape of the code,
 * because the code is mine and the reply is not, and a parser tested against its own author's idea
 * of the input proves only that the idea is self-consistent.
 */
class ImageFrameInfoTest {

    /** Exactly what the server printed, in order. */
    private static final List<String> REPLY = List.of(
            "Image ID: 25",
            "Name: give_way",
            "Map Size: 3 x 4",
            "Dithering: nearest-color",
            "Creator: FirefoxBrowser (16733106-637a-4309-885a-c5e7ddbe9cb9)",
            "Access: []",
            "Time Created: 31/07/2026 17:57:48 UTC",
            "Markers: []",
            "URL: https://raw.githubusercontent.com/kierandrewett/mcmarkings/refs/heads/main/give_way.png");

    private static final class Recorder implements CommandSink {
        private final List<String> sent = new ArrayList<>();

        @Override
        public void send(String command) {
            sent.add(command);
        }

        @Override
        public void sendAll(List<String> commands) {
            sent.addAll(commands);
        }

        @Override
        public int pending() {
            return 0;
        }

        @Override
        public void setCommandsPerSecond(double rate) {
        }

        @Override
        public void clear() {
            sent.clear();
        }
    }

    private Recorder commands;

    @BeforeEach
    void reset() {
        ImageFrameInfo.reset();
        commands = new Recorder();
    }

    /** Feeds the reply in and returns which lines the player would still have seen. */
    private List<String> feed(List<String> lines, long at) {
        List<String> shown = new ArrayList<>();
        for (String line : lines) {
            if (ImageFrameInfo.read(line, at)) {
                shown.add(line);
            }
        }
        return shown;
    }

    @Test
    @DisplayName("the real size comes out of a real reply")
    void readsTheSizeTheServerReports() {
        ImageFrameInfo.request(commands, "imageframe", "give_way", 1_000L);
        assertEquals(List.of("imageframe info give_way"), commands.sent);

        feed(REPLY, 1_100L);

        ImageFrameInfo.Details details = ImageFrameInfo.known("give_way").orElseThrow();
        assertEquals(new GridSize(3, 4), details.grid(),
                "the reply says 3 x 4 and that is what the map is, whatever anybody asked for");
        assertEquals("give_way", details.name());
        assertEquals("25", details.id());
        assertTrue(details.url().endsWith("give_way.png"), "got " + details.url());
    }

    @Test
    @DisplayName("the whole reply is kept out of chat")
    void theReplyIsSwallowed() {
        ImageFrameInfo.request(commands, "imageframe", "give_way", 1_000L);

        assertEquals(List.of(), feed(REPLY, 1_100L),
                "clicking through a browser of fourteen hundred signs would put nine lines a time "
                        + "into the chat log");
    }

    /**
     * Nothing is swallowed when nothing was asked.
     *
     * <p>The failure worth guarding: a mod that eats server messages it did not ask for gets blamed
     * for whatever the player stops seeing, and the player has no way to know why.
     */
    @Test
    @DisplayName("an unrequested reply is left alone")
    void unrequestedLinesArePassedThrough() {
        assertEquals(REPLY, feed(REPLY, 1_000L),
                "no request was outstanding, so none of this was ours to take");
    }

    @Test
    @DisplayName("ordinary chat is never touched, even mid-request")
    void ordinaryChatSurvives() {
        ImageFrameInfo.request(commands, "imageframe", "give_way", 1_000L);

        List<String> chatter = List.of(
                "<Kieran> where did the sign go",
                "Teleporting to spawn",
                "Name is a funny thing",
                "[ImageFrame] A new version is available on SpigotMC: 2026.1.4");

        assertEquals(chatter, feed(chatter, 1_100L),
                "a request being open must not make every line in chat fair game");
    }

    /**
     * A reply about another map is still worth keeping.
     *
     * <p>It is hidden, because whether a line belongs to this mod cannot be known until the second
     * line of the reply and the first has already had to be shown or hidden. What it must not do is
     * throw the answer away: a server volunteering a map's size is the server's answer however the
     * question came to be put, and that is the whole reason for asking.
     */
    @Test
    @DisplayName("a reply about a different map is still recorded")
    void anotherMapsReplyIsRecorded() {
        ImageFrameInfo.request(commands, "imageframe", "give_way", 1_000L);

        List<String> other = new ArrayList<>(REPLY);
        other.set(1, "Name: no_entry");
        feed(other, 1_100L);

        assertEquals(new GridSize(3, 4), ImageFrameInfo.known("no_entry").orElseThrow().grid(),
                "the server said how big no_entry is and that is worth knowing");
    }

    /**
     * Suppression stops when the wait does.
     *
     * <p>Otherwise a request the plugin never answers leaves this quietly eating anything that
     * happens to look like a reply for the rest of the session.
     */
    @Test
    @DisplayName("a request that is never answered stops swallowing")
    void suppressionExpires() {
        ImageFrameInfo.request(commands, "imageframe", "give_way", 1_000L);

        assertFalse(ImageFrameInfo.waitingFor("give_way", 100_000L), "the wait should be over");
        assertEquals(REPLY, feed(REPLY, 100_000L), "and nothing should still be being taken");
    }

    /** A size the parser cannot read must not become a confident wrong answer. */
    @Test
    @DisplayName("an unreadable size records nothing")
    void anUnreadableSizeIsNotGuessed() {
        ImageFrameInfo.request(commands, "imageframe", "give_way", 1_000L);

        List<String> odd = new ArrayList<>(REPLY);
        odd.set(2, "Map Size: enormous");
        feed(odd, 1_100L);

        assertTrue(ImageFrameInfo.known("give_way").isEmpty(),
                "a map of unknown size has to stay unknown, since the whole point of asking was to "
                        + "stop assuming");
    }
}
