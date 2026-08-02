package dev.kierandrewett.mcmarkings.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How long ago something was placed, in words.
 *
 * <p>Mostly here for the edges. The middle of the range is obvious and the ends are
 * where a date helper embarrasses itself: an entry with no recorded time, a clock
 * that went backwards, and the boundary where "23 hours" should become "yesterday".
 */
class RelativeTimeTest {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    /**
     * A plausible "now", in milliseconds since the epoch.
     *
     * <p>Not a small round number. The first version of this used 1e9, which is about
     * eleven days after the epoch, so "15 days ago" landed before it and came back as
     * the no-recorded-time case. The test was wrong and the code was right, which is
     * the good way round but only obvious once written down.
     */
    private static final long NOW = 1_700_000_000_000L;

    private static String ago(long millis) {
        return RelativeTime.describe(NOW - millis, NOW);
    }

    @Test
    @DisplayName("recent work reads as recent")
    void recent() {
        assertEquals("just now", ago(0));
        assertEquals("just now", ago(59_000));
        assertEquals("1 minute ago", ago(MINUTE));
        assertEquals("5 minutes ago", ago(5 * MINUTE));
    }

    @Test
    @DisplayName("hours and the boundary into yesterday")
    void hoursAndDays() {
        assertEquals("1 hour ago", ago(HOUR));
        assertEquals("23 hours ago", ago(23 * HOUR));
        assertEquals("yesterday", ago(DAY), "a day is a word, not a number");
        assertEquals("3 days ago", ago(3 * DAY));
    }

    @Test
    @DisplayName("longer ago gets coarser, because precision stops helping")
    void weeksMonthsYears() {
        assertEquals("1 week ago", ago(7 * DAY));
        assertEquals("2 weeks ago", ago(15 * DAY));
        assertEquals("1 month ago", ago(31 * DAY));
        assertEquals("1 year ago", ago(400 * DAY));
        assertEquals("2 years ago", ago(800 * DAY));
    }

    @Test
    @DisplayName("singular and plural, since a row is read not parsed")
    void plurals() {
        assertEquals("1 minute ago", ago(MINUTE));
        assertEquals("2 minutes ago", ago(2 * MINUTE));
        assertEquals("1 hour ago", ago(HOUR));
        assertEquals("2 hours ago", ago(2 * HOUR));
    }

    @Test
    @DisplayName("an entry with no recorded time says so rather than lying")
    void noRecordedTime() {
        // Registry entries written before the time was recorded. "just now" would be
        // a lie and the epoch would read as 55 years ago, which is alarming.
        assertEquals("at some point", RelativeTime.describe(0, NOW));
    }

    @Test
    @DisplayName("a clock that went backwards is odd, not broken")
    void futureTimestamps() {
        // A daylight saving change, or a registry copied from another machine.
        assertEquals("just now", RelativeTime.describe(NOW + 5_000, NOW));
    }
}
