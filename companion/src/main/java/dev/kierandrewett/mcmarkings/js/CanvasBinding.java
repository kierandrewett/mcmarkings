package dev.kierandrewett.mcmarkings.js;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

/**
 * Exposes a {@link CanvasApi} to a script as the {@code ctx} object.
 *
 * <p>Every method is a native Rhino function over plain values. Nothing here ever
 * hands a Java object to the script, which is what lets the class shutter deny all
 * Java access without breaking drawing.
 */
final class CanvasBinding {

    private CanvasBinding() {
    }

    static Scriptable create(Context cx, Scriptable scope, CanvasApi canvas) {
        Scriptable ctx = cx.newObject(scope);

        ScriptableObject.putProperty(ctx, "width", (double) canvas.width());
        ScriptableObject.putProperty(ctx, "height", (double) canvas.height());

        put(ctx, scope, "fillRect", 5, (c, s, self, args) -> {
            canvas.fillRect(
                    JsValues.numberArg(args, 0, "x"),
                    JsValues.numberArg(args, 1, "y"),
                    JsValues.numberArg(args, 2, "width"),
                    JsValues.numberArg(args, 3, "height"),
                    JsValues.stringArg(args, 4, "colour"));
            return Undefined.instance;
        });

        put(ctx, scope, "roundedRect", 6, (c, s, self, args) -> {
            canvas.roundedRect(
                    JsValues.numberArg(args, 0, "x"),
                    JsValues.numberArg(args, 1, "y"),
                    JsValues.numberArg(args, 2, "width"),
                    JsValues.numberArg(args, 3, "height"),
                    JsValues.numberArg(args, 4, "radius"),
                    JsValues.stringArg(args, 5, "colour"));
            return Undefined.instance;
        });

        put(ctx, scope, "strokeRect", 6, (c, s, self, args) -> {
            canvas.strokeRect(
                    JsValues.numberArg(args, 0, "x"),
                    JsValues.numberArg(args, 1, "y"),
                    JsValues.numberArg(args, 2, "width"),
                    JsValues.numberArg(args, 3, "height"),
                    JsValues.numberArg(args, 4, "thickness"),
                    JsValues.stringArg(args, 5, "colour"));
            return Undefined.instance;
        });

        put(ctx, scope, "strokeRoundedRect", 7, (c, s, self, args) -> {
            canvas.strokeRoundedRect(
                    JsValues.numberArg(args, 0, "x"),
                    JsValues.numberArg(args, 1, "y"),
                    JsValues.numberArg(args, 2, "width"),
                    JsValues.numberArg(args, 3, "height"),
                    JsValues.numberArg(args, 4, "radius"),
                    JsValues.numberArg(args, 5, "thickness"),
                    JsValues.stringArg(args, 6, "colour"));
            return Undefined.instance;
        });

        put(ctx, scope, "line", 6, (c, s, self, args) -> {
            canvas.line(
                    JsValues.numberArg(args, 0, "x1"),
                    JsValues.numberArg(args, 1, "y1"),
                    JsValues.numberArg(args, 2, "x2"),
                    JsValues.numberArg(args, 3, "y2"),
                    JsValues.numberArg(args, 4, "thickness"),
                    JsValues.stringArg(args, 5, "colour"));
            return Undefined.instance;
        });

        put(ctx, scope, "polygon", 2, (c, s, self, args) -> {
            canvas.polygon(points(JsValues.arg(args, 0)), JsValues.stringArg(args, 1, "colour"));
            return Undefined.instance;
        });

        put(ctx, scope, "circle", 4, (c, s, self, args) -> {
            canvas.circle(
                    JsValues.numberArg(args, 0, "cx"),
                    JsValues.numberArg(args, 1, "cy"),
                    JsValues.numberArg(args, 2, "radius"),
                    JsValues.stringArg(args, 3, "colour"));
            return Undefined.instance;
        });

        put(ctx, scope, "ring", 5, (c, s, self, args) -> {
            canvas.ring(
                    JsValues.numberArg(args, 0, "cx"),
                    JsValues.numberArg(args, 1, "cy"),
                    JsValues.numberArg(args, 2, "outerRadius"),
                    JsValues.numberArg(args, 3, "thickness"),
                    JsValues.stringArg(args, 4, "colour"));
            return Undefined.instance;
        });

        put(ctx, scope, "text", 4, (c, s, self, args) -> {
            CanvasApi.TextMetrics measured = canvas.text(
                    JsValues.stringArg(args, 0, "text"),
                    JsValues.numberArg(args, 1, "x"),
                    JsValues.numberArg(args, 2, "y"),
                    textOptions(JsValues.arg(args, 3)));
            return JsValues.object(c, s, "width", measured.width(), "height", measured.height());
        });

        put(ctx, scope, "measureText", 2, (c, s, self, args) -> {
            CanvasApi.TextMetrics measured =
                    canvas.measureText(JsValues.stringArg(args, 0, "text"), textOptions(JsValues.arg(args, 1)));
            return JsValues.object(c, s,
                    "width", measured.width(),
                    "height", measured.height(),
                    "ascent", measured.ascent(),
                    "descent", measured.descent());
        });

        put(ctx, scope, "drawImage", 5, (c, s, self, args) -> {
            canvas.drawImage(
                    JsValues.stringArg(args, 0, "path"),
                    JsValues.numberArg(args, 1, "x"),
                    JsValues.numberArg(args, 2, "y"),
                    JsValues.numberArg(args, 3, "width"),
                    JsValues.numberArg(args, 4, "height"));
            return Undefined.instance;
        });

        put(ctx, scope, "imageSize", 1, (c, s, self, args) -> {
            int[] size = canvas.imageSize(JsValues.stringArg(args, 0, "path"));
            return JsValues.object(c, s, "width", (double) size[0], "height", (double) size[1]);
        });

        put(ctx, scope, "save", 0, (c, s, self, args) -> {
            canvas.save();
            return Undefined.instance;
        });

        put(ctx, scope, "restore", 0, (c, s, self, args) -> {
            canvas.restore();
            return Undefined.instance;
        });

        put(ctx, scope, "translate", 2, (c, s, self, args) -> {
            canvas.translate(JsValues.numberArg(args, 0, "x"), JsValues.numberArg(args, 1, "y"));
            return Undefined.instance;
        });

        put(ctx, scope, "scale", 2, (c, s, self, args) -> {
            canvas.scale(JsValues.numberArg(args, 0, "sx"), JsValues.numberArg(args, 1, "sy"));
            return Undefined.instance;
        });

        put(ctx, scope, "rotate", 1, (c, s, self, args) -> {
            canvas.rotate(JsValues.numberArg(args, 0, "radians"));
            return Undefined.instance;
        });

        put(ctx, scope, "clip", 4, (c, s, self, args) -> {
            canvas.clip(
                    JsValues.numberArg(args, 0, "x"),
                    JsValues.numberArg(args, 1, "y"),
                    JsValues.numberArg(args, 2, "width"),
                    JsValues.numberArg(args, 3, "height"));
            return Undefined.instance;
        });

        put(ctx, scope, "clearClip", 0, (c, s, self, args) -> {
            canvas.clearClip();
            return Undefined.instance;
        });

        return ctx;
    }

