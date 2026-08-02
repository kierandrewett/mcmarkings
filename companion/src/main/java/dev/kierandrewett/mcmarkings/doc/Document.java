package dev.kierandrewett.mcmarkings.doc;

import dev.kierandrewett.mcmarkings.core.GridSize;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An image being composed, as layers rather than pixels.
 *
 * <p>This is the editor's model and also the template format: a template is just a
 * saved document, so there is no second representation to keep in step. It is
 * deliberately free of Minecraft and of any drawing library, so it can be tested
 * without either.
 *
 * <p>The canvas is expressed as a frame grid plus a resolution rather than raw
 * pixels, because the output is always going onto item frames and the grid is what
 * the person placing it actually cares about.
 */
public record Document(
        String name,
        GridSize grid,
        int pixelsPerFrame,
        int background,
        List<Layer> layers) {

    /** Fully transparent, so an unfilled canvas composites onto anything. */
    public static final int TRANSPARENT = 0x00000000;

    public Document {
        layers = layers == null ? List.of() : List.copyOf(layers);
        if (pixelsPerFrame <= 0) {
            throw new IllegalArgumentException("pixelsPerFrame must be positive, got " + pixelsPerFrame);
        }
    }

    public static Document blank(String name, GridSize grid, int pixelsPerFrame) {
        return new Document(name, grid, pixelsPerFrame, TRANSPARENT, List.of());
    }

    public int width() {
        return grid.columns() * pixelsPerFrame;
    }

    public int height() {
        return grid.rows() * pixelsPerFrame;
    }

    /** Scale from document pixels to one map frame's 128 pixels. */
    public double frameScale() {
        return pixelsPerFrame / (double) GridSize.MAP_PIXELS;
    }

    public Optional<Layer> byId(String id) {
        return layers.stream().filter(layer -> layer.id().equals(id)).findFirst();
    }

    public int indexOf(String id) {
        for (int index = 0; index < layers.size(); index++) {
            if (layers.get(index).id().equals(id)) {
                return index;
            }
        }
        return -1;
    }

    public Document withLayers(List<Layer> newLayers) {
        return new Document(name, grid, pixelsPerFrame, background, newLayers);
    }

    public Document withName(String newName) {
        return new Document(newName, grid, pixelsPerFrame, background, layers);
    }

    public Document withBackground(int argb) {
        return new Document(name, grid, pixelsPerFrame, argb, layers);
    }

    /**
     * Resizes the canvas without moving anything.
     *
     * <p>Layers keep their document coordinates deliberately. Rescaling them to the
     * new canvas would be a different operation, and silently doing it would ruin a
     * carefully placed composition the moment someone tried a different grid.
     */
    public Document withGrid(GridSize newGrid, int newPixelsPerFrame) {
        return new Document(name, newGrid, newPixelsPerFrame, background, layers);
    }

    /** Adds on top of the stack. */
    public Document add(Layer layer) {
        List<Layer> updated = new ArrayList<>(layers);
        updated.add(layer);
        return withLayers(updated);
    }

    public Document replace(Layer layer) {
        int index = indexOf(layer.id());
        if (index < 0) {
            return this;
        }
        List<Layer> updated = new ArrayList<>(layers);
        updated.set(index, layer);
        return withLayers(updated);
    }

    public Document remove(String id) {
        List<Layer> updated = new ArrayList<>(layers);
        updated.removeIf(layer -> layer.id().equals(id));
        return withLayers(updated);
    }

    /**
     * Moves a layer through the stack. {@code delta} is positive towards the front.
     * Out-of-range moves clamp rather than failing, since this is driven by buttons
     * that are easier to leave enabled.
     */
    public Document reorder(String id, int delta) {
        int index = indexOf(id);
        if (index < 0) {
            return this;
        }
        int target = Math.clamp(index + delta, 0, layers.size() - 1);
        if (target == index) {
            return this;
        }

        List<Layer> updated = new ArrayList<>(layers);
        updated.add(target, updated.remove(index));
        return withLayers(updated);
    }
}
