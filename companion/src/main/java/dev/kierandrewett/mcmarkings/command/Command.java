package dev.kierandrewett.mcmarkings.command;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * One thing the user can ask for, wherever they ask for it from.
 *
 * <p>The point of routing actions through commands is that a button, a keyboard
 * shortcut and the palette all invoke exactly the same thing, so they cannot drift
 * apart or disagree about when it is available. It also means every action is
 * reachable without a mouse, which is the largest accessibility win available here
 * for the least ceremony.
 *
 * @param id       stable, dotted, used by config and never shown to the user
 * @param label    what appears in the palette and on menus
 * @param category groups related commands in the palette, for example "Layer"
 * @param hint     one line explaining what it does, for people who do not know
 * @param shortcut default binding, or null for palette-only commands
 * @param enabled  whether it currently applies, so the palette can grey it out
 * @param action   what to run
 */
public record Command(
        String id,
        String label,
        String category,
        Supplier<String> hint,
        Shortcut shortcut,
        BooleanSupplier enabled,
        Runnable action) {

    public Command {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a command needs an id");
        }
        if (action == null) {
            throw new IllegalArgumentException("a command needs an action");
        }
        enabled = enabled == null ? () -> true : enabled;
        label = label == null || label.isBlank() ? id : label;
        category = category == null ? "" : category;
        hint = hint == null ? () -> "" : hint;
    }

    /**
     * The hint as it reads right now.
     *
     * <p>A supplier rather than a string because the useful hint is sometimes a fact
     * about the current state: after an hour of edits, "undo" is less use than "undo
     * move layer", and the history already knows which it is. Evaluated on demand, so
     * a hint that changes is never stale.
     */
    public String hintText() {
        String text = hint.get();
        return text == null ? "" : text;
    }

    public static Builder of(String id, String label) {
        return new Builder(id, label);
    }

    public boolean isEnabled() {
        return enabled.getAsBoolean();
    }

    /** Runs only when applicable, so callers do not each have to check. */
    public boolean run() {
        if (!isEnabled()) {
            return false;
        }
        action.run();
        return true;
    }

    /** Lowercase haystack the palette searches. */
    public String searchKey() {
        return (label + " " + category + " " + hintText() + " " + id).toLowerCase(Locale.ROOT);
    }

    public static final class Builder {

        private final String id;
        private final String label;
        private String category = "";
        private Supplier<String> hint = () -> "";
        private Shortcut shortcut;
        private BooleanSupplier enabled = () -> true;

        private Builder(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public Builder category(String value) {
            this.category = value;
            return this;
        }

        /** A hint that never changes, which is most of them. */
        public Builder hint(String value) {
            return hint(() -> value);
        }

        /** A hint worked out when it is shown, for anything that depends on state. */
        public Builder hint(Supplier<String> value) {
            this.hint = value;
            return this;
        }

        public Builder shortcut(Shortcut value) {
            this.shortcut = value;
            return this;
        }

        public Builder enabledWhen(BooleanSupplier value) {
            this.enabled = value;
            return this;
        }

        public Command does(Runnable action) {
            return new Command(id, label, category, hint, shortcut, enabled, action);
        }
    }
}
