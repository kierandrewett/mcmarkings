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

    public boolean isOpen() {
        return ImGui.isPopupOpen(id);
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

            for (Command command : results()) {
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
                if (ImGui.isItemHovered() && !command.hint().isEmpty()) {
                    ImGui.setTooltip(command.hint());
                }
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

    /**
     * Merges the registries in scope, in the order given.
     *
     * <p>Each is searched separately rather than concatenated first, so the window's
     * own actions keep their place ahead of the tab's rather than being interleaved
     * by whatever the ranking happens to prefer.
     */
    private List<Command> results() {
        List<Command> found = new ArrayList<>();
        String text = query.get();

        for (CommandRegistry registry : scope.get()) {
            if (registry == null) {
                continue;
            }
            for (Command command : registry.search(text, RESULT_LIMIT)) {
                if (found.size() >= RESULT_LIMIT) {
                    return found;
                }
                found.add(command);
            }
        }
        return found;
    }
}
