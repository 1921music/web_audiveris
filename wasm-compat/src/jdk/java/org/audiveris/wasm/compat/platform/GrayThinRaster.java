/*
 * Copyright (c) 2000, 2001, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2000, 2016, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2005, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation. Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.audiveris.wasm.compat.platform;

import java.awt.geom.Path2D;
import sun.java2d.loops.ProcessPath;

/** Original Java ProcessPath curve processing with a packed white pixel/line sink.
 * Line stepping follows JDK 25 DrawLine.c SETUP_BRESENHAM and LoopMacros.h's solid loop.
 * Native ProcessPath's additional integer-endpoint clipping is retained before line stepping.
 */
public final class GrayThinRaster {
    private static final int STROKE_DEFAULT = 0, STROKE_PURE = 2;
    private GrayThinRaster() { }
    public static void draw(byte[] pixels, int width, int height, Path2D.Float path, boolean pure) {
        if (width <= 0 || height <= 0 || (long) width * height != pixels.length) {
            throw new IllegalArgumentException("Invalid packed gray raster");
        }
        var iterator = path.getPathIterator(null); float[] coords = new float[6];
        while (!iterator.isDone()) {
            int count = switch (iterator.currentSegment(coords)) { case 0, 1 -> 2; case 2 -> 4; case 3 -> 6; default -> 0; };
            for (int i = 0; i < count; i++) {
                if (!Float.isFinite(coords[i]) || Math.abs(coords[i]) > 0x400000) {
                    throw new UnsupportedOperationException("Thin-path coordinates outside the supported finite range");
                }
            }
            iterator.next();
        }
        var handler = new ProcessPath.DrawHandler(0, 0, width, height, pure ? STROKE_PURE : STROKE_DEFAULT) {
            private final int[] line = new int[4];
            @Override public void drawPixel(int x, int y) {
                if (x >= 0 && x < width && y >= 0 && y < height) { pixels[y * width + x] = (byte) 255; }
            }
            @Override public void drawScanline(int x1, int x2, int y) { throw new UnsupportedOperationException("Thin path requested a fill scanline"); }
            @Override public void drawLine(int x0, int y0, int x1, int y1) {
                // The Java ProcessPath variant omits native PROCESS_LINE's TESTANDCLIP stage.
                line[0] = x0; line[1] = y0; line[2] = x1; line[3] = y1;
                if (!clip(line,1,0,3,2,yMinf+.5f,yMaxf+.5f) || !clip(line,3,2,1,0,yMinf+.5f,yMaxf+.5f)
                        || !clip(line,0,1,2,3,xMinf+.5f,xMaxf+.5f) || !clip(line,2,3,0,1,xMinf+.5f,xMaxf+.5f)) { return; }
                x0 = line[0]; y0 = line[1]; x1 = line[2]; y1 = line[3];
                int dx = x1 - x0, dy = y1 - y0, ax = Math.abs(dx), ay = Math.abs(dy);
                boolean xMajor = ax >= ay; int major = xMajor ? ax : ay, minor = xMajor ? ay : ax;
                int errorMajor = minor * 2, errorMinor = major * 2 - errorMajor, error = errorMajor - major;
                int sx = dx < 0 ? -1 : 1, sy = dy < 0 ? -1 : 1;
                for (int step = 0; step <= major; step++) {
                    drawPixel(x0, y0); if (step == major) { break; }
                    if (error < 0) {
                        if (xMajor) { x0 += sx; } else { y0 += sy; } error += errorMajor;
                    } else { x0 += sx; y0 += sy; error -= errorMinor; }
                }
            }
        };
        if (!ProcessPath.drawPath(handler, path, 0, 0)) { throw new IllegalArgumentException("Invalid thin raster path"); }
    }
    private static boolean clip(int[] line, int a, int b, int otherA, int otherB, float minimum, float maximum) {
        int a1 = line[a], a2 = line[otherA];
        if (a1 < minimum || a1 > maximum) {
            if (a1 < minimum ? a2 < minimum : a2 > maximum) { return false; }
            double target = a1 < minimum ? minimum : maximum;
            line[b] = (int) (line[b] + (target-a1) * (line[otherB]-line[b]) / (a2-a1));
            line[a] = (int) target;
        }
        return true;
    }
}
