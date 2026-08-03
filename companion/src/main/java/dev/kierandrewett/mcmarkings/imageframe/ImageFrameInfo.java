package dev.kierandrewett.mcmarkings.imageframe;

import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.core.GridSize;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What ImageFrame says about a map, read out of its own reply.
 *
 * <p>Everything this mod believed about a placed map came from a file it writes itself, and it
 * writes that file when it sends a command rather than when the command works. So it could be
 * certain a map existed that had never been created, and certain a map was two frames by two when
 * the server had made it one by one, and the only symptom of either was a button appearing to do
 * nothing.
 *
 * <p>The plugin will simply say. {@code imageframe info <name>} prints the name, the map size, the
 * URL and the rest, and the size in that reply is the size the map actually is rather than the size
 * anybody intended. Asking is the difference between a guess and an answer.
 *
 * <p>The reply arrives as ordinary chat, which is no use to a player: clicking through a browser of
 * fourteen hundred signs would fill the chat log with nine lines a time. The lines are swallowed on
 * the way in, and only while a request is outstanding and only when they match the shape of a reply,
 * so an unrelated message from the server is never eaten and neither is anything at all when this
 * mod is not asking.
 */
public final class ImageFrameInfo {

    /** One map, as the server describes it. */
    public record Details(String name, GridSize grid, String url, String id) {
    }

    /**
     * How long a reply is waited for.
     *
     * <p>Generous, because it is a round trip to a server and the queue in front of it is rate
     * limited on purpose. What runs out is only the suppression: after this, lines stop being
     * swallowed and anything the plugin says lands in chat where somebody can read it, which is the
     * right way round for a request that has gone wrong.
     */
    private static final long TIMEOUT_MILLIS = 10_000;

    /** The lines of a reply, in the order the plugin prints them. */
    private static final Pattern FIELD =
            Pattern.compile("^(Image ID|Name|Map Size|Dithering|Creator|Access|Time Created|Markers|URL): ?(.*)$");

    /** "3 x 4", with or without the spaces. */
    private static final Pattern MAP_SIZE = Pattern.compile("^(\\d+)\\s*[xX]\\s*(\\d+)$");

    private static final Map<String, Details> known = new LinkedHashMap<>();

    /** Names asked about and not yet answered, with the moment each stops being waited for. */
    private static final Map<String, Long> pending = new LinkedHashMap<>();

    /** The reply being read, field by field, until its URL line closes it. */
    private static final Map<String, String> building = new LinkedHashMap<>();

    private ImageFrameInfo() {
    }

    /** Asks the server about a map. Cheap to call again; a request already in flight is kept. */
    public static synchronized void request(CommandSink commands, String alias, String name,
            long nowMillis) {
        String wanted = ImageFrameCommands.sanitiseName(name);
        if (wanted.isEmpty() || pending.containsKey(wanted)) {
            return;
        }
        pending.put(wanted, nowMillis + TIMEOUT_MILLIS);
        commands.send(ImageFrameCommands.info(alias, wanted));
    }

    /** What the server said about this map, if it has said anything. */
    public static synchronized Optional<Details> known(String name) {
        return Optional.ofNullable(known.get(ImageFrameCommands.sanitiseName(name)));
    }

    /** Whether an answer about this map is still being waited for. */
    public static synchronized boolean waitingFor(String name, long nowMillis) {
        Long deadline = pending.get(ImageFrameCommands.sanitiseName(name));
        return deadline != null && nowMillis < deadline;
    }

    /**
     * Reads one incoming line, and says whether the player should see it.
     *
     * <p>False only for a line that is part of a reply this mod asked for. Everything else is
     * passed through untouched, including the plugin's own errors: a request that failed is worth
     * seeing, and a mod that eats server messages it did not recognise would be much worse than one
     * that leaves nine lines in the chat log.
     */
    public static synchronized boolean read(String line, long nowMillis) {
        expire(nowMillis);

        Matcher field = FIELD.matcher(line.trim());
        if (!field.find()) {
            // Not shaped like a reply, so it is somebody talking. Never touched, whatever is
            // outstanding: a request being open must not make every line in chat fair game.
            return true;
        }

        // Every line of the reply, not only the one that completes it. The first version suppressed
        // the closing URL and let the other eight through, which is eight lines a click in a
        // browser of fourteen hundred signs and not what anybody meant by hiding it.
        //
        // Whether it is ours is judged by whether this mod is waiting for anything at all, because
        // the name that would say for certain arrives on the second line and the first has already
        // had to be shown or hidden by then. The cost is that running the command by hand inside
        // the few seconds after clicking a sign shows nothing, and the alternative is the log
        // filling up every time somebody browses.
        boolean asked = !pending.isEmpty();

        String key = field.group(1);
        String value = field.group(2).trim();
        building.put(key, value);

        // The URL is the last line the plugin prints, so it is what closes a reply. Waiting for it
        // rather than counting lines means a plugin version with a field more or less still works.
        if (!"URL".equals(key)) {
            return !asked;
        }

        String name = ImageFrameCommands.sanitiseName(building.getOrDefault("Name", ""));
        GridSize grid = gridFrom(building.get("Map Size"));
        String id = building.getOrDefault("Image ID", "");
        building.clear();

        // Recorded whether or not it was asked for. A server volunteering the size of a map is
        // worth keeping however the question came to be put, and it is still the server's answer
        // rather than this mod's assumption, which is the entire point.
        if (!name.isEmpty() && grid != null) {
            pending.remove(name);
            known.put(name, new Details(name, grid, value, id));
            McMarkingsCompanion.LOGGER.info("[mcmarkings] {} is {} on the server", name, grid);
        }
        return !asked;
    }

    /** Forgets what a server said, for a disconnect or after placing something new. */
    public static synchronized void forget(String name) {
        String wanted = ImageFrameCommands.sanitiseName(name);
        known.remove(wanted);
        pending.remove(wanted);
    }

    public static synchronized void reset() {
        known.clear();
        pending.clear();
        building.clear();
    }

    private static void expire(long nowMillis) {
        pending.values().removeIf(deadline -> nowMillis >= deadline);
        if (pending.isEmpty()) {
            building.clear();
        }
    }

    private static GridSize gridFrom(String value) {
        if (value == null) {
            return null;
        }
        Matcher size = MAP_SIZE.matcher(value.trim().toLowerCase(Locale.ROOT));
        if (!size.find()) {
            return null;
        }
        try {
            return new GridSize(Integer.parseInt(size.group(1)), Integer.parseInt(size.group(2)));
        } catch (RuntimeException unusable) {
            return null;
        }
    }
}
