// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.Path2D;
import sun.awt.geom.PathConsumer2D;
import sun.java2d.marlin.DMarlinRenderingEngine;

/** Explicit Marlin geometry entry, without renderer discovery or native raster access. */
public final class StrokePaths {
    private static final DMarlinRenderingEngine ENGINE = new DMarlinRenderingEngine();
    private StrokePaths() { }
    /** Wasm profile guard; the JVM oracle deliberately does not call this diagnostic entry. */
    public static void nativeRaster() {
        ENGINE.getAATileGenerator(null, null, null, null, false, false, new int[4]);
    }
    public static Shape widened(Shape source, BasicStroke stroke) {
        return ENGINE.createStrokedShape(source, stroke.getLineWidth(), stroke.getEndCap(),
            stroke.getLineJoin(), stroke.getMiterLimit(), stroke.getDashArray(), stroke.getDashPhase());
    }
    /** Shape geometry used by normalized, non-antialiased drawing; rasterization is separate. */
    public static Path2D.Float normalized(Shape source, BasicStroke stroke) {
        Path2D.Float path = new Path2D.Float();
        ENGINE.strokeTo(source, null, stroke, false, true, false, new PathConsumer2D() {
            public void moveTo(float x, float y) { path.moveTo(x, y); }
            public void lineTo(float x, float y) { path.lineTo(x, y); }
            public void quadTo(float a, float b, float c, float d) { path.quadTo(a, b, c, d); }
            public void curveTo(float a, float b, float c, float d, float e, float f) { path.curveTo(a, b, c, d, e, f); }
            public void closePath() { path.closePath(); }
            public void pathDone() { }
            public long getNativeConsumer() { return 0; }
        });
        return path;
    }
}
