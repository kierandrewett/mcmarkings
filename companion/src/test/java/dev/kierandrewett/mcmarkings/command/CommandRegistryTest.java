package dev.kierandrewett.mcmarkings.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Commands, shortcuts and the palette.
 *
 * <p>Routing every action through one place is what keeps a button, a shortcut and
 * the palette from drifting apart, and what makes the whole tool reachable without
 * a mouse. These pin the behaviour that decides whether that feels fast or fussy.
 */
class CommandRegistryTest {

    private static final int KEY_Z = 'Z';
    private static final int KEY_D = 'D';
    private static final int KEY_DELETE = 261;

    private static Command noop(String id, String label) {
        return Command.of(id, label).does(() -> {
        });
    }

    @Test
    @DisplayName("a shortcut runs its command and reports that it was handled")
    void shortcutRunsCommand() {
        AtomicBoolean ran = new AtomicBoolean();
        CommandRegistry registry = new CommandRegistry();
        registry.register(Command.of("edit.undo", "Undo")
                .shortcut(Shortcut.control(KEY_Z))
                .does(() -> ran.set(true)));

        assertTrue(registry.handleKey(KEY_Z, true, false, false));
        assertTrue(ran.get());
    }

    @Test
    @DisplayName("modifiers must match exactly, so undo and redo do not collide")
    void modifiersDistinguishBindings() {
        AtomicInteger undo = new AtomicInteger();
        AtomicInteger redo = new AtomicInteger();
        CommandRegistry registry = new CommandRegistry();
        registry.register(Command.of("edit.undo", "Undo")
                .shortcut(Shortcut.control(KEY_Z)).does(undo::incrementAndGet));
        registry.register(Command.of("edit.redo", "Redo")
                .shortcut(Shortcut.controlShift(KEY_Z)).does(redo::incrementAndGet));

        registry.handleKey(KEY_Z, true, false, false);
        registry.handleKey(KEY_Z, true, true, false);

        assertEquals(1, undo.get());
        assertEquals(1, redo.get());
    }

    @Test
    @DisplayName("a disabled shortcut falls through rather than swallowing the key")
    void disabledCommandsDoNotConsumeTheKey() {
        AtomicBoolean ran = new AtomicBoolean();
        CommandRegistry registry = new CommandRegistry();
        registry.register(Command.of("layer.delete", "Delete layer")
                .shortcut(Shortcut.of(KEY_DELETE))
                .enabledWhen(() -> false)
                .does(() -> ran.set(true)));

        // Appearing broken is worse than doing nothing, so the key stays available
        // to whatever else might want it.
        assertFalse(registry.handleKey(KEY_DELETE, false, false, false));
        assertFalse(ran.get());
    }

    @Test
    @DisplayName("running a disabled command directly is refused")
    void disabledCommandsWillNotRun() {
        AtomicBoolean ran = new AtomicBoolean();
        Command command = Command.of("x", "X").enabledWhen(() -> false).does(() -> ran.set(true));

        assertFalse(command.run());
        assertFalse(ran.get());
    }

    @Test
    @DisplayName("the palette matches a subsequence, the way people type at palettes")
    void paletteMatchesSubsequences() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(noop("layer.front", "Bring to front"));
        registry.register(noop("layer.back", "Send to back"));

        List<Command> results = registry.search("brf", 10);

