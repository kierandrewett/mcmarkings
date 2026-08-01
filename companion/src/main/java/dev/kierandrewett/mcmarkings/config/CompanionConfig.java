package dev.kierandrewett.mcmarkings.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * User settings, stored as JSON next to the other Fabric mod configs so they are
 * readable and diffable on disk.
 */
public class CompanionConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Local clone the mod reads images from and writes generated output into. */
    public String repoPath = System.getProperty("user.home") + "/dev/mcmarkings";

    /** Branch to pull and push. */
    public String branch = "main";

    /** Left blank to derive "owner/repo" from the origin remote. */
    public String githubSlug = "";

    /** Command root without the slash; servers often rebind imageframe to frame. */
    public String commandAlias = "imageframe";

    /** Directories searched for the Transport typeface, which is not in the repo. */
    public List<String> fontSearchPaths = new ArrayList<>(List.of(
            System.getProperty("user.home") + "/.local/share/fonts",
            "/usr/share/fonts"));

    /**
     * Export resolution per map frame. Vanilla maps are 128px, but ImageFrameClient
     * renders full colour at higher detail, so 256 is a better default.
     */
    public int exportPixelsPerFrame = 256;

    /** Invisible frames requested as glowing rather than plain. */
    public boolean glowingFrames = true;

    /** Commands per second sent to the server, to stay under chat rate limits. */
    public double commandsPerSecond = 2.0;

    /** Directory generated PNGs are written into, relative to the repo root. */
    public String generatedDirectory = "generated";

    /** Directory generator scripts are read from, relative to the repo root. */
    public String generatorDirectory = "generators";

    public Path repoRoot() {
        return Path.of(repoPath);
    }

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(McMarkingsCompanion.MOD_ID + ".json");
    }

    public static CompanionConfig load() {
        Path path = configPath();
        if (!Files.exists(path)) {
            CompanionConfig fresh = new CompanionConfig();
            fresh.save();
            return fresh;
        }
        try {
            CompanionConfig loaded = GSON.fromJson(Files.readString(path), CompanionConfig.class);
            return loaded == null ? new CompanionConfig() : loaded;
        } catch (IOException | RuntimeException exception) {
            McMarkingsCompanion.LOGGER.error("[mcmarkings] could not read config, using defaults", exception);
            return new CompanionConfig();
        }
    }

    public void save() {
        try {
            Path path = configPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (IOException exception) {
            McMarkingsCompanion.LOGGER.error("[mcmarkings] could not write config", exception);
        }
    }
}
