package dev.kierandrewett.mcmarkings;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Notices when the mod's own file has been replaced under the running game.
 *
 * <p>Java loads classes when they are first needed. Replacing the jar while the game
 * runs does not reload anything, it swaps the file the loader will read from next, so
 * everything already in memory carries on and the next class the mod reaches for
 * comes from a build it was not compiled against. What surfaces is "Failed to load
 * class file for dev.kierandrewett.mcmarkings.repo.PullResult", named after a class
 * nobody has heard of, from whichever screen happened to need it first.
 *
 * <p>Somebody hit that three times in one afternoon while I was working on this: once
 * in the window, once on a pull, once typing in the search box. The failure is
 * explained now wherever it lands, and explaining it afterwards is the worse half of
 * the job. The file's timestamp says the same thing before anything breaks.
 *
 * <p>Anyone who updates a mod without closing the game meets this, which is most
 * people the first time and everyone who develops one.
 */
public final class InstallWatch {

    /**
     * How often the file is looked at.
     *
     * <p>A stat is cheap and a frame is not the place for one anyway. Nothing here is
     * urgent: the point is to say so before the next thing someone clicks fails, and a
     * couple of seconds is well inside that.
     */
    private static final long CHECK_INTERVAL_MILLIS = 2_000;

    private static final Path SOURCE = locate().orElse(null);

    private static final long BUILT_AT = modifiedAt(SOURCE);

    private static volatile boolean replaced;

    private static volatile long lastCheckedAt;

    private InstallWatch() {
    }

    /**
     * Whether the file this is running from has changed since it started.
     *
     * <p>Latches. Once it has been replaced the game is going to have to restart
     * whatever happens next, and a warning that came and went with the timestamp
     * would be worse than one that stays.
     */
    public static boolean replaced(long nowMillis) {
        if (replaced || SOURCE == null || BUILT_AT == 0L) {
            return replaced;
        }
        if (nowMillis - lastCheckedAt < CHECK_INTERVAL_MILLIS) {
            return false;
        }
        lastCheckedAt = nowMillis;

        long now = modifiedAt(SOURCE);
        if (now != 0L && now != BUILT_AT) {
            replaced = true;
            McMarkingsCompanion.LOGGER.warn(
                    "[mcmarkings] {} was replaced while the game was running", SOURCE.getFileName());
        }
        return replaced;
    }

    /** What to tell someone, once. */
    public static String warning() {
        return "This mod's file has been replaced since the game started. "
                + "Anything it has not already loaded will fail. Restart Minecraft.";
    }

    /**
     * The jar or folder this is running from.
     *
     * <p>Through the loader rather than the class's own code source, because in a
     * development run the classes come from a build directory and the timestamps there
     * move for reasons that are not a reinstall.
     */
    private static Optional<Path> locate() {
        try {
            return FabricLoader.getInstance().getModContainer(McMarkingsCompanion.MOD_ID)
                    .flatMap(container -> container.getOrigin().getPaths().stream().findFirst())
                    .filter(Files::isRegularFile);
        } catch (RuntimeException | LinkageError unavailable) {
            // No loader, which is a test. Nothing to watch and nothing to say.
            return Optional.empty();
        }
    }

    private static long modifiedAt(Path path) {
        if (path == null) {
            return 0L;
        }
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException | RuntimeException unreadable) {
            return 0L;
        }
    }
}
