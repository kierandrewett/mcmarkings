package dev.kierandrewett.mcmarkings;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class McMarkingsCompanion implements ClientModInitializer {

    public static final String MOD_ID = "mcmarkings";

    public static final Logger LOGGER = LoggerFactory.getLogger("mcmarkings");

    private static KeyMapping openBrowserKey;

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(id("main"));

        openBrowserKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mcmarkings.open_browser",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_M,
                category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openBrowserKey.consumeClick()) {
                LOGGER.info("[mcmarkings] browser key pressed");
            }
        });

        LOGGER.info("[mcmarkings] companion initialised");
    }
}
