package dev.kierandrewett.mcmarkings.imageframe;

import dev.kierandrewett.mcmarkings.core.GridSize;

import java.util.Locale;

/**
 * Builds ImageFrame command strings, without the leading slash.
 *
 * <p>The alias is configurable because servers commonly rebind /imageframe to
 * /frame. Names are sanitised to what ImageFrame accepts as a map name.
 */
public final class ImageFrameCommands {

    /**
     * The longest command worth sending.
     *
     * <p>Vanilla's packet would carry thirty two thousand characters, and the servers
     * people actually run do not: a command past about this length is refused, and the
     * refusal is a decoder failure that closes the connection rather than an error
     * anybody sees. Somebody was thrown out of their world by one.
     */
    public static final int MAX_COMMAND_LENGTH = 256;

    /** Enough of a digest to separate two names that shortened to the same thing. */
    private static final int DISCRIMINATOR_LENGTH = 4;

    private ImageFrameCommands() {
    }

    /**
     * A map name that leaves the command short enough to send.
     *
     * <p>The command carries the name and then a URL with the same name inside it, so
     * a sign called vehicles_carrying_dangerous_goods_within_the_tunnel_restriction_
     * code_622_10_2 spends its length twice and runs past what a server will take. Two
     * hundred and twenty nine of the fourteen hundred images in the repository I have
     * in front of me did exactly that, and what it looks like in game is the button
     * doing nothing at all.
     *
     * <p>Shortened by the amount it is over rather than to a fixed size, because the
     * name is the only part of the command anybody can change: the URL is where the
     * file actually is, and the alias belongs to the server. Trimming a name to some
     * round number would throw away characters that were not in the way.
     *
     * <p>Truncating alone would let two long names collide and quietly refresh each
     * other, so a short digest of the full name goes on the end. Deterministic,
     * because the same image has to produce the same name every time or refreshing it
     * later would make a second map instead.
     *
     * <p>Blank when even an empty name would not fit, which means the URL alone is too
     * long and no name can rescue it. The caller has to say so rather than send it.
     */
    public static String fitName(String alias, String name, String url, GridSize grid) {
        String candidate = sanitiseName(name);
        int over = create(alias, candidate, url, grid).length() - MAX_COMMAND_LENGTH;
        if (over <= 0) {
            return candidate;
        }

        int keep = candidate.length() - over - DISCRIMINATOR_LENGTH - 1;
        if (keep < 1) {
            return "";
        }
        return candidate.substring(0, keep) + "_" + digestOf(candidate);
    }

    /**
     * A few stable hex characters from a name.
     *
     * <p>Only to tell two shortened names apart, so the width matters and the
     * strength does not. String's own hash is deterministic across runs and machines,
     * which is the only property being relied on here.
     */
    private static String digestOf(String name) {
        String hex = Integer.toHexString(name.hashCode());
        return hex.length() <= DISCRIMINATOR_LENGTH
                ? "0".repeat(DISCRIMINATOR_LENGTH - hex.length()) + hex
                : hex.substring(hex.length() - DISCRIMINATOR_LENGTH);
    }

    /**
     * A 1x1 grid yields a plain map item; anything larger needs the "combined"
     * keyword to come back as one placeable item rather than N loose maps.
     */
    public static String create(String alias, String name, String url, GridSize grid) {
        String base = alias + " create " + sanitiseName(name) + " " + url
                + " " + grid.columns() + " " + grid.rows();
        return grid.isSingle() ? base : base + " combined";
    }

    public static String refresh(String alias, String name, String url) {
        return alias + " refresh " + sanitiseName(name) + " " + url;
    }

    public static String get(String alias, String name, GridSize grid) {
        String base = alias + " get " + sanitiseName(name);
        return grid.isSingle() ? base : base + " combined";
    }

    /**
     * Asks the plugin to describe a map.
     *
     * <p>The only command here whose reply matters more than its effect. It prints the map's real
     * size, which is the thing this mod used to assume and get wrong.
     */
    public static String info(String alias, String name) {
        return alias + " info " + sanitiseName(name);
    }

    public static String delete(String alias, String name) {
        return alias + " delete " + sanitiseName(name);
    }

    public static String giveInvisibleFrames(String alias, boolean glowing, int amount) {
        return alias + " giveinvisibleframe " + (glowing ? "glowing" : "regular")
                + " " + Math.max(1, amount);
    }

    /**
     * Not offered anywhere, on purpose.
     *
     * <p>Every other command here is reachable from the interface. This one is not,
     * because I do not know precisely what ImageFrame does with it, and a button
     * whose label is a guess about somebody else's plugin is worse than no button.
     * Kept rather than deleted because the command name is the part that took
     * finding, and wiring it up is easy once its behaviour is known.
     */
    public static String select(String alias) {
        return alias + " select";
    }

    /** ImageFrame map names are identifier-ish; keep to lowercase word characters. */
    public static String sanitiseName(String name) {
        String cleaned = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return cleaned.isBlank() ? "untitled" : cleaned;
    }
}
