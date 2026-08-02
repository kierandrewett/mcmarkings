package dev.kierandrewett.mcmarkings.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    @DisplayName("a shortcut claimed in two registries is reported")
    void clashesAcrossRegistriesAreFound() {
        // The case that actually happens. The window and the visible tab each keep
        // their own commands, keys go to the window first, and a tab command sharing
        // a shortcut with a window one silently never fires.
        CommandRegistry window = new CommandRegistry();
        window.register(Command.of("window.palette", "Palette")
                .shortcut(Shortcut.control('P')).does(() -> { }));

        CommandRegistry panel = new CommandRegistry();
        panel.register(Command.of("panel.paste", "Paste")
                .shortcut(Shortcut.control('P')).does(() -> { }));

        List<List<Command>> clashes = CommandRegistry.conflictsAcross(List.of(window, panel));

        assertEquals(1, clashes.size());
        assertEquals(List.of("window.palette", "panel.paste"),
                clashes.getFirst().stream().map(Command::id).toList(),
                "in dispatch order, so the first is the one that wins");
    }

    @Test
    @DisplayName("the same key with different modifiers is not a clash")
    void modifiersArePartOfTheKey() {
        CommandRegistry first = new CommandRegistry();
        first.register(Command.of("a", "A").shortcut(Shortcut.control('Z')).does(() -> { }));

        CommandRegistry second = new CommandRegistry();
        second.register(Command.of("b", "B").shortcut(Shortcut.controlShift('Z')).does(() -> { }));

        assertTrue(CommandRegistry.conflictsAcross(List.of(first, second)).isEmpty(),
                "undo and redo would otherwise be reported as a clash");
    }

    @Test
    @DisplayName("a panel with no commands of its own is normal, not a failure")
    void nullRegistriesAreSkipped() {
        CommandRegistry window = new CommandRegistry();
        window.register(Command.of("a", "A").shortcut(Shortcut.control('P')).does(() -> { }));

        List<CommandRegistry> registries = new java.util.ArrayList<>();
        registries.add(window);
        registries.add(null);

        assertTrue(CommandRegistry.conflictsAcross(registries).isEmpty());
    }

    @Test
    @DisplayName("commands without shortcuts never clash")
    void shortcutlessCommandsAreIgnored() {
        CommandRegistry first = new CommandRegistry();
        first.register(Command.of("a", "A").does(() -> { }));

        CommandRegistry second = new CommandRegistry();
        second.register(Command.of("b", "B").does(() -> { }));

        assertTrue(CommandRegistry.conflictsAcross(List.of(first, second)).isEmpty());
    }

    @Test
    @DisplayName("three registries claiming one key report all three")
    void everyClaimantIsReported() {
        // Reporting only the first two would leave someone fixing it twice.
        List<CommandRegistry> registries = List.of(
                new CommandRegistry(), new CommandRegistry(), new CommandRegistry());
        for (int index = 0; index < registries.size(); index++) {
            registries.get(index).register(
                    Command.of("r" + index, "R").shortcut(Shortcut.control('G')).does(() -> { }));
        }

        assertEquals(3, CommandRegistry.conflictsAcross(registries).getFirst().size());
    }

    @Test
    @DisplayName("registering the same id twice is refused, not silently obeyed")
    void duplicateIdsAreRejected() {
        // It used to be a bare put, so the first command simply disappeared. A missing
        // palette entry with no explanation is a miserable thing to track down.
        CommandRegistry registry = new CommandRegistry();
        registry.register(Command.of("editor.delete", "Delete").does(() -> { }));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> registry.register(Command.of("editor.delete", "Remove").does(() -> { })));

        assertTrue(failure.getMessage().contains("Delete") && failure.getMessage().contains("Remove"),
                "the message should name both so the duplicate is findable: " + failure.getMessage());
    }

    @Test
    @DisplayName("replace is how you deliberately swap one out")
    void replaceIsTheIntentionalRoute() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(Command.of("a", "First").does(() -> { }));
        registry.replace(Command.of("a", "Second").does(() -> { }));

        assertEquals("Second", registry.byId("a").orElseThrow().label());
        assertEquals(1, registry.all().size());
    }
}
