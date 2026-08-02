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

    /**
     * The box every visible layer sits inside, or empty when there is nothing.
     *
     * <p>Hidden layers are left out: something switched off is not part of what the
     * document is, and including it would make a frame size suggestion answer for
     * content nobody can see.
     *
     * <p>Not clamped to the canvas. A layer dragged off the edge still counts,
     * because the usual reason to ask this is to work out what the canvas ought to
     * be rather than what it currently is.
     */
    public java.util.Optional<Layer.Bounds> contentBounds() {
        return boundsAround(layers.stream().filter(Layer::visible).toList());
    }

    /**
     * The box around the named layers, or empty when none of them are here.
     *
     * <p>Same question as {@link #contentBounds()} asked of a selection instead, so
     * it shares the arithmetic rather than growing a second copy of it in whatever
     * happens to need it. Unknown ids are skipped: a selection can outlive the layer
     * it referred to, and answering with a box that ignores the missing one is more
     * useful than refusing.
     */
    public java.util.Optional<Layer.Bounds> boundsOf(java.util.Collection<String> ids) {
        return boundsAround(layers.stream().filter(layer -> ids.contains(layer.id())).toList());
    }

    private static java.util.Optional<Layer.Bounds> boundsAround(List<Layer> of) {
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;

        for (Layer layer : of) {
            Layer.Bounds bounds = layer.bounds();
            left = Math.min(left, bounds.x());
            top = Math.min(top, bounds.y());
            right = Math.max(right, bounds.right());
            bottom = Math.max(bottom, bounds.bottom());
        }

        if (left == Integer.MAX_VALUE || right <= left || bottom <= top) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new Layer.Bounds(left, top, right - left, bottom - top));
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
