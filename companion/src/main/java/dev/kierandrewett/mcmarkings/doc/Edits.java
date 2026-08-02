package dev.kierandrewett.mcmarkings.doc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The operations the editor performs on a document.
 *
 * <p>Separated from the UI so the awkward parts, particularly the coordinate
 * changes in grouping, can be tested rather than discovered by eye. Every method
 * returns a new document and never mutates the one it is given, which is what lets
 * {@link History} keep states cheaply.
 *
 * <p>These are deliberately the small, fast actions: duplicate, nudge, group, raise.
 * They are what a composition is actually built out of, and each one needing a trip
 * to a menu is the difference between a tool you sink into and one you fight.
 */
public final class Edits {

    /**
     * How far a duplicate lands from its original, in document pixels.
     *
     * <p>Enough to be visibly a second copy rather than looking like nothing
     * happened, small enough that it is still obviously related to the first.
     */
    private static final int DUPLICATE_OFFSET = 16;

    /** Ids only have to be unique within a document, and monotonic is enough. */
    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    private Edits() {
    }

    public static String newId(String prefix) {
        return prefix + "-" + NEXT_ID.getAndIncrement();
    }

    /**
     * Copies layers, offset slightly, and returns the new document along with the
     * ids of the copies so the caller can select them.
     */
    public static Result duplicate(Document document, List<String> ids) {
        List<String> created = new ArrayList<>();
        Document updated = document;

        for (String id : ids) {
            Layer original = document.byId(id).orElse(null);
            if (original == null) {
                continue;
            }
            Layer copy = withNewIdentity(original, original.name() + " copy");
            Layer.Bounds bounds = copy.bounds();
            copy = copy.withBounds(bounds.movedTo(bounds.x() + DUPLICATE_OFFSET, bounds.y() + DUPLICATE_OFFSET));

            updated = updated.add(copy);
            created.add(copy.id());
        }

        return new Result(updated, created);
    }

    /**
     * Adds copies of layers that came from somewhere else.
     *
     * <p>Duplicating copies within one document; this takes layers held elsewhere,
     * which is what makes a clipboard work across two of them. Each copy gets a new
     * identity, groups included, so pasting twice cannot produce two layers sharing
     * an id and the second edit silently moving both.
     *
     * <p>Names are kept as they were rather than gaining "copy". Pasting into a
     * different document is moving a thing, not duplicating it, and the name is
     * usually the reason it was worth keeping.
     *
     * <p>Offset so a paste on top of its origin is visible and grabbable. Pasting
     * into an empty canvas lands where it was, which is almost always right when the
     * two documents are the same size.
     */
    public static Result paste(Document document, List<Layer> layers, boolean offset) {
        List<String> created = new ArrayList<>();
        Document updated = document;

        for (Layer layer : layers) {
            if (layer == null) {
                continue;
            }
            Layer copy = withNewIdentity(layer, layer.name());
            if (offset) {
                Layer.Bounds bounds = copy.bounds();
                copy = copy.withBounds(bounds.movedTo(
                        bounds.x() + DUPLICATE_OFFSET, bounds.y() + DUPLICATE_OFFSET));
            }

            updated = updated.add(copy);
            created.add(copy.id());
        }

        return new Result(updated, created);
    }

    /** Moves layers by a delta, for arrow keys. Locked layers stay put. */
    public static Document nudge(Document document, List<String> ids, int deltaX, int deltaY) {
        Document updated = document;
        for (String id : ids) {
            Layer layer = document.byId(id).filter(candidate -> !candidate.locked()).orElse(null);
            if (layer == null) {
                continue;
            }
            Layer.Bounds bounds = layer.bounds();
            updated = updated.replace(layer.withBounds(bounds.movedTo(bounds.x() + deltaX, bounds.y() + deltaY)));
        }
        return updated;
    }

    /**
     * Wraps layers in a group sized to enclose them.
     *
     * <p>The subtle part. A group's children are positioned relative to its content
     * box, so their absolute coordinates have to be rebased on the way in, and
     * unrebased on the way out. Getting this wrong makes everything jump the moment
     * it is grouped, which is exactly the sort of bug that is obvious on screen and
     * invisible in the code.
     *
     * <p>The group takes the position of the topmost member in the stack, so
     * grouping does not reorder anything visually.
     */
    public static Result group(Document document, List<String> ids, Insets padding) {
        List<Layer> members = ordered(document, ids);
        if (members.size() < 2) {
            // A group of one is just an extra level to click through.
            return new Result(document, List.of());
        }

        Layer.Bounds box = Alignment.boundingBox(members);
        Layer.Bounds outer = new Layer.Bounds(
                box.x() - padding.left(),
                box.y() - padding.top(),
                box.width() + padding.horizontal(),
                box.height() + padding.vertical());

        List<Layer> children = new ArrayList<>();
        for (Layer member : members) {
            Layer.Bounds bounds = member.bounds();
            children.add(member.withBounds(bounds.movedTo(bounds.x() - box.x(), bounds.y() - box.y())));
        }

        String groupId = newId("group");
        Layer.Group created = new Layer.Group(groupId, "Group", outer, true, false, 1.0,
                Insets.NONE, padding, children);

        int insertAt = highestIndex(document, ids);
        List<Layer> layers = new ArrayList<>(document.layers());
        layers.removeIf(layer -> ids.contains(layer.id()));
        layers.add(Math.clamp(insertAt - countBelow(document, ids, insertAt), 0, layers.size()), created);

        return new Result(document.withLayers(layers), List.of(groupId));
    }