        assertFalse(results.isEmpty());
        assertEquals("Bring to front", results.getFirst().label());
    }

    @Test
    @DisplayName("a label prefix outranks a match buried in the description")
    void betterMatchesRankFirst() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(Command.of("file.export", "Export image")
                .hint("write a group of layers out").does(() -> {
                }));
        registry.register(noop("layer.group", "Group layers"));

        List<Command> results = registry.search("group", 10);

        assertEquals("Group layers", results.getFirst().label(),
                "the command actually named group should win");
    }

    @Test
    @DisplayName("unavailable commands sort below available ones")
    void enabledCommandsComeFirst() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(Command.of("a.off", "Delete selection")
                .enabledWhen(() -> false).does(() -> {
                }));
        registry.register(noop("a.on", "Delete layer"));

        List<Command> results = registry.search("delete", 10);

        assertEquals("Delete layer", results.getFirst().label());
    }

    @Test
    @DisplayName("an empty query lists everything, so the palette teaches what exists")
    void emptyQueryListsEverything() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(noop("a", "Alpha"));
        registry.register(noop("b", "Beta"));

        assertEquals(2, registry.search("", 10).size());
        assertEquals(1, registry.search("", 1).size(), "the limit is respected");
    }

    @Test
    @DisplayName("colliding bindings are reported rather than silently ignored")
    void conflictsAreDetected() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(Command.of("one", "One").shortcut(Shortcut.control(KEY_D)).does(() -> {
        }));
        registry.register(Command.of("two", "Two").shortcut(Shortcut.control(KEY_D)).does(() -> {
        }));
        registry.register(noop("three", "Three"));

        List<List<Command>> conflicts = registry.conflicts();

        assertEquals(1, conflicts.size());
        assertEquals(2, conflicts.getFirst().size());
    }

    @Test
    @DisplayName("re-registering an id replaces it, so a binding can be changed")
    void reregisteringReplaces() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(Command.of("edit.undo", "Undo").shortcut(Shortcut.control(KEY_Z)).does(() -> {
        }));
        registry.replace(Command.of("edit.undo", "Undo").shortcut(Shortcut.control(KEY_D)).does(() -> {
        }));

        assertEquals(1, registry.all().size());
        assertTrue(registry.forKey(KEY_D, true, false, false).isPresent());
        assertTrue(registry.forKey(KEY_Z, true, false, false).isEmpty());
    }

    @Test
    @DisplayName("shortcuts read the same way everywhere they are shown")
    void shortcutsDisplayConsistently() {
        assertEquals("Ctrl+Z", Shortcut.control(KEY_Z).display());
        assertEquals("Ctrl+Shift+Z", Shortcut.controlShift(KEY_Z).display());
        assertEquals("Delete", Shortcut.of(KEY_DELETE).display());

        // Modifier order is fixed rather than following construction order.
        assertEquals("Ctrl+Shift+Alt+D", new Shortcut(KEY_D, true, true, true).display());
    }

    @Test
    @DisplayName("a written shortcut parses back, for rebinding stored in config")
    void shortcutsRoundTrip() {
        for (Shortcut shortcut : List.of(
                Shortcut.control(KEY_Z),
                Shortcut.controlShift(KEY_Z),
                Shortcut.of(KEY_DELETE),
                new Shortcut(265, false, false, true))) {
            assertEquals(shortcut, Shortcut.parse(shortcut.display()).orElseThrow(),
                    "failed to round-trip " + shortcut.display());
        }
    }

    @Test
    @DisplayName("shortcut parsing is forgiving about how it is written")
    void shortcutParsingIsLenient() {
        assertEquals(Shortcut.control(KEY_Z), Shortcut.parse("ctrl+z").orElseThrow());
        assertEquals(Shortcut.control(KEY_Z), Shortcut.parse("Command + Z").orElseThrow());
        assertTrue(Shortcut.parse("").isEmpty());
        assertTrue(Shortcut.parse("Ctrl+").isEmpty(), "a modifier with no key is not a shortcut");
    }

    @Test
    @DisplayName("shortcuts are keyed on the physical key, not the typed character")
    void shortcutsAreLayoutIndependent() {
        // Matching on the character would break the moment someone is not on QWERTY.
        Shortcut shortcut = Shortcut.control(KEY_Z);

        assertTrue(shortcut.matches(KEY_Z, true, false, false));
        assertFalse(shortcut.matches('z', true, false, false),
                "lowercase is a different code; GLFW reports the uppercase one");
    }
}
