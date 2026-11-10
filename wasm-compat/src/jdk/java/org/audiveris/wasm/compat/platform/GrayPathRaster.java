// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.awt.geom.Path2D;
import java.util.Arrays;
import sun.java2d.loops.ProcessPath;

/** Non-antialiased Path2D fills using the JDK's unchanged Java ProcessPath implementation.
 * This bridge is compiled separately with the required module export; no reflection or JNI is used.
 * This is the thin-stroke FillPath operation, not rectangle fast paths or wide-stroke span filling.
 */
public final class GrayPathRaster {
    private GrayPathRaster() { }
    public static void fill(byte[] pixels, int width, int height, Path2D.Float path) {
        if (width <= 0 || height <= 0 || (long)width * height != pixels.length) {
            throw new IllegalArgumentException("Invalid packed gray raster");
        }
        ProcessPath.DrawHandler handler = new ProcessPath.DrawHandler(0, 0, width, height) {
            @Override public void drawScanline(int x1, int x2, int y) {
                if (y < 0 || y >= height) { throw new IllegalStateException("Unclipped path scanline"); }
                int left = Math.max(0, x1), right = Math.min(width - 1, x2);
                if (left <= right) { Arrays.fill(pixels, y * width + left, y * width + right + 1, (byte)255); }
            }
            @Override public void drawPixel(int x, int y) {
                throw new UnsupportedOperationException("Path fill requested drawPixel");
            }
            @Override public void drawLine(int x1, int y1, int x2, int y2) {
                throw new UnsupportedOperationException("Path fill requested drawLine");
            }
        };
        if (!ProcessPath.fillPath(handler, path, 0, 0)) {
            throw new IllegalArgumentException("Invalid raster path");
        }
    }
}
