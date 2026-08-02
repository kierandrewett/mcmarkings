package dev.kierandrewett.mcmarkings.core;

/**
 * Whether the checked-out branch has work the remote has not seen.
 *
 * <p>Three states rather than a boolean, because "no unpushed commits" and "nobody
 * has looked yet" are not the same answer and only one of them is safe to say out
 * loud. The flag this replaced started false and was only set once an async lookup
 * finished, so for the first second of every session, and for the whole of any
 * session where the lookup failed, the mod stayed quiet in a way that read as an
 * all clear it had not earned.
 *
 * <p>It also carried across repositories. The lookup runs per repository and sets
 * the flag inside a try, so switching to a second repository and having git fail
 * left the first repository's answer on screen.
 *
 * <p>The sentence lives here rather than at the two call sites that show it. They
 * had already drifted: one of them told you a push sends the whole branch and the
 * other said nothing at all, for the same state.
 */
public enum PushState {

    /** Not looked up yet, or the lookup failed. Say the cautious thing. */
    UNKNOWN,

    /** Everything local is on the remote, so a push sends only the new commit. */
    CLEAN,

    /** There is local work a push would send along with the new commit. */
    UNPUSHED;

    public static PushState of(boolean unpushed) {
        return unpushed ? UNPUSHED : CLEAN;
    }

    /**
     * What to add to a tooltip on a button that pushes.
     *
     * <p>Its own paragraph, because it is a different thought from what the button
     * does, and a caveat run onto the end of a sentence gets read as part of it.
     *
     * <p>The clean case is worth saying rather than leaving blank. Someone about to
     * publish is deciding whether pressing this sends a fortnight of unrelated work,
     * and silence answers that question no better than a warning would.
     */
    public String note() {
        return "\n\n" + switch (this) {
            case UNPUSHED -> "This branch has local commits the remote has not seen. Pushing sends those too.";
            case CLEAN -> "Nothing else is waiting to be pushed.";
            case UNKNOWN -> "A push sends the whole branch, not just this commit.";
        };
    }
}
