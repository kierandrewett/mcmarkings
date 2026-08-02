package dev.kierandrewett.mcmarkings.core;

import java.time.Duration;

/**
 * How long ago something happened, in words.
 *
 * <p>The map registry has recorded when every sign was placed since the beginning
 * and nothing ever showed it. In the one panel whose job is finding something you
 * made earlier, "yesterday" is the most useful thing on the row: it is how people
 * actually remember their own work, far better than a name they picked in a hurry.
 *
 * <p>Words rather than a timestamp. A date and time is precise and answers a
 * question nobody asked; the question is "is this the one I did this morning".
 * Deliberately coarse for the same reason, since "3 days ago" and "3 days and four
 * hours ago" are the same fact.
 *
 * <p>Takes the current time rather than reading a clock, so it can be tested and so
 * a caller redrawing sixty times a second is not asking the system for the time
 * sixty times a second.
 */
public final class RelativeTime {

    private RelativeTime() {
    }

    /**
     * @param thenMillis when it happened
     * @param nowMillis  the current time
     */
    public static String describe(long thenMillis, long nowMillis) {
        if (thenMillis <= 0) {
            // Entries written before the registry recorded a time. Saying "just now"
            // would be a lie and "55 years ago" would be alarming.
            return "at some point";
        }

        Duration since = Duration.ofMillis(nowMillis - thenMillis);
        if (since.isNegative()) {
            // A clock change, or a registry copied from another machine. Not worth an
            // error, and "in the future" is at least honest about being odd.
            return "just now";
        }

        long minutes = since.toMinutes();
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return plural(minutes, "minute") + " ago";
        }

        long hours = since.toHours();
        if (hours < 24) {
            return plural(hours, "hour") + " ago";
        }

        long days = since.toDays();
        if (days == 1) {
            return "yesterday";
        }
        if (days < 7) {
            return days + " days ago";
        }
        if (days < 31) {
            long weeks = days / 7;
            return plural(weeks, "week") + " ago";
        }
        if (days < 365) {
            long months = days / 30;
            return plural(months, "month") + " ago";
        }

        long years = days / 365;
        return plural(years, "year") + " ago";
    }

    private static String plural(long count, String unit) {
        return count + " " + unit + (count == 1 ? "" : "s");
    }
}
