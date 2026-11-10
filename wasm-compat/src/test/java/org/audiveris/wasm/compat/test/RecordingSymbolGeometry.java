package org.audiveris.wasm.compat.test;

import java.awt.Dimension;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.*;
import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.ui.symbol.*;

/** JVM font adapter records exact resource values required by the original recognition calls. */
final class RecordingSymbolGeometry implements SymbolGeometry.Provider {
    private final SymbolGeometry.Provider original;
    private final Map<String, Map<String, Object>> records = new TreeMap<>();
    RecordingSymbolGeometry(SymbolGeometry.Provider original) { this.original = original; }
    Collection<Map<String, Object>> records() { return records.values(); }
    void recordPitchOffsets() {
        for (Shape shape : new Shape[]{Shape.G_CLEF, Shape.G_CLEF_SMALL, Shape.G_CLEF_8VA, Shape.G_CLEF_8VB,
                Shape.F_CLEF, Shape.F_CLEF_SMALL, Shape.F_CLEF_8VA, Shape.F_CLEF_8VB, Shape.FLAT}) {
            record(3, MusicFamily.Bravura, shape, 50, 0, 0,
                org.audiveris.omr.sig.inter.AbstractPitchedInter.getAreaPitchOffset(shape), 0, 0, 0);
        }
    }
    private void record(int kind, MusicFamily family, Shape shape, int interline, int num, int den, double... values) {
        String name = shape == null ? "" : shape.name();
        String key = kind + ":" + family + ":" + name + ":" + interline + ":" + num + ":" + den;
        Map<String, Object> entry = Map.of("kind", kind, "family", family.name(), "shape", name,
            "interline", interline, "num", num, "den", den, "values", values);
        records.putIfAbsent(key, entry);
    }
    @Override public Rectangle2D bounds(MusicFamily family, Shape shape, int interline) {
        var value = original.bounds(family, shape, interline);
        record(0, family, shape, interline, 0, 0, value.getX(), value.getY(), value.getWidth(), value.getHeight()); return value;
    }
    @Override public Point2D centroidOffset(MusicFamily family, Shape shape, int interline) {
        var value = original.centroidOffset(family, shape, interline);
        record(1, family, shape, interline, 0, 0, value.getX(), value.getY(), 0, 0); return value;
    }
    @Override public Dimension timeDimension(MusicFamily family, Shape shape, int interline, int numerator, int denominator) {
        var value = original.timeDimension(family, shape, interline, numerator, denominator);
        record(2, family, shape, interline, numerator, denominator, value.width, value.height, 0, 0); return value;
    }
}
