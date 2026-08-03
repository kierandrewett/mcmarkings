package dev.kierandrewett.mcmarkings;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kierandrewett.mcmarkings.config.CompanionConfig;
import dev.kierandrewett.mcmarkings.gui.imgui.ImGuiShell;
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

        // Every line the server prints, offered to the info reader before the player sees it. It
        // takes only what this mod asked for and only while it is waiting, and hands everything
        // else straight back.
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.ALLOW_GAME.register(
                (message, overlay) -> overlay
                        || dev.kierandrewett.mcmarkings.imageframe.ImageFrameInfo.read(
                                message.getString(), System.currentTimeMillis()));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (services != null) {
                services.tick(client);
                boolean showing = client.screen instanceof cn.enaium.fabric.imgui.ImGuiRenderable;
                // Between frames, which is the only safe point to touch the atlas.
                if (showing) {
                    dev.kierandrewett.mcmarkings.gui.imgui.ImGuiFonts
                            .ensureMatchesGuiScale(services.fonts, services.config.textScale);
                    // Said every tick rather than once on opening, so that a screen
                    // reaching this state by any other route is never left deaf.
                    dev.kierandrewett.mcmarkings.gui.imgui.ImGuiScreens.acceptInput(true);
                } else {
                    // ImGui hears the keyboard whether or not it is on screen, and it
                    // keeps what it hears until something draws. Everything played
                    // between one opening and the next would arrive at the next one all
                    // at once, typed into whatever field had the keyboard.
                    dev.kierandrewett.mcmarkings.gui.imgui.ImGuiScreens.acceptInput(false);
                    dev.kierandrewett.mcmarkings.gui.imgui.ImGuiScreens.discardPendingInput();
                }
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

        // Always the shell. It shows the first-run panel itself when nothing is set
        // up, which is what stops an unconfigured install being a different screen
        // with a different look and a handover in the middle of it.
        client.setScreen(shell(resolved));
    }

    /**
     * The window, kept between openings.
     *
     * <p>It used to be built fresh on every keypress, which threw away everything the
     * interface was holding: where the canvas was scrolled and how far in, what was
     * selected, the search you had typed, and the generator form you were halfway
     * through filling in. In a game the window gets closed constantly, because Escape
     * is also how you look at the world, and coming back to a reset view every time
     * is the sort of friction that stops a session rather than interrupting it.
     *
     * <p>Tied to the services it was built against, so a reload in settings still gets
     * a genuinely new window rather than one holding references to services that have
     * been thrown away.
     */
    private static ImGuiShell shell(CompanionServices resolved) {
        if (shell == null || !shell.belongsTo(resolved)) {
            shell = new ImGuiShell(resolved);
        }
        return shell;
    }

    /**
     * Services are built on first use rather than at startup.
     *
     * <p>Scanning repositories is slow and is not worth doing during the loading
     * screen for a player who may never press the key. Construction is also
     * deliberately total: it never throws, so there is no failure path here to
     * handle.
     */
    private static ImGuiShell shell;

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
        if (shell != null) {
            // The window owns GPU textures that nothing else references. Dropping the
            // reference without this leaks them for the rest of the session.
            shell.dispose();
            shell = null;
        }

        // After the window, not before. Panels release their own textures as they are
        // removed, and closing the cache out from under them would leave them evicting
        // from something already emptied.
        //
        // Here rather than at the settings button that reloads, because this is the
        // method that does the discarding, and a second caller would otherwise have to
        // know to clean up as well. Nothing frees these once the reference is gone:
        // the textures, and a decode pool whose threads outlived every reload of the
        // session because they are daemons and so never complained.
        if (services != null) {
            services.thumbnails.close();
        }
        services = null;
    }

    private static void report(Minecraft client, String message, ChatFormatting colour) {
        LOGGER.warn("[mcmarkings] {}", message);
        if (client != null && client.player != null) {
            client.player.sendSystemMessage(Component.literal("[mcmarkings] " + message).withStyle(colour));
        }
    }
}
