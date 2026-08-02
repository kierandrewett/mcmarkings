package dev.kierandrewett.mcmarkings.doc;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Undo and redo for the editor.
 *
 * <p>The thing that makes an editor safe to play in. Without it every action is
 * permanent, so people stop experimenting and start being careful, which is the
 * opposite of what a tool like this is for.
 *
 * <p>Two details do most of the work. Consecutive edits of the same kind to the
 * same layer coalesce, so dragging something across the canvas is one undo rather
 * than two hundred, and the stack is bounded so a long session cannot quietly eat
 * memory holding every state a document has ever been in.
 *
 * <p>Documents are immutable records, so entries share structure and holding a
 * hundred of them is far cheaper than it sounds.
 */
public final class History {

    /** Enough to cover a long working session without being worth measuring. */
    public static final int DEFAULT_LIMIT = 100;

    /**
     * How long two edits can be apart and still merge.
     *
     * <p>Generous, because a drag is a stream of tiny edits and the alternative is
     * an undo stack nobody can navigate. A pause longer than this reads as a
     * deliberate separate action.
     */
    private static final long COALESCE_WINDOW_MILLIS = 700;

    /** One recorded state, with the label the UI shows next to Undo. */
    public record Entry(Document document, String label, String coalesceKey, long atMillis) {
    }

    private final int limit;

    private final Deque<Entry> past = new ArrayDeque<>();
    private final Deque<Entry> future = new ArrayDeque<>();

    private Entry present;

    public History(Document initial) {
        this(initial, DEFAULT_LIMIT);
    }

    public History(Document initial, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("history limit must be at least 1, got " + limit);
        }
        this.limit = limit;
        this.present = new Entry(initial, "Open", null, 0L);
    }

    public Document current() {
        return present.document();
    }

    public boolean canUndo() {
        return !past.isEmpty();
    }

    public boolean canRedo() {
        return !future.isEmpty();
    }

    /** Label for the Undo control, so it can read "Undo move layer". */
    public Optional<String> undoLabel() {
        return canUndo() ? Optional.of(present.label()) : Optional.empty();
    }

    public Optional<String> redoLabel() {
        return canRedo() ? Optional.of(future.peek().label()) : Optional.empty();
    }

    /**
     * Records a new state.
     *
     * <p>{@code coalesceKey} identifies a continuous gesture, typically the action
     * and the layer it affects, such as {@code "move:layer-3"}. A null key never
     * merges, which is what a discrete action like deleting a layer wants.
     */
    public void push(Document document, String label, String coalesceKey) {
        push(document, label, coalesceKey, System.currentTimeMillis());
    }

    /** Time is injected so the coalescing window can be tested without sleeping. */
    void push(Document document, String label, String coalesceKey, long atMillis) {
        if (document == null || document.equals(present.document())) {
            // Dragging a layer back to where it started should not fill the stack.
            return;
        }

        // A new edit invalidates anything that was undone; there is no tree here.
        future.clear();

        if (shouldCoalesce(coalesceKey, atMillis)) {
            present = new Entry(document, label, coalesceKey, atMillis);
            return;
        }

        past.push(present);
        while (past.size() > limit) {
            past.removeLast();
        }
        present = new Entry(document, label, coalesceKey, atMillis);
    }

    private boolean shouldCoalesce(String coalesceKey, long atMillis) {
        return coalesceKey != null
                && coalesceKey.equals(present.coalesceKey())
                && atMillis - present.atMillis() <= COALESCE_WINDOW_MILLIS;
    }

    /**
     * Steps back one action.
     *
     * <p>Returns the document now in effect either way, so callers do not have to
     * branch on whether anything happened.
     */
    public Document undo() {
        if (!canUndo()) {
            return current();
        }
        future.push(present);
        present = past.pop();
        return current();
    }

    public Document redo() {
        if (!canRedo()) {
            return current();
        }
        past.push(present);
        present = future.pop();
        return current();
    }

    /**
     * Ends the current gesture, so the next edit starts a fresh entry.
     *
     * <p>Called when a drag finishes. Without it, letting go and immediately
     * dragging again would merge into the previous move purely because it happened
     * quickly.
     */
    public void endGesture() {
        present = new Entry(present.document(), present.label(), null, present.atMillis());
    }

    /** Forgets everything, for opening a different document. */
    public void reset(Document document) {
        past.clear();
        future.clear();
        present = new Entry(document, "Open", null, 0L);
    }

    public int depth() {
        return past.size();
    }
}
