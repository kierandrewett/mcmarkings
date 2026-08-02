package dev.kierandrewett.mcmarkings.gui.imgui.panel;

import dev.kierandrewett.mcmarkings.command.Command;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the palette shares a short list between the window and the visible tab.
 *
 * <p>The failure this guards against is invisible in a running palette: a command
 * that was pushed out looks exactly like a command that does not exist, so someone
 * concludes the tool cannot do the thing and stops looking. Filling the list from
 * the window first is the obvious implementation and is exactly that bug.
 */
class CommandPaletteTest {

    private static List<Command> commands(String prefix, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> Command.of(prefix + index, prefix + index).does(() -> { }))
                .toList();
    }

    private static List<String> idsOf(List<Command> commands) {
        return commands.stream().map(Command::id).toList();
    }

    @Test
    @DisplayName("everything is shown when it all fits")
    void everythingFits() {
        List<Command> shown = CommandPalette.interleave(
                List.of(commands("w", 2), commands("p", 2)), 10);

        assertEquals(4, shown.size());
        assertTrue(idsOf(shown).containsAll(List.of("w0", "w1", "p0", "p1")));
    }

    @Test
    @DisplayName("the visible tab is never pushed out by the window")
    void theTabAlwaysGetsAShare() {
        // The window has about ten commands. Filling from it first and stopping means
        // a short query shows none of the editor's at all.
        List<Command> shown = CommandPalette.interleave(
                List.of(commands("window", 20), commands("panel", 20)), 6);

        assertEquals(3, idsOf(shown).stream().filter(id -> id.startsWith("panel")).count(),
                "half the slots should belong to the tab");
    }

    @Test
    @DisplayName("the window still comes first within a round")
    void windowKeepsItsPlace() {
        List<Command> shown = CommandPalette.interleave(
                List.of(commands("w", 3), commands("p", 3)), 4);

        assertEquals(List.of("w0", "p0", "w1", "p1"), idsOf(shown));
    }

    @Test
    @DisplayName("a short list does not stop a longer one filling the rest")
    void oneShortListDoesNotStarveTheOther() {
        // A tab with two commands should not leave four slots empty.
        List<Command> shown = CommandPalette.interleave(
                List.of(commands("w", 10), commands("p", 2)), 6);

        assertEquals(6, shown.size());
        assertEquals(4, idsOf(shown).stream().filter(id -> id.startsWith("w")).count());
    }

    @Test
    @DisplayName("the limit is never exceeded")
    void limitIsRespected() {
        assertEquals(5, CommandPalette.interleave(
                List.of(commands("a", 40), commands("b", 40), commands("c", 40)), 5).size());
    }

    @Test
    @DisplayName("no registries, or empty ones, come back empty rather than looping")
    void degenerateInputsTerminate() {
        assertTrue(CommandPalette.interleave(List.of(), 10).isEmpty());
        assertTrue(CommandPalette.interleave(List.of(List.of(), List.of()), 10).isEmpty());
    }

    @Test
    @DisplayName("a limit of zero shows nothing rather than everything")
    void zeroLimit() {
        assertTrue(CommandPalette.interleave(List.of(commands("w", 5)), 0).isEmpty());
    }
}
