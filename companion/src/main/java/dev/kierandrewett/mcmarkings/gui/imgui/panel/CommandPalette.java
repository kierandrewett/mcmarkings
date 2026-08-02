package dev.kierandrewett.mcmarkings.gui.imgui.panel;

import dev.kierandrewett.mcmarkings.command.Command;
import dev.kierandrewett.mcmarkings.command.CommandRegistry;
import imgui.ImGui;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Everything the window can do, one search away.
 *
 * <p>Lifted out of the editor, which is where it started, because being able to
 * reach an action by typing its name is worth more the further you are from the
 * thing that owns it. Knowing a command exists somewhere is easy; remembering which
 * tab put it where is what makes people reach for the mouse.
 *
 * <p>Searches across several registries at once, so the window's own actions and
 * whatever the visible tab offers appear together. Nothing here knows which is
 * which: the caller decides what is in scope this frame, which is what lets the
 * contents change with the tab without the palette caring.
 */
public final class CommandPalette {

    private static final int QUERY_BUFFER = 128;

    /** Enough to choose from without turning the list into its own reading task. */
    private static final int RESULT_LIMIT = 14;

    private final String id;

    private final Supplier<List<CommandRegistry>> scope;

    private final ImString query = new ImString("", QUERY_BUFFER);

    private boolean openRequested;

    /**
     * ImGui takes focus for the item submitted after the call, so this has to
     * survive until the field itself is being drawn rather than being set with it.
     */
    private boolean focusPending;

    public CommandPalette(String id, Supplier<List<CommandRegistry>> scope) {
        this.id = id;
        this.scope = scope;
    }

    /** Opens on the next frame, cleared and focused. */
    public void open() {
        openRequested = true;
    }

    /**
     * Draws the palette. Call unconditionally: an ImGui popup exists only while
     * whoever opened it is still submitting.
     */
    public void draw() {
        if (openRequested) {
            openRequested = false;
            query.set("");
            focusPending = true;
            ImGui.openPopup(id);
        }
        if (!ImGui.beginPopup(id)) {
            return;
        }

        Command chosen = null;
        try {
            if (focusPending) {
                ImGui.setKeyboardFocusHere();
                focusPending = false;
            }
            ImGui.setNextItemWidth(ImGui.getFontSize() * 24.0f);
            ImGui.inputTextWithHint("##" + id + "-query", "Search commands", query);

            Results results = results();
            for (Command command : results.shown()) {
                String shortcut = command.shortcut() == null ? "" : "   " + command.shortcut().display();
                String label = command.category() + ": " + command.label() + shortcut;

                if (!command.isEnabled()) {
                    // Shown rather than hidden, so the palette teaches what exists and a
                    // missing entry always means a wrong search rather than a state you
                    // cannot see. The hint below says why it is unavailable.
                    ImGui.textDisabled(label);
                    continue;
                }
                if (ImGui.selectable(label)) {
                    chosen = command;
                }
                if (ImGui.isItemHovered() && !command.hintText().isEmpty()) {
                    ImGui.setTooltip(command.hintText());
                }
            }

            if (results.shown().isEmpty()) {
                ImGui.textDisabled("Nothing matches that.");
            } else if (results.total() > results.shown().size()) {
                // Said, because every other list in here says it. A palette that
                // quietly shows fourteen of thirty teaches people it does not have
                // what they are looking for.
                ImGui.textDisabled((results.total() - results.shown().size())
                        + " more; keep typing to narrow it down");
            }

            if (chosen != null) {
                ImGui.closeCurrentPopup();
            }
        } finally {
            ImGui.endPopup();
        }

        // Run outside the popup. A command that opens another popup, or that throws,
        // must not do it while this one is still on the stack.
        if (chosen != null) {
            chosen.run();
        }
    }

    /** What matched, and how much of it is being shown. */
    private record Results(List<Command> shown, int total) {
    }

    /**
     * Merges the registries in scope.
     *
     * <p>Each is searched separately rather than concatenated first, so the window's
     * own actions keep their place ahead of the tab's rather than being interleaved
     * by whatever the ranking happens to prefer.
     *
     * <p>Taken a round at a time once there are more matches than fit. Filling the
     * list from the window first and stopping is the obvious way and it is wrong: the
     * window has ten commands with shortcuts on most of them, so a short query could
     * fill every slot and the visible tab's commands would not appear at all. A
     * palette that hides the commands of the thing you are looking at is worse than
     * no palette, because it answers "no" to a question it never actually asked.
     */
    private Results results() {
        List<List<Command>> perRegistry = new ArrayList<>();
        String text = query.get();
        int total = 0;

        for (CommandRegistry registry : scope.get()) {
            if (registry == null) {
                continue;
            }
            // Each is bounded so a large registry cannot make this expensive, which
            // does mean the total is a count of what was looked at rather than of
            // everything that could ever match. Close enough to say "there are more".
            List<Command> matches = registry.search(text, RESULT_LIMIT * 2);
            perRegistry.add(matches);
            total += matches.size();
        }

        return new Results(interleave(perRegistry, RESULT_LIMIT), total);
    }

    /**
     * Takes one from each list in turn until the limit is reached.
     *
     * <p>Package-private and static so it can be tested. The allocation is the part
     * with a decision in it, and it is invisible in a running palette: the failure
     * looks like a command simply not existing.
     *
     * <p>A list that runs out is skipped rather than ending the round, so one
     * registry with two commands does not stop a longer one from filling the rest.
     */
    static List<Command> interleave(List<List<Command>> perRegistry, int limit) {
        List<Command> shown = new ArrayList<>();

        for (int round = 0; shown.size() < limit; round++) {
            boolean anyLeft = false;
            for (List<Command> matches : perRegistry) {
                if (round >= matches.size()) {
                    continue;
                }
                anyLeft = true;
                shown.add(matches.get(round));
                if (shown.size() >= limit) {
                    break;
                }
            }
            if (!anyLeft) {
                break;
            }
        }

        return shown;
    }
}
