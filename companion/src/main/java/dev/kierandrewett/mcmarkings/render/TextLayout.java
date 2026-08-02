package dev.kierandrewett.mcmarkings.render;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.LineMetrics;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;

/**
 * The one place a run of text is measured and placed.
 *
 * <p>Two callers draw text: the JavaScript drawing API a generator scripts against,
 * and the layer document renderer. A generated sign and a hand-composed one have to
 * come out identical, so the tracking, vertical scale, alignment and baseline maths
 * lives here and both call it rather than each keeping its own copy that can drift.
 *
 * <p>Everything is stateless. The caller owns the {@link Graphics2D}, its rendering
 * hints, its clip and its composite; this only translates, scales, and draws.
 */
public final class TextLayout {

    /** Which end of the run sits on the given x. */
    public enum Align {
        LEFT, CENTRE, RIGHT
    }

    /** What the given y means vertically. */
    public enum Baseline {
        /** y is the top of the ascent. */
        TOP,
        /** y is halfway between ascent and descent. */
        MIDDLE,
        /** y is the alphabetic baseline, as in canvas 2D. */
        ALPHABETIC
    }

    /** Ascent, descent and height are already multiplied by the vertical scale. */
    public record Metrics(double width, double height, double ascent, double descent) {
    }

    private TextLayout() {
    }

    /**
     * Lays the string out once and applies tracking by shifting glyph positions, so the
     * width we report is by construction the width we draw. The end position is shifted
     * by only (n - 1) gaps: tracking sits between glyphs, not after the last one.
     */
    public static GlyphVector glyphs(Font font, FontRenderContext frc, String text, double tracking) {
        GlyphVector glyphs = font.createGlyphVector(frc, text);
        if (tracking == 0) {
            return glyphs;
        }
        int count = glyphs.getNumGlyphs();
        for (int index = 0; index <= count; index++) {
            Point2D position = glyphs.getGlyphPosition(index);
            double shift = tracking * Math.min(index, Math.max(count - 1, 0));
            glyphs.setGlyphPosition(index, new Point2D.Double(position.getX() + shift, position.getY()));
        }
        return glyphs;
    }

    public static Metrics measure(Font font, FontRenderContext frc, GlyphVector glyphs, String text, double scaleY) {
        // An empty string still has a meaningful height, which is what a caller
        // laying out evenly spaced rows actually wants.
        LineMetrics lineMetrics = font.getLineMetrics(text.isEmpty() ? "Hg" : text, frc);
        double ascent = lineMetrics.getAscent() * scaleY;
        double descent = lineMetrics.getDescent() * scaleY;
        double width = glyphs.getGlyphPosition(glyphs.getNumGlyphs()).getX();
        return new Metrics(width, ascent + descent, ascent, descent);
    }

    /** Lays out and measures in one go, for callers that only want the numbers. */
    public static Metrics measure(Font font, FontRenderContext frc, String text, double tracking, double scaleY) {
        return measure(font, frc, glyphs(font, frc, text, tracking), text, scaleY);
    }

    public static double leftOf(Align align, double x, double width) {
        return switch (align) {
            case CENTRE -> x - width / 2;
            case RIGHT -> x - width;
            case LEFT -> x;
        };
    }

    /**
     * Ascent and descent are already scaled, so the baseline lands where the stretched
     * text actually sits rather than where the unstretched font would.
     */
    public static double baselineOf(Baseline baseline, double y, Metrics metrics) {
        return switch (baseline) {
            case TOP -> y + metrics.ascent();
            case MIDDLE -> y + (metrics.ascent() - metrics.descent()) / 2;
            case ALPHABETIC -> y;
        };
    }

    /**
     * Draws an already laid out run so that {@code (x, y)} means what {@code align} and
     * {@code baseline} say it means.
     *
     * <p>The transform is restored afterwards, because the vertical scale must not leak
     * into whatever the caller draws next.
     */
    public static void draw(
            Graphics2D graphics,
            GlyphVector glyphs,
            Metrics metrics,
            double x,
            double y,
            Align align,
            Baseline baseline,
            double scaleY,
            Color colour) {
        AffineTransform previous = graphics.getTransform();
        graphics.translate(leftOf(align, x, metrics.width()), baselineOf(baseline, y, metrics));
        if (scaleY != 1.0) {
            graphics.scale(1.0, scaleY);
        }
        graphics.setColor(colour);
        graphics.drawGlyphVector(glyphs, 0f, 0f);
        graphics.setTransform(previous);
    }
}