    /** Unwraps a group, restoring its children's absolute positions. */
    public static Result ungroup(Document document, String groupId) {
        Layer layer = document.byId(groupId).orElse(null);
        if (!(layer instanceof Layer.Group group)) {
            return new Result(document, List.of());
        }

        Layer.Bounds content = group.bounds().shrunkBy(group.padding());

        List<Layer> restored = new ArrayList<>();
        for (Layer child : group.children()) {
            Layer.Bounds bounds = child.bounds();
            restored.add(child.withBounds(bounds.movedTo(
                    bounds.x() + content.x(), bounds.y() + content.y())));
        }

        List<Layer> layers = new ArrayList<>(document.layers());
        int index = document.indexOf(groupId);
        layers.remove(index);
        layers.addAll(index, restored);

        return new Result(document.withLayers(layers),
                restored.stream().map(Layer::id).toList());
    }

    public static Document bringToFront(Document document, List<String> ids) {
        List<Layer> moving = ordered(document, ids);
        List<Layer> layers = new ArrayList<>(document.layers());
        layers.removeAll(moving);
        layers.addAll(moving);
        return document.withLayers(layers);
    }

    public static Document sendToBack(Document document, List<String> ids) {
        List<Layer> moving = ordered(document, ids);
        List<Layer> layers = new ArrayList<>(document.layers());
        layers.removeAll(moving);
        layers.addAll(0, moving);
        return document.withLayers(layers);
    }

    /** Sizes a layer to the whole canvas, for a background. */
    public static Document fitToCanvas(Document document, String id) {
        return document.byId(id)
                .map(layer -> document.replace(
                        layer.withBounds(new Layer.Bounds(0, 0, document.width(), document.height()))))
                .orElse(document);
    }

    public static Document remove(Document document, List<String> ids) {
        Document updated = document;
        for (String id : ids) {
            updated = updated.remove(id);
        }
        return updated;
    }

    /** A document plus whatever the operation created, for the caller to select. */
    public record Result(Document document, List<String> createdIds) {
    }

    /** Layers in stack order rather than the order the caller happened to click them. */
    private static List<Layer> ordered(Document document, List<String> ids) {
        Set<String> wanted = new LinkedHashSet<>(ids);
        List<Layer> layers = new ArrayList<>();
        for (Layer layer : document.layers()) {
            if (wanted.contains(layer.id())) {
                layers.add(layer);
            }
        }
        return layers;
    }

    private static int highestIndex(Document document, List<String> ids) {
        int highest = 0;
        for (String id : ids) {
            highest = Math.max(highest, document.indexOf(id));
        }
        return highest;
    }

    private static int countBelow(Document document, List<String> ids, int index) {
        int below = 0;
        for (String id : ids) {
            int at = document.indexOf(id);
            if (at >= 0 && at < index) {
                below++;
            }
        }
        return below;
    }

    /** A copy needs its own identity, recursively, or ids collide inside groups. */
    private static Layer withNewIdentity(Layer layer, String name) {
        return switch (layer) {
            case Layer.Image image -> new Layer.Image(newId("image"), name, image.bounds(), image.visible(),
                    image.locked(), image.opacity(), image.margins(), image.repoPath(), image.fit());
            case Layer.Text text -> new Layer.Text(newId("text"), name, text.bounds(), text.visible(),
                    text.locked(), text.opacity(), text.margins(), text.text(), text.font(), text.size(),
                    text.colour(), text.horizontalAlign(), text.verticalAlign(), text.lineGap(),
                    text.tracking(), text.verticalScale());
            case Layer.Shape shape -> new Layer.Shape(newId("shape"), name, shape.bounds(), shape.visible(),
                    shape.locked(), shape.opacity(), shape.margins(), shape.padding(), shape.fill(),
                    shape.cornerRadius(), shape.borderColour(), shape.borderWidth());
            case Layer.Group group -> new Layer.Group(newId("group"), name, group.bounds(), group.visible(),
                    group.locked(), group.opacity(), group.margins(), group.padding(),
                    group.children().stream().map(child -> withNewIdentity(child, child.name())).toList());
        };
    }
}
