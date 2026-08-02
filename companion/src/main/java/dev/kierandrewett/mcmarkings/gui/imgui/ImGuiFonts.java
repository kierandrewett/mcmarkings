package dev.kierandrewett.mcmarkings.gui.imgui;

import cn.enaium.fabric.imgui.DefaultImGui;
import cn.enaium.fabric.imgui.FabricImGui;
import dev.kierandrewett.mcmarkings.McMarkingsCompanion;
import dev.kierandrewett.mcmarkings.render.FontRegistry;
import imgui.ImFontConfig;
import imgui.ImGui;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Keeps ImGui's text crisp at whatever GUI scale the game is set to.
 *
 * <p>Scaling the font through {@code FontScaleMain} does not work here, and the
 * result is the blurry, pixelated text this replaces. That setting relies on the
 * renderer advertising {@code RendererHasTextures} so Dear ImGui can re-rasterise
 * glyphs on demand, and the LWJGL3 backend does not implement it. Without it the
 * only font in the atlas is the built-in 13 pixel bitmap, and asking for a larger
 * scale simply stretches those pixels.
 *
 * <p>So the atlas itself is rebuilt at the size actually wanted, from a real
 * outline font taken off the machine, and the backend's font texture is recreated
 * to match. Glyphs are then rasterised at their final size and look the way any
 * other application's do.
 *
 * <p>Rebuilding is not safe inside a frame: Dear ImGui reads the atlas while
 * building draw lists. Everything here must therefore be driven from the client
 * tick, which sits between frames, never from a render callback.
 */
public final class ImGuiFonts {

    /** Text size at GUI scale 1. Chosen to sit close to Minecraft's own font. */
    private static final float BASE_PIXELS = 16.0f;

    /** Guards against a pathological scale producing an enormous atlas. */
    private static final float MAX_PIXELS = 64.0f;

    private static float builtForPixels;

    private ImGuiFonts() {
    }

    /**
     * Rebuilds the atlas if the GUI scale has moved since it was last built.
     *
     * <p>Call from the client tick only. Cheap and returns immediately in the
     * overwhelmingly common case where nothing has changed.
     */
    public static void ensureMatchesGuiScale(FontRegistry fonts) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getWindow() == null) {
            return;
        }

        float target = Math.min(MAX_PIXELS, BASE_PIXELS * Math.max(1.0f, client.getWindow().getGuiScale()));
        if (Math.abs(target - builtForPixels) < 0.5f) {
            return;
        }

        try {
            rebuild(fonts, target);
            builtForPixels = target;
        } catch (Throwable error) {
            // A failed rebuild would otherwise be retried every tick forever, and
            // the previous atlas is still perfectly usable.
            builtForPixels = target;
            McMarkingsCompanion.LOGGER.warn("[mcmarkings] could not rebuild the imgui font atlas", error);
        }
    }

    /**
     * Which characters the atlas is built for.
     *
     * <p>Dear ImGui's default is Basic Latin and Latin-1, and that is not enough for
     * what this repository holds. Its sign descriptions contain three thousand nine
     * hundred en dashes, at U+2013, which is above Latin-1, so every tooltip and
     * detail pane showing one drew a missing-glyph box instead. The document renderer
     * had no such trouble, which is why it went unnoticed: what gets published was
     * always right and only the interface reading it was wrong.
     *
     * <p>Latin Extended-A comes with it because this is a British sign set and the
     * Welsh half needs it. There are Wales variants of most signs here, and the
     * circumflexed vowels those names use, w and y among them, sit at U+0174 onwards
     * rather than in Latin-1 with the French and German ones.
     *
     * <p>Three ranges rather than everything, because every glyph is atlas space and
     * the atlas is rebuilt whenever the GUI scale moves.
     */
    static short[] glyphRanges() {
        return new short[] {
            0x0020, 0x00FF,   // Basic Latin and Latin-1, which was all there was
            0x0100, 0x017F,   // Latin Extended-A, for Welsh and the rest of Europe
            0x2000, 0x206F,   // General Punctuation: en and em dashes, quotes, ellipsis
            0,                // Dear ImGui reads until a zero
        };
    }

    private static void rebuild(FontRegistry fonts, float pixels) {
        var atlas = ImGui.getIO().getFonts();
        atlas.clear();

        Optional<Path> file = fonts.anyReadableFontFile();
        if (file.isPresent()) {
            atlas.addFontFromFileTTF(file.get().toString(), pixels, glyphRanges());
        } else {
            // Nothing scalable on the machine. The built-in font at least rasterises
            // at the requested size rather than being stretched afterwards.
            ImFontConfig config = new ImFontConfig();
            config.setSizePixels(pixels);
            atlas.addFontDefault(config);
            config.destroy();
        }
        atlas.build();

        // The backend uploaded the old atlas as a GL texture, so it has to be told
        // to take the new one; otherwise the glyph coordinates and the texture
        // disagree and the text renders as garbage.
        if (FabricImGui.IMGUI instanceof DefaultImGui backend) {
            backend.imGuiImplGl3.destroyFontsTexture();
            backend.imGuiImplGl3.createFontsTexture();
        }

        McMarkingsCompanion.LOGGER.info("[mcmarkings] imgui font atlas rebuilt at {}px from {}",
                (int) pixels, file.map(Path::getFileName).map(Path::toString).orElse("the built-in font"));
    }
}
