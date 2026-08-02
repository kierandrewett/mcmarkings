package dev.kierandrewett.mcmarkings.texture;

import net.minecraft.resources.Identifier;

import java.util.OptionalInt;

/**
 * A runtime-uploaded texture.
 *
 * <p>ImGui draws it from the raw GL name in {@link #glId()}, while
 * {@link #identifier()} is what Minecraft's own drawing takes. The GL id is
 * optional because it is specific to the OpenGL backend and the game is moving
 * towards Vulkan, so callers must degrade rather than assume it is present.
 */
public interface TextureHandle extends AutoCloseable {

    Identifier identifier();

    int width();

    int height();

    /** Raw OpenGL texture name, empty when the backend is not OpenGL. */
    OptionalInt glId();

    @Override
    void close();
}
