package dev.kierandrewett.mcmarkings;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kierandrewett.mcmarkings.config.CompanionConfig;
import dev.kierandrewett.mcmarkings.core.RepoImage;
import dev.kierandrewett.mcmarkings.gui.BrowserScreen;
import dev.kierandrewett.mcmarkings.gui.imgui.BuilderScreen;
import dev.kierandrewett.mcmarkings.gui.imgui.GeneratorScreen;
import dev.kierandrewett.mcmarkings.imageframe.ClientCommandSink;
import dev.kierandrewett.mcmarkings.js.RhinoGeneratorRuntime;
import dev.kierandrewett.mcmarkings.registry.JsonMapRegistry;
import dev.kierandrewett.mcmarkings.render.FontRegistry;
import dev.kierandrewett.mcmarkings.render.ImageComposer;
import dev.kierandrewett.mcmarkings.repo.ProcessGitService;
import dev.kierandrewett.mcmarkings.repo.RepoScanner;
import dev.kierandrewett.mcmarkings.texture.RuntimeTextureCache;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.util.function.Function;

public class McMarkingsCompanion implements ClientModInitializer {

    public static final String MOD_ID = "mcmarkings";

    public static final Logger LOGGER = LoggerFactory.getLogger("mcmarkings");

    /** Thumbnails are drawn into a 64px cell, so decoding beyond 128 is wasted work. */
    private static final int THUMBNAIL_EDGE = 128;

    /** Roughly a screenful of cells several times over, well short of exhausting VRAM. */
    private static final int MAX_RESIDENT_THUMBNAILS = 512;

    private static CompanionServices services;

    private static ClientCommandSink commands;

    private static KeyMapping openBrowserKey;
    private static KeyMapping openGeneratorKey;
    private static KeyMapping openBuilderKey;

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public static CompanionServices services() {
        return services;
    }

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(id("main"));

        openBrowserKey = register("key.mcmarkings.open_browser", InputConstants.KEY_M, category);
        openGeneratorKey = register("key.mcmarkings.open_generator", InputConstants.KEY_G, category);
        openBuilderKey = register("key.mcmarkings.open_builder", InputConstants.KEY_B, category);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (commands != null) {
                commands.tick(client);
            }
            while (openBrowserKey.consumeClick()) {
                open(client, BrowserScreen::new);
            }
            while (openGeneratorKey.consumeClick()) {
                open(client, GeneratorScreen::new);
            }
            while (openBuilderKey.consumeClick()) {
                open(client, BuilderScreen::new);
            }
        });

        LOGGER.info("[mcmarkings] companion initialised");
    }

    private static KeyMapping register(String translationKey, int keyCode, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(
                new KeyMapping(translationKey, InputConstants.Type.KEYSYM, keyCode, category));
    }

    private static void open(Minecraft client, Function<CompanionServices, Screen> screen) {
        CompanionServices resolved = ensureServices(client);
        if (resolved != null) {
            client.setScreen(screen.apply(resolved));
        }
    }

    /**
     * Services are built on first use rather than at startup.
     *
     * <p>Scanning the repository and shelling out to git are slow and can fail, and
     * neither is worth doing during the loading screen for a player who may never
     * press the key. Building here also means a misconfigured repository can be
     * fixed and retried without restarting the game.
     */
    private static CompanionServices ensureServices(Minecraft client) {
        if (services != null) {
            return services;
        }

        CompanionConfig config = CompanionConfig.load();

        if (!Files.isDirectory(config.repoRoot())) {
            report(client, "Repository not found at " + config.repoRoot()
                    + ". Set repoPath in config/mcmarkings.json.", ChatFormatting.RED);
            return null;
        }

        try {
            RepoScanner repo = new RepoScanner(config.repoRoot());
            repo.rescan();

            JsonMapRegistry registry = new JsonMapRegistry();
            registry.load();

            FontRegistry fonts = new FontRegistry(config.fontSearchPaths);
            for (String warning : fonts.warnings()) {
                report(client, warning, ChatFormatting.YELLOW);
            }

            ImageComposer composer = new ImageComposer();
            Function<RepoImage, BufferedImage> loader = image -> {
                try {
                    return composer.thumbnail(repo.resolve(image.path()), THUMBNAIL_EDGE);
                } catch (IOException exception) {
                    throw new IllegalStateException("could not read " + image.path(), exception);
                }
            };

            commands = new ClientCommandSink(config.commandsPerSecond,
                    command -> report(client, "Not connected, dropped: /" + command, ChatFormatting.RED));

            RhinoGeneratorRuntime generators = new RhinoGeneratorRuntime(config, fonts);
            try {
                generators.reload();
            } catch (Exception exception) {
                // A broken script must not cost the player the browser, which is the
                // part that works without any generators at all.
                report(client, "Generators failed to load: " + exception.getMessage(), ChatFormatting.RED);
            }

            services = new CompanionServices(
                    config,
                    repo,
                    new ProcessGitService(config.repoRoot()),
                    registry,
                    commands,
                    new RuntimeTextureCache(loader, MAX_RESIDENT_THUMBNAILS),
                    generators);

            LOGGER.info("[mcmarkings] ready, {} image(s) from {}", repo.images().size(), config.repoRoot());
            return services;
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("[mcmarkings] could not start", exception);
            report(client, "Could not start: " + exception.getMessage(), ChatFormatting.RED);
            return null;
        }
    }

    private static void report(Minecraft client, String message, ChatFormatting colour) {
        LOGGER.warn("[mcmarkings] {}", message);
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("[mcmarkings] " + message).withStyle(colour));
        }
    }
}
