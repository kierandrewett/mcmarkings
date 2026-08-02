package dev.kierandrewett.mcmarkings.gui.imgui.panel;

/**
 * One tab's worth of interface.
 *
 * <p>Deliberately tiny. The shell knows nothing about what a panel draws and a
 * panel knows nothing about the shell, which is what lets the same image browser
 * be a tab in one place and the body of a modal in another. Anything a panel needs
 * from its surroundings is passed to its constructor rather than reached for here.
 */
public interface Panel {

    /**
     * Label shown on the tab.
     *
     * <p>Must be stable for the life of the panel: ImGui keys a tab's selected
     * state off its label, so a title that changes between frames deselects the
     * tab the user is looking at.
     */
    String title();

    /**
     * Draws the body into whatever region the caller has already set up.
     *
     * <p>Called once per frame while the tab is selected, from the render thread.
     * Nothing here may read a file, decode an image or block on a future; the
     * budget is a fraction of a frame.
     */
    void draw();

    /**
     * Called when the window closes, for panels holding something the garbage
     * collector will not free.
     *
     * <p>Default empty, because most panels hold nothing of the sort. A GPU texture
     * is the case this exists for: nothing else references it, so without a hook it
     * survives for the rest of the session.
     */
    default void onRemoved() {
    }

    /**
     * The panel's own actions, for the window's command palette to search.
     *
     * <p>Null when it has none, which is most of them. Returning the live registry
     * rather than a copy is deliberate: what a command does and whether it is
     * available both depend on state that changes between frames.
     */
    default dev.kierandrewett.mcmarkings.command.CommandRegistry commands() {
        return null;
    }
}
