// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.awt.Dimension;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.*;
import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.ui.symbol.MusicFamily;
import org.audiveris.omr.ui.symbol.SymbolGeometry;

/** Host font resources: immutable entries may be added between page-processing calls. */
public final class HeadlessSymbolGeometry implements SymbolGeometry.Provider {
    private record Measurement(double a, double b, double c, double d) { }
    private static final HeadlessSymbolGeometry INSTANCE = new HeadlessSymbolGeometry();
    private static final Map<String, Measurement> entries = new HashMap<>();
    private HeadlessSymbolGeometry() { }
    public static SymbolGeometry.Provider provider() { return INSTANCE; }
    public static void register(int kind, String family, String shape, int interline, int numerator, int denominator,
            double a, double b, double c, double d) {
        Objects.requireNonNull(family); Objects.requireNonNull(shape);
        if (kind < 0 || kind > 3 || family.isBlank() || interline <= 0) { throw new IllegalArgumentException("Invalid font measurement key"); }
        for (double value : new double[]{a, b, c, d}) { if (!Double.isFinite(value)) { throw new IllegalArgumentException("Non-finite font measurement"); } }
        if (kind == 0 && (c < 0 || d < 0)) { throw new IllegalArgumentException("Negative glyph bounds"); }
        if (kind == 2 && (a < 0 || b < 0 || a != Math.rint(a) || b != Math.rint(b)
                || a > Integer.MAX_VALUE || b > Integer.MAX_VALUE)) { throw new IllegalArgumentException("Invalid time dimensions"); }
        String key = key(kind, family, shape, interline, numerator, denominator);
        if (entries.containsKey(key)) { throw new IllegalArgumentException("Font measurement already installed: " + key); }
        entries.put(key, new Measurement(a, b, c, d));
    }
    private static String key(int kind, String family, String shape, int interline, int numerator, int denominator) {
        return kind + ":" + family + ":" + shape + ":" + interline + ":" + numerator + ":" + denominator;
    }
    private static Measurement require(int kind, MusicFamily family, Shape shape, int interline, int numerator, int denominator) {
        String key = key(kind, Objects.requireNonNull(family).name(), shape == null ? "" : shape.name(), interline, numerator, denominator);
        Measurement value = entries.get(key);
        if (value == null) { throw new UnsupportedOperationException("Host must supply symbol geometry: " + key); }
        return value;
    }
    /** Original AbstractPitchedInter uses Bravura at 200 points, i.e. reference interline 50. */
    public static void fillPitchOffsets(Map<Shape, Double> target) {
        for (Shape shape : new Shape[]{Shape.G_CLEF, Shape.G_CLEF_SMALL, Shape.G_CLEF_8VA, Shape.G_CLEF_8VB,
                Shape.F_CLEF, Shape.F_CLEF_SMALL, Shape.F_CLEF_8VA, Shape.F_CLEF_8VB, Shape.FLAT}) {
            target.put(shape, require(3, MusicFamily.Bravura, shape, 50, 0, 0).a);
        }
    }
    @Override public Rectangle2D bounds(MusicFamily family, Shape shape, int interline) {
        var value = require(0, family, shape, interline, 0, 0); return new Rectangle2D.Double(value.a, value.b, value.c, value.d);
    }
    @Override public Point2D centroidOffset(MusicFamily family, Shape shape, int interline) {
        var value = require(1, family, shape, interline, 0, 0); return new Point2D.Double(value.a, value.b);
    }
    @Override public Dimension timeDimension(MusicFamily family, Shape shape, int interline, int numerator, int denominator) {
        var value = require(2, family, shape, interline, numerator, denominator); return new Dimension((int) value.a, (int) value.b);
    }
}
