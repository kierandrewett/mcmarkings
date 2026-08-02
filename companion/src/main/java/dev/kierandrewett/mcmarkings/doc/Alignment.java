package dev.kierandrewett.mcmarkings.doc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Aligns and distributes a selection of layers.
 *
 * <p>Pure geometry over the document, so the behaviour can be pinned by tests
 * rather than judged by eye in a running game.
 *
 * <p>The reference an alignment works against depends on how many layers are
 * selected, which is the convention every drawing tool uses: one layer aligns to
 * the canvas, because aligning it to itself would do nothing, and several align to
 * the box enclosing them, because that is the arrangement being tidied.
 */
public final class Alignment {

    public enum Horizontal {
        LEFT, CENTRE, RIGHT
    }

    public enum Vertical {
        TOP, MIDDLE, BOTTOM
    }

    private Alignment() {
    }

    public static Document alignHorizontally(Document document, List<String> ids, Horizontal how) {
        List<Layer> selected = select(document, ids);
        if (selected.isEmpty()) {
            return document;
        }

        Layer.Bounds reference = referenceFor(document, selected);

        Document updated = document;
        for (Layer layer : selected) {
            Layer.Bounds bounds = layer.bounds();
            int x = switch (how) {
                case LEFT -> reference.x();
                case CENTRE -> reference.centreX() - bounds.width() / 2;
                case RIGHT -> reference.right() - bounds.width();
            };
            updated = updated.replace(layer.withBounds(bounds.movedTo(x, bounds.y())));
        }
        return updated;
    }

    public static Document alignVertically(Document document, List<String> ids, Vertical how) {
        List<Layer> selected = select(document, ids);
        if (selected.isEmpty()) {
            return document;
        }

        Layer.Bounds reference = referenceFor(document, selected);

        Document updated = document;
        for (Layer layer : selected) {
            Layer.Bounds bounds = layer.bounds();
            int y = switch (how) {
                case TOP -> reference.y();
                case MIDDLE -> reference.centreY() - bounds.height() / 2;
                case BOTTOM -> reference.bottom() - bounds.height();
            };
            updated = updated.replace(layer.withBounds(bounds.movedTo(bounds.x(), y)));
        }
        return updated;
    }

    /**
     * Spaces layers evenly between the outermost two, which stay put.
     *
     * <p>Gaps are equalised rather than centres, so differently sized layers end up
     * looking evenly spaced instead of merely being evenly indexed. Fewer than three
     * layers is a no-op: there is nothing between the ends to move.
     */
    public static Document distributeHorizontally(Document document, List<String> ids) {
        List<Layer> selected = sorted(select(document, ids), Comparator.comparingInt(layer -> layer.bounds().x()));
        if (selected.size() < 3) {
            return document;
        }

        Layer first = selected.getFirst();
        Layer last = selected.getLast();
        int span = last.bounds().right() - first.bounds().x();
        int occupied = selected.stream().mapToInt(layer -> layer.bounds().width()).sum();
        int gap = (span - occupied) / (selected.size() - 1);

        Document updated = document;
        int cursor = first.bounds().right() + gap;
        for (int index = 1; index < selected.size() - 1; index++) {
            Layer layer = selected.get(index);
            Layer.Bounds bounds = layer.bounds();
            updated = updated.replace(layer.withBounds(bounds.movedTo(cursor, bounds.y())));
            cursor += bounds.width() + gap;
        }
        return updated;
    }

    public static Document distributeVertically(Document document, List<String> ids) {
        List<Layer> selected = sorted(select(document, ids), Comparator.comparingInt(layer -> layer.bounds().y()));
        if (selected.size() < 3) {
            return document;
        }

        Layer first = selected.getFirst();
        Layer last = selected.getLast();
        int span = last.bounds().bottom() - first.bounds().y();
        int occupied = selected.stream().mapToInt(layer -> layer.bounds().height()).sum();
        int gap = (span - occupied) / (selected.size() - 1);

        Document updated = document;
        int cursor = first.bounds().bottom() + gap;
        for (int index = 1; index < selected.size() - 1; index++) {
            Layer layer = selected.get(index);
            Layer.Bounds bounds = layer.bounds();
            updated = updated.replace(layer.withBounds(bounds.movedTo(bounds.x(), cursor)));
            cursor += bounds.height() + gap;
        }
        return updated;
    }

    /** The smallest box containing every given layer. */
    public static Layer.Bounds boundingBox(List<Layer> layers) {
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;

        for (Layer layer : layers) {
            Layer.Bounds bounds = layer.bounds();
            left = Math.min(left, bounds.x());
            top = Math.min(top, bounds.y());
            right = Math.max(right, bounds.right());
            bottom = Math.max(bottom, bounds.bottom());
        }

        return new Layer.Bounds(left, top, right - left, bottom - top);
    }

    private static Layer.Bounds referenceFor(Document document, List<Layer> selected) {
        if (selected.size() == 1) {
            return new Layer.Bounds(0, 0, document.width(), document.height());
        }
        return boundingBox(selected);
    }

    /** Locked layers are skipped, since the point of locking is not to be moved. */
    private static List<Layer> select(Document document, List<String> ids) {
        List<Layer> selected = new ArrayList<>();
        for (String id : ids) {
            document.byId(id).filter(layer -> !layer.locked()).ifPresent(selected::add);
        }
        return selected;
    }

    private static List<Layer> sorted(List<Layer> layers, Comparator<Layer> comparator) {
        List<Layer> copy = new ArrayList<>(layers);
        copy.sort(comparator);
        return copy;
    }
}
