package dev.kierandrewett.mcmarkings.core;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The state this replaced was a boolean that started false, so it claimed a clean
 * branch before anything had looked and after any lookup that failed. These pin the
 * distinction, since it is invisible in a running game: a wrong answer here looks
 * exactly like a right one until a push sends a fortnight of unrelated work.
 */
class PushStateTest {

    @Test
    void unknownIsNotTheSameAnswerAsClean() {
        assertNotEquals(PushState.UNKNOWN.note(), PushState.CLEAN.note());

        // The specific failure worth naming: not having looked must never read as an
        // all clear, because that is what the old boolean did for free.
        assertFalse(PushState.UNKNOWN.note().toLowerCase().contains("nothing else"),
                PushState.UNKNOWN.note());
    }

    @Test
    void warnsOnlyWhenThereIsSomethingToWarnAbout() {
        assertTrue(PushState.UNPUSHED.note().contains("has not seen"), PushState.UNPUSHED.note());
        assertFalse(PushState.CLEAN.note().contains("has not seen"), PushState.CLEAN.note());
        assertFalse(PushState.UNKNOWN.note().contains("has not seen"), PushState.UNKNOWN.note());
    }

    @Test
    void everyStateSaysSomething() {
        // A tooltip that silently loses its last paragraph for one state is how the
        // two call sites drifted in the first place.
        for (PushState state : EnumSet.allOf(PushState.class)) {
            assertTrue(state.note().startsWith("\n\n"), state + " must stand as its own paragraph");
            assertTrue(state.note().strip().length() > 20, state + " says too little: " + state.note());
            assertTrue(state.note().strip().endsWith("."), state + " must be a sentence");
        }
    }

    @Test
    void mapsTheGitAnswerRoundTheRightWay() {
        assertEquals(PushState.UNPUSHED, PushState.of(true));
        assertEquals(PushState.CLEAN, PushState.of(false));
    }
}
