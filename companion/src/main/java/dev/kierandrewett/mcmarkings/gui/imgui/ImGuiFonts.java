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

    /**
     * How much smaller Monocraft is asked for than a normal face.
     *
     * <p>Pixel fonts fill their em box in a way a proportional sans does not, so at
     * the same nominal size Monocraft comes out around twice as large on screen. The
     * base above was chosen against a sans and bundling Monocraft made the whole
     * interface jump, which is a thing you notice immediately and would never guess
     * from the number being the same.
     *
     * <p>Applied to the bundled face only. Halving it for everything would leave
     * anyone falling back to a system font reading eight pixel text.
     */
    private static final float BUNDLED_SCALE = 0.5f;

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

    /** Where the bundled face lives in the jar. */
    private static final String BUNDLED_FONT = "/assets/mcmarkings/font/Monocraft.ttf";

    /**
     * The bundled face, or null if it is not in the jar.
     *
     * <p>Null rather than throwing, because a missing font is not worth taking the
     * window down for: the scan of installed fonts is still there behind it, and
     * before that Dear ImGui's own bitmap. The interface reads worse and it opens.
     */
    private static byte[] bundledFont() {
        try (java.io.InputStream stream = ImGuiFonts.class.getResourceAsStream(BUNDLED_FONT)) {
            return stream == null ? null : stream.readAllBytes();
        } catch (java.io.IOException | RuntimeException unreadable) {
            McMarkingsCompanion.LOGGER.warn("[mcmarkings] could not read the bundled font", unreadable);
            return null;
        }
    }

    private static void rebuild(FontRegistry fonts, float pixels) {
        var atlas = ImGui.getIO().getFonts();
        atlas.clear();

        // Monocraft first, and it travels with the mod rather than being looked for.
        // Minecraft's own font is a bitmap in ascii.png and unicode pages, which
        // nothing here can load, so this is a scalable recreation of the same shapes.
        // Bundled because the alternative is telling somebody to go and install a font
        // before their interface reads properly, and most people will not.
        byte[] bundled = bundledFont();
        if (bundled != null) {
            // Rounded, and never below one. A pixel font asked for a fractional size
            // lands its glyphs between pixels and comes out furry, which is the exact
            // thing a font like this is for avoiding.
            float bundledPixels = Math.max(1.0f, Math.round(pixels * BUNDLED_SCALE));
            atlas.addFontFromMemoryTTF(bundled, bundledPixels, glyphRanges());
            McMarkingsCompanion.LOGGER.info("[mcmarkings] imgui font atlas rebuilt at {}px from Monocraft",
                    (int) bundledPixels);
            finishAtlas(atlas);
            return;
        }

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
        McMarkingsCompanion.LOGGER.info("[mcmarkings] imgui font atlas rebuilt at {}px from {}",
                (int) pixels, file.map(Path::getFileName).map(Path::toString).orElse("the built-in font"));
        finishAtlas(atlas);
    }

    /**
     * Builds the atlas and makes the backend take the new texture.
     *
     * <p>Shared, because there are three ways in now and forgetting this on any of
     * them does not fail, it renders every glyph from the wrong place in the texture
     * and the interface comes out as garbage.
     */
    private static void finishAtlas(imgui.ImFontAtlas atlas) {
        atlas.build();
        if (FabricImGui.IMGUI instanceof DefaultImGui backend) {
            backend.imGuiImplGl3.destroyFontsTexture();
            backend.imGuiImplGl3.createFontsTexture();
        }
    }
}
