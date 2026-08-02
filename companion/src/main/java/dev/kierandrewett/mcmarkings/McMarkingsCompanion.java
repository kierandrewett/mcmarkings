package dev.kierandrewett.mcmarkings;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kierandrewett.mcmarkings.config.CompanionConfig;
import dev.kierandrewett.mcmarkings.gui.BrowserScreen;
import dev.kierandrewett.mcmarkings.gui.WelcomeScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class McMarkingsCompanion implements ClientModInitializer {

    public static final String MOD_ID = "mcmarkings";

    public static final Logger LOGGER = LoggerFactory.getLogger("mcmarkings");

    private static CompanionServices services;

    private static KeyMapping openKey;

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(id("main"));

        // One key for the whole mod. Everything else is reachable from the screens
        // themselves, so there is nothing to memorise and nothing to rebind.
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mcmarkings.open",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_M,
                category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (services != null) {
                services.tick(client);
            }
            while (openKey.consumeClick()) {
                open(client);
            }
        });

        LOGGER.info("[mcmarkings] companion initialised");
    }

    /**
     * Opens the browser, or the first-run screen when nothing is set up yet.
     *
     * <p>An unconfigured install is a starting point rather than a failure, so this
     * never refuses to open something.
     */
    private static void open(Minecraft client) {
        CompanionServices resolved = services();

        for (String note : resolved.startupNotes()) {
            report(client, note, ChatFormatting.YELLOW);
        }
        resolved.clearStartupNotes();

        // Deliberately asks what is configured rather than what has finished
        // opening. Opening runs in the background, and treating "not ready yet" as
        // "nothing set up" would flash the first-run screen at an existing user.
        client.setScreen(resolved.hasConfiguredRepositories()
                ? new BrowserScreen(resolved)
                : new WelcomeScreen(resolved));
    }

    /**
     * Services are built on first use rather than at startup.
     *
     * <p>Scanning repositories is slow and is not worth doing during the loading
     * screen for a player who may never press the key. Construction is also
     * deliberately total: it never throws, so there is no failure path here to
     * handle.
     */
    public static CompanionServices services() {
        if (services == null) {
            services = new CompanionServices(CompanionConfig.load(), command ->
                    report(Minecraft.getInstance(), "Not connected, dropped: /" + command, ChatFormatting.RED));
            LOGGER.info("[mcmarkings] ready with {} repository(ies)", services.workspaces().size());
        }
        return services;
    }

    /** Forgets everything so the next open re-reads config and re-scans from disk. */
    public static void reset() {
        services = null;
    }

    private static void report(Minecraft client, String message, ChatFormatting colour) {
        LOGGER.warn("[mcmarkings] {}", message);
        if (client != null && client.player != null) {
            client.player.sendSystemMessage(Component.literal("[mcmarkings] " + message).withStyle(colour));
        }
    }
}
