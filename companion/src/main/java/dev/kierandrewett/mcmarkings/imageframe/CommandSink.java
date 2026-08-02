package dev.kierandrewett.mcmarkings.imageframe;

import java.util.List;

/**
 * Somewhere to send chat commands.
 *
 * <p>Implementations throttle, because a refresh pass after a pull can produce
 * dozens of commands at once and servers rate-limit chat. Callers hand over
 * commands without a leading slash and do not wait.
 */
public interface CommandSink {

    void send(String command);

    void sendAll(List<String> commands);

    /** Commands still queued behind the throttle. */
    int pending();

    /** Changes how fast queued commands go out, while running. */
    void setCommandsPerSecond(double rate);

    void clear();
}
