package dev.kierandrewett.mcmarkings.gui.imgui;

import dev.kierandrewett.mcmarkings.McMarkingsCompanion;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Writes a small settings file without stalling the frame.
 *
 * <p>Config and the map registry are both written from the interface, in about five
 * places, whenever something changes. They are small files and the write is usually
 * instant, which is exactly why it kept being done inline. It is still the wrong
 * thread: usually instant is not the same as always, and a slow disk, a network home
 * directory or an antivirus scanner turns a save into a visible stutter. A stutter
 * every time you tick a checkbox is the kind of thing that makes an interface feel
 * cheap without anyone being able to say why.
 *
 * <p>Saves collapse rather than queue. If one is already running the next is flagged
 * and happens after it, so dragging a slider costs one extra write rather than one
 * per frame, and the last value always reaches the disk.
 *
 * <p>Failures are logged and nothing else. Losing a preference is not worth
 * interrupting someone over, and there is nothing useful they could do about it
 * mid-session anyway.
 */
public final class Persist {

    /** A save that may fail. Both config and the registry fit this. */
    @FunctionalInterface
    public interface Save {
        void run() throws Exception;
    }

    private final String what;

    private final Save action;

    private final AtomicBoolean running = new AtomicBoolean();

    private volatile boolean pending;

    /**
     * @param what   named in the log line, so a failure says which file
     * @param action the write itself, run on a worker
     */
    public Persist(String what, Save action) {
        this.what = what;
        this.action = action;
    }

    /** Asks for a save. Cheap, and safe to call every frame. */
    public void request() {
        pending = true;
        if (!running.compareAndSet(false, true)) {
            return;
        }

        Thread.ofVirtual().name("mcmarkings-save-" + what).start(() -> {
            try {
                // Re-checked rather than written once: anything flagged while this was
                // running has to reach the disk too, or the last edit is the one lost.
                while (pending) {
                    pending = false;
                    action.run();
                }
            } catch (Exception failure) {
                McMarkingsCompanion.LOGGER.error("[mcmarkings] could not save " + what, failure);
            } finally {
                running.set(false);
            }
        });
    }
}
