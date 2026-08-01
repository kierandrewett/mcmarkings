package dev.kierandrewett.mcmarkings.texture;

import net.minecraft.resources.Identifier;

import java.util.OptionalInt;

/**
 * A runtime-uploaded texture, usable from both UI stacks.
 *
 * <p>owo-ui draws it by {@link #identifier()}; ImGui needs the raw GL name from
 * {@link #glId()}. The GL id is optional because it is OpenGL-backend specific
 * and Minecraft is moving towards Vulkan, so callers must degrade rather than
 * assume it is present.
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
