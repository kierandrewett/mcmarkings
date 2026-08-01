package dev.kierandrewett.mcmarkings.imageframe;

import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * Sends queued commands to the server on the client tick, at a fixed rate.
 *
 * <p>A refresh pass after a pull can produce dozens of commands at once, and
 * servers drop or kick for chat spam, so commands leave one at a time on a timer
 * rather than in a burst. Draining on the tick also guarantees we are on the
 * client thread, which is where the connection may be touched.
 */
public class ClientCommandSink implements CommandSink {

    private static final int TICKS_PER_SECOND = 20;

    private final Deque<String> queue = new ArrayDeque<>();
    private final double commandsPerSecond;

    /** Notified when a command is dropped because there is no connection. */
    private final Consumer<String> onFailure;

    private int ticksUntilNext;

    public ClientCommandSink(double commandsPerSecond, Consumer<String> onFailure) {
        this.commandsPerSecond = Math.max(0.1, commandsPerSecond);
        this.onFailure = onFailure;
    }

    @Override
    public synchronized void send(String command) {
        queue.addLast(command);
    }

    @Override
    public synchronized void sendAll(List<String> commands) {
        queue.addAll(commands);
    }

    @Override
    public synchronized int pending() {
        return queue.size();
    }

    @Override
    public synchronized void clear() {
        queue.clear();
    }

    /** Call once per client tick. */
    public void tick(Minecraft client) {
        String command;
        synchronized (this) {
            if (queue.isEmpty()) {
                return;
            }
            if (ticksUntilNext > 0) {
                ticksUntilNext--;
                return;
            }
            command = queue.pollFirst();
            ticksUntilNext = (int) Math.max(1, Math.round(TICKS_PER_SECOND / commandsPerSecond));
        }

        ClientPacketListener connection = client.getConnection();
        if (connection == null) {
            // Not in a world. Dropping is right: replaying stale commands on the
            // next join would recreate maps the player has moved on from.
            McMarkingsCompanion.LOGGER.warn("[mcmarkings] no connection, dropped: {}", command);
            if (onFailure != null) {
                onFailure.accept(command);
            }
            clear();
            return;
        }

        McMarkingsCompanion.LOGGER.info("[mcmarkings] sending: /{}", command);
        connection.sendCommand(command);
    }
}