    private static void put(Scriptable ctx, Scriptable scope, String name, int arity,
            org.mozilla.javascript.Callable body) {
        ScriptableObject.putProperty(ctx, name, JsValues.fn(scope, name, arity, body));
    }

    private static CanvasApi.TextOptions textOptions(Object raw) {
        if (!JsValues.present(raw)) {
            return CanvasApi.TextOptions.defaults();
        }
        if (!(raw instanceof Scriptable options)) {
            throw new IllegalArgumentException("options must be an object, got " + JsValues.text(raw));
        }
        Object font = JsValues.property(options, "font");
        Object size = JsValues.property(options, "size");
        Object colour = JsValues.property(options, "colour");
        Object align = JsValues.property(options, "align");
        Object baseline = JsValues.property(options, "baseline");
        Object tracking = JsValues.property(options, "tracking");
        Object scaleY = JsValues.property(options, "scaleY");
        return new CanvasApi.TextOptions(
                font == null ? null : JsValues.text(font),
                size == null ? CanvasApi.TextOptions.DEFAULT_SIZE : JsValues.number(size, "size"),
                colour == null ? null : JsValues.text(colour),
                align == null ? null : JsValues.text(align),
                baseline == null ? null : JsValues.text(baseline),
                tracking == null ? CanvasApi.TextOptions.DEFAULT_TRACKING : JsValues.number(tracking, "tracking"),
                scaleY == null ? CanvasApi.TextOptions.DEFAULT_SCALE_Y : JsValues.number(scaleY, "scaleY"));
    }

    private static double[][] points(Object raw) {
        if (!(raw instanceof NativeArray outer)) {
            throw new IllegalArgumentException("points must be an array of [x, y] pairs");
        }
        int count = (int) outer.getLength();
        double[][] points = new double[count][];
        for (int index = 0; index < count; index++) {
            Object entry = ScriptableObject.getProperty(outer, index);
            if (!(entry instanceof NativeArray pair) || pair.getLength() < 2) {
                throw new IllegalArgumentException("point " + index + " must be [x, y]");
            }
            points[index] = new double[] {
                JsValues.number(ScriptableObject.getProperty(pair, 0), "point " + index + " x"),
                JsValues.number(ScriptableObject.getProperty(pair, 1), "point " + index + " y"),
            };
        }
        return points;
    }
}
