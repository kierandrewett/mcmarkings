package dev.kierandrewett.mcmarkings.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Everything the user can do, in one place.
 *
 * <p>Holding them together is what lets a palette exist at all, and it makes the
 * keyboard the fastest way to work rather than a second-class one. Registration
 * order is preserved so a category reads in the order it was written rather than
 * alphabetically, which usually matches how someone thinks about it.
 */
public final class CommandRegistry {

    private final Map<String, Command> byId = new LinkedHashMap<>();

    /**
     * Adds a command, refusing to quietly replace one.
     *
     * <p>It was a bare put, so registering the same id twice made the first command
     * vanish. Ids are built by hand and several are assembled from loops, so a copied
     * line or an enum gaining a value that collides is an ordinary mistake, and the
     * symptom would be a command missing from the palette with nothing anywhere
     * saying why.
     *
     * <p>Loud rather than logged, unlike a shortcut clash. That is a design decision
     * two features disagree about; this is a bug, and it is worth failing where it
     * happens rather than being found later by whoever notices something absent.
     * {@link #replace} is there for deliberately swapping one out.
     */
    public void register(Command command) {
        Command existing = byId.putIfAbsent(command.id(), command);
        if (existing != null) {
            throw new IllegalArgumentException("two commands share the id \"" + command.id()
                    + "\": \"" + existing.label() + "\" and \"" + command.label()
                    + "\". Use replace() if the second is meant to win.");
        }
    }

    public void registerAll(List<Command> commands) {
        commands.forEach(this::register);
    }

    /** Replaces any command with the same id, for rebinding at runtime. */
    /**
     * Swaps a command out, or adds it if there is none.
     *
     * <p>Does the put itself rather than going through {@link #register}, which now
     * refuses a duplicate. The two used to be the same call, which is why registering
     * twice by accident looked exactly like changing a binding on purpose.
     */
    public void replace(Command command) {
        byId.put(command.id(), command);
    }

    public Optional<Command> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<Command> all() {
        return List.copyOf(byId.values());
    }

    /**
     * The command bound to a key press, if any.
     *
     * <p>Disabled commands are skipped rather than swallowing the key, so a
     * shortcut that does not currently apply falls through to whatever else wants
     * it instead of appearing broken.
     */
    public Optional<Command> forKey(int keyCode, boolean control, boolean shift, boolean alt) {
        for (Command command : byId.values()) {
            Shortcut shortcut = command.shortcut();
            if (shortcut != null && shortcut.matches(keyCode, control, shift, alt) && command.isEnabled()) {
                return Optional.of(command);
            }
        }
        return Optional.empty();
    }

    /** Runs whatever is bound to a key press. True when something handled it. */
    public boolean handleKey(int keyCode, boolean control, boolean shift, boolean alt) {
        return forKey(keyCode, control, shift, alt).map(Command::run).orElse(false);
    }

    /**
     * Palette search.
     *
     * <p>Matches a subsequence rather than a substring, so "brf" finds "Bring to
     * front" the way every command palette people already use behaves. Results are
     * ranked so a prefix of the label beats a match buried in the hint, and
     * available commands come before unavailable ones.
     */
    public List<Command> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return all().stream()
                    .sorted(Comparator.comparing((Command command) -> !command.isEnabled()))
                    .limit(limit)
                    .toList();
        }

        String needle = query.toLowerCase(Locale.ROOT).replace(" ", "");

        List<Ranked> matches = new ArrayList<>();
        for (Command command : byId.values()) {
            int score = score(command, needle);
            if (score >= 0) {
                matches.add(new Ranked(command, score));
            }
        }

        matches.sort(Comparator
                .comparing((Ranked ranked) -> !ranked.command().isEnabled())
                .thenComparingInt(Ranked::score)
                .thenComparing(ranked -> ranked.command().label()));

        return matches.stream().limit(limit).map(Ranked::command).toList();
    }

    /** Lower is better. Negative means no match at all. */
    private static int score(Command command, String needle) {
        String label = command.label().toLowerCase(Locale.ROOT);

        if (label.replace(" ", "").startsWith(needle)) {
            return 0;
        }
        if (label.contains(needle)) {
            return 1;
        }
        if (isSubsequence(needle, label.replace(" ", ""))) {
            return 2;
        }
        if (command.searchKey().contains(needle)) {
            return 3;
        }
        return -1;
    }

    private static boolean isSubsequence(String needle, String haystack) {
        int position = 0;
        for (int index = 0; index < haystack.length() && position < needle.length(); index++) {
            if (haystack.charAt(index) == needle.charAt(position)) {
                position++;
            }
        }
        return position == needle.length();
    }

    /**
     * Bindings that collide, so a settings screen can point them out.
     *
     * <p>Worth surfacing rather than silently letting the first registered win:
     * a shortcut that does nothing because something else claimed it is baffling
     * from the outside.
     */
    public List<List<Command>> conflicts() {
        return conflictsAcross(List.of(this));
    }

    /**
     * Commands answering to the same keys, across several registries.
     *
     * <p>The interesting case is between registries rather than inside one. Keys are
     * dispatched to them in order, so the first registry wins and the later command
     * silently never fires. That is the worst shape a bug can take: the shortcut is
     * still listed next to the key that no longer works, and the only symptom is
     * something not happening.
     *
     * <p>Registries are given in dispatch order, and each returned group is in that
     * same order, so the first element of a group is the one that will actually run.
     * Nulls are skipped, because a panel with no commands of its own is normal.
     */
    public static List<List<Command>> conflictsAcross(List<CommandRegistry> registries) {
        Map<Shortcut, List<Command>> byShortcut = new LinkedHashMap<>();

        for (CommandRegistry registry : registries) {
            if (registry == null) {
                continue;
            }
            for (Command command : registry.byId.values()) {
                if (command.shortcut() != null) {
                    byShortcut.computeIfAbsent(command.shortcut(), key -> new ArrayList<>()).add(command);
                }
            }
        }

        return byShortcut.values().stream()
                .filter(commands -> commands.size() > 1)
                .map(List::copyOf)
                .toList();
    }

    private record Ranked(Command command, int score) {
    }
}
