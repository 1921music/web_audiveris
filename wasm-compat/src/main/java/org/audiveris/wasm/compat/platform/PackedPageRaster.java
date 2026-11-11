// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import ij.process.ByteProcessor;
import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import org.audiveris.omr.glyph.Glyph;
import org.audiveris.omr.image.PageRaster;
import org.audiveris.omr.image.RunTableRaster;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.ui.symbol.MusicFamily;

/** White non-AA drawing directly into the cleaner's packed byte buffer. */
public final class PackedPageRaster implements PageRaster {
    private final ByteProcessor buffer;
    private final byte[] pixels;
    private final int width, height;
    private BasicStroke stroke = new BasicStroke();
    public PackedPageRaster(ByteProcessor buffer) {
        this.buffer = java.util.Objects.requireNonNull(buffer); width = buffer.getWidth(); height = buffer.getHeight();
        pixels = (byte[]) buffer.getPixels();
        if (width <= 0 || height <= 0 || (long) width * height != pixels.length) { throw new IllegalArgumentException("Invalid cleanup raster"); }
    }
    @Override public void initialize() { }
    @Override public Stroke getStroke() { return stroke; }
    @Override public void setStroke(Stroke value) {
        if (!(value instanceof BasicStroke basic)) { throw new UnsupportedOperationException("Cleanup requires BasicStroke"); }
        stroke = basic;
    }
    @Override public void fill(Shape shape) {
        if (stroke.getLineWidth() <= 1 && stroke.getDashArray() == null) {
            GrayPathRaster.fill(pixels, width, height, new Path2D.Float(shape));
        } else { GraySpanRaster.fill(pixels, width, height, shape, true); }
    }
    @Override public void draw(Shape shape) {
        if (stroke.getLineWidth() <= 1) {
            if (stroke.getDashArray() != null) { throw new UnsupportedOperationException("Dashed thin-stroke drawing needs its own verified geometry path"); }
            GrayThinRaster.draw(pixels, width, height, new Path2D.Float(shape), false);
        } else { GraySpanRaster.fill(pixels, width, height, StrokePaths.normalized(shape, stroke), false); }
    }
    @Override public void fillRect(int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) { return; }
        int x0 = (int) Math.max(0, Math.min(width, (long) x)), y0 = (int) Math.max(0, Math.min(height, (long) y));
        int x1 = (int) Math.max(x0, Math.min(width, (long) x + w)), y1 = (int) Math.max(y0, Math.min(height, (long) y + h));
        for (int row = y0; row < y1; row++) { java.util.Arrays.fill(pixels, row * width + x0, row * width + x1, (byte) 255); }
    }
    @Override public void glyph(Glyph glyph) {
        var origin = glyph.getTopLeft(); RunTableRaster.erase(buffer, glyph.getRunTable(), origin.x, origin.y);
    }
    @Override public void symbol(Inter inter, MusicFamily family, int pointSize) {
        throw new UnsupportedOperationException("Cleanup music-symbol painting is not configured: " + inter.getShape());
    }
}
