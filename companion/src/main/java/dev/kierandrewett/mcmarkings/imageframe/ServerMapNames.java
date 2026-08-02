package dev.kierandrewett.mcmarkings.imageframe;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Asks the server which maps ImageFrame actually has.
 *
 * <p>Until now the only answer to "does this map exist" came from a JSON file this
 * mod writes itself, and it writes an entry when it sends a command rather than when
 * the command works. A command the server threw away still left it convinced the map
 * was there, and from then on every press refreshed something that had never been
 * created, silently, which is how somebody lost an evening to a sign that would not
 * appear.
 *
 * <p>There is a real answer available and it was there the whole time. ImageFrame
 * registers its command through Brigadier, the server sends its command tree to every
 * client, and the map name argument is completed by the server on request. That is
 * the same machinery the chat box uses when you press tab, so asking for the
 * completions of "imageframe get " gets back the actual names, from the server, with
 * no chat parsing and no guessing.
 *
 * <p>Empty means unknown, never "there are none". A server whose plugin offers no
 * completions for that argument is indistinguishable here from one with no maps, and
 * treating silence as proof of absence would swap a wrong yes for a wrong no. The
 * caller keeps its own record for that case.
 */
public final class ServerMapNames {

    /**
     * How often the server is asked.
     *
     * <p>Asking is a packet, and the caller is a panel that draws sixty times a
     * second, so asking on every call would be sixty command-completion requests per
     * second at somebody's server for as long as they left the window open. Once every
     * few seconds is far quicker than a person can place a map and place it again.
     */
    private static final long REFRESH_INTERVAL_MILLIS = 5_000;

    private static volatile Set<String> cached = Set.of();

    private static volatile long lastAskedAtMillis;

    private static CompletableFuture<Suggestions> pending;

    private ServerMapNames() {
    }

    /**
     * The names the server offers, lowercased, or empty when it will not say.
     *
     * <p>Returns immediately with whatever has already arrived. The request goes out
     * over the network and completes later, so the first call after opening a world
     * is expected to come back empty and the one after it to be right.
     */
    public static synchronized Set<String> known(String alias) {
        if (pending != null && pending.isDone()) {
            cached = harvest(pending);
            pending = null;
        }

        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            // Left the world. What the last server said is not true of the next one.
            cached = Set.of();
            return cached;
        }

        long now = System.currentTimeMillis();
        if (pending != null || now - lastAskedAtMillis < REFRESH_INTERVAL_MILLIS) {
            return cached;
        }
        lastAskedAtMillis = now;

        try {
            CommandDispatcher<ClientSuggestionProvider> commands = connection.getCommands();
            ClientSuggestionProvider provider = connection.getSuggestionsProvider();

            // A trailing space, so the parse sits on the argument after "get" rather
            // than on the word "get" itself, which would complete the subcommand.
            ParseResults<ClientSuggestionProvider> parse =
                    commands.parse(alias + " get ", provider);

            // Asked for, not waited for. The answer comes from a server and this
            // runs while a frame is being built, so blocking would hang the game on
            // somebody's latency, every frame, which is worse than the problem being
            // solved. Read on a later call.
            pending = commands.getCompletionSuggestions(parse);
            return cached;
        } catch (Exception | LinkageError unavailable) {
            // A server that does not speak this, or a plugin that declares the
            // argument some other way. The caller falls back to what it recorded.
            McMarkingsCompanion.LOGGER.debug("[mcmarkings] could not read map names from the server",
                    unavailable);
            return cached;
        }
    }

    private static Set<String> harvest(CompletableFuture<Suggestions> answered) {
        try {
            Set<String> names = new LinkedHashSet<>();
            for (Suggestion suggestion : answered.get().getList()) {
                String text = suggestion.getText().trim();
                if (!text.isEmpty()) {
                    names.add(text.toLowerCase(Locale.ROOT));
                }
            }
            return names;
        } catch (Exception failed) {
            if (failed instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Set.of();
        }
    }

    /** Forgets what the last server said, for a disconnect or a change of alias. */
    public static synchronized void reset() {
        cached = Set.of();
        pending = null;
        lastAskedAtMillis = 0L;
    }
}
