/*
 * Copyright (c) 1998, 2025, Oracle and/or its affiliates. All rights reserved.
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

import java.awt.Shape;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/** Java adaptation of OpenJDK 25 ShapeSpanIterator.c's non-AA span converter.
 * JNI/lifetime management is replaced with owned Java arrays and a packed white raster sink.
 * Source: openjdk/jdk tag jdk-25+36, src/java.desktop/share/native/libawt/java2d/pipe/ShapeSpanIterator.c.
 * Integer overflow and float arithmetic in the stepping algorithm are intentional.
 */
public final class GraySpanRaster {
    private static final int ERRSTEP_MAX = 0x7fffffff;
    private static final class Segment {
        int x, y, lastY, error, bumpX, bumpError, wind;
    }
    private final int width, height;
    private final ArrayList<Segment> segments = new ArrayList<>();
    private float x, y, moveX, moveY, adjustX, adjustY;
    private GraySpanRaster(int width, int height) { this.width = width; this.height = height; }

    public static void fill(byte[] pixels, int width, int height, Shape shape, boolean normalize) {
        if (width <= 0 || height <= 0 || (long) width * height != pixels.length) {
            throw new IllegalArgumentException("Invalid packed gray raster");
        }
        var raster = new GraySpanRaster(width, height); var iterator = shape.getPathIterator(null);
        boolean evenOdd = iterator.getWindingRule() == PathIterator.WIND_EVEN_ODD; float[] coords = new float[6];
        while (!iterator.isDone()) {
            int type = iterator.currentSegment(coords);
            int count = switch (type) { case 0, 1 -> 2; case 2 -> 4; case 3 -> 6; case 4 -> 0; default -> throw new IllegalArgumentException("Invalid path segment"); };
            for (int i = 0; i < count; i++) {
                if (!Float.isFinite(coords[i]) || Math.abs(coords[i]) > 0x400000) {
                    throw new UnsupportedOperationException("Span coordinates outside the supported finite range");
                }
            }
            if (type == PathIterator.SEG_MOVETO) { raster.close(); }
            if (normalize && count > 0) {
                int end = count - 2;
                float nx = (float) Math.floor(coords[end] + .25f) + .25f, ny = (float) Math.floor(coords[end + 1] + .25f) + .25f;
                float dx = nx - coords[end], dy = ny - coords[end + 1]; coords[end] = nx; coords[end + 1] = ny;
                if (type == PathIterator.SEG_QUADTO) {
                    // Preserve the original ADJUST2 macro, including its X adjustment using newadjy.
                    coords[0] += (raster.adjustX + dy) / 2; coords[1] += (raster.adjustY + dy) / 2;
                } else if (type == PathIterator.SEG_CUBICTO) {
                    coords[0] += raster.adjustX; coords[1] += raster.adjustY; coords[2] += dx; coords[3] += dy;
                }
                raster.adjustX = dx; raster.adjustY = dy;
            }
            switch (type) {
                case PathIterator.SEG_MOVETO -> { raster.moveX = raster.x = coords[0]; raster.moveY = raster.y = coords[1]; }
                case PathIterator.SEG_LINETO -> { raster.line(raster.x, raster.y, coords[0], coords[1]); raster.x = coords[0]; raster.y = coords[1]; }
                case PathIterator.SEG_QUADTO -> { raster.quad(0, raster.x, raster.y, coords[0], coords[1], coords[2], coords[3]); raster.x = coords[2]; raster.y = coords[3]; }
                case PathIterator.SEG_CUBICTO -> { raster.cubic(0, raster.x, raster.y, coords[0], coords[1], coords[2], coords[3], coords[4], coords[5]); raster.x = coords[4]; raster.y = coords[5]; }
                case PathIterator.SEG_CLOSE -> raster.close();
                default -> throw new IllegalArgumentException("Invalid path segment");
            }
            iterator.next();
        }
        raster.close(); raster.paint(pixels, evenOdd);
    }

    private void close() {
        if (x != moveX || y != moveY) { line(x, y, moveX, moveY); x = moveX; y = moveY; }
    }
    private void line(float x0, float y0, float x1, float y1) {
        float minX = Math.min(x0, x1), maxX = Math.max(x0, x1), minY = Math.min(y0, y1), maxY = Math.max(y0, y1);
        if (maxY <= 0 || minY >= height || minX >= width) { return; }
        if (maxX <= 0) { append(maxX, y0, maxX, y1); } else { append(x0, y0, x1, y1); }
    }
    private void quad(int depth, float x0, float y0, float x1, float y1, float x2, float y2) {
        float minX = Math.min(x0, Math.min(x1, x2)), maxX = Math.max(x0, Math.max(x1, x2));
        float minY = Math.min(y0, Math.min(y1, y2)), maxY = Math.max(y0, Math.max(y1, y2));
        if (maxY <= 0 || minY >= height || minX >= width) { return; }
        if (maxX <= 0) { append(maxX, y0, maxX, y2); return; }
        if (depth < 10 && distanceSquared(x0, y0, x2, y2, x1, y1) > 1.0) {
            float cx1 = (x0 + x1) / 2f, cx2 = (x1 + x2) / 2f, cy1 = (y0 + y1) / 2f, cy2 = (y1 + y2) / 2f;
            x1 = (cx1 + cx2) / 2f; y1 = (cy1 + cy2) / 2f;
            quad(depth + 1, x0, y0, cx1, cy1, x1, y1); quad(depth + 1, x1, y1, cx2, cy2, x2, y2);
        } else { append(x0, y0, x2, y2); }
    }
    private void cubic(int depth, float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3) {
        float minX = Math.min(Math.min(x0, x1), Math.min(x2, x3)), maxX = Math.max(Math.max(x0, x1), Math.max(x2, x3));
        float minY = Math.min(Math.min(y0, y1), Math.min(y2, y3)), maxY = Math.max(Math.max(y0, y1), Math.max(y2, y3));
        if (maxY <= 0 || minY >= height || minX >= width) { return; }
        if (maxX <= 0) { append(maxX, y0, maxX, y3); return; }
        if (depth < 10 && (distanceSquared(x0, y0, x3, y3, x1, y1) > 1.0 || distanceSquared(x0, y0, x3, y3, x2, y2) > 1.0)) {
            float cx = (x1 + x2) / 2f, cy = (y1 + y2) / 2f;
            x1 = (x0 + x1) / 2f; x2 = (x2 + x3) / 2f; y1 = (y0 + y1) / 2f; y2 = (y2 + y3) / 2f;
            float cx12 = (x1 + cx) / 2f, cx21 = (cx + x2) / 2f, cy12 = (y1 + cy) / 2f, cy21 = (cy + y2) / 2f;
            cx = (cx12 + cx21) / 2f; cy = (cy12 + cy21) / 2f;
            cubic(depth + 1, x0, y0, x1, y1, cx12, cy12, cx, cy); cubic(depth + 1, cx, cy, cx21, cy21, x2, y2, x3, y3);
        } else { append(x0, y0, x3, y3); }
    }
    private static float distanceSquared(float x0, float y0, float x1, float y1, float px, float py) {
        x1 -= x0; y1 -= y0; px -= x0; py -= y0;
        float dot = px * x1 + py * y1, projected;
        if (dot <= 0.0) { projected = 0; }
        else {
            px = x1 - px; py = y1 - py; dot = px * x1 + py * y1;
            projected = dot <= 0.0 ? 0 : dot * dot / (x1 * x1 + y1 * y1);
        }
        return px * px + py * py - projected;
    }
    private void append(float x0, float y0, float x1, float y1) {
        int wind;
        if (y0 > y1) { float temp = x0; x0 = x1; x1 = temp; temp = y0; y0 = y1; y1 = temp; wind = -1; }
        else { wind = 1; }
        int startY = (int) Math.ceil(y0 - .5f), lastY = (int) Math.ceil(y1 - .5f);
        if (startY >= lastY || startY >= height || lastY <= 0) { return; }
        float dx = x1 - x0, dy = y1 - y0, slope = dx / dy, bump = startY + .5f - y0;
        x0 += bump * dx / dy;
        int startX = (int) Math.ceil(x0 - .5f);
        var segment = new Segment(); segment.x = startX; segment.y = startY; segment.lastY = lastY;
        segment.bumpX = (int) Math.floor(slope); segment.bumpError = (int) ((slope - Math.floor(slope)) * ERRSTEP_MAX);
        segment.error = (int) ((x0 - (startX - .5f)) * (double) ERRSTEP_MAX); segment.wind = wind;
        segments.add(segment);
    }
    private void paint(byte[] pixels, boolean evenOdd) {
        Segment[] table = segments.toArray(new Segment[0]); int count = table.length, low = 0;
        Arrays.sort(table, Comparator.comparingInt((Segment s) -> s.y).thenComparingInt(s -> s.x).thenComparingInt(s -> s.lastY));
        while (low < count && table[low].lastY <= 0) { low++; }
        int current = low, high = low, scanY = -1;
        while (low < count) {
            if (current < high) {
                Segment segment = table[current]; int x0 = segment.x, x1;
                if (x0 >= width) { current = high; continue; }
                if (x0 < 0) { x0 = 0; }
                if (evenOdd) { current += 2; x1 = current <= high ? table[current - 1].x : width; }
                else {
                    int wind = segment.wind; current++;
                    while (true) {
                        if (current >= high) { x1 = width; break; }
                        segment = table[current++]; wind += segment.wind;
                        if (wind == 0) { x1 = segment.x; break; }
                    }
                }
                if (x1 > width) { x1 = width; }
                if (x1 > x0) { Arrays.fill(pixels, scanY * width + x0, scanY * width + x1, (byte) 255); }
                continue;
            }
            if (++scanY >= height) { break; }
            int next = high;
            for (current = high - 1; current >= low; current--) {
                Segment segment = table[current]; if (segment.lastY > scanY) { table[--next] = segment; }
            }
            low = next;
            if (low == high && low < count && scanY < table[low].y) { scanY = table[low].y; }
            while (high < count && table[high].y <= scanY) { high++; }
            for (current = low; current < high; current++) {
                Segment segment = table[current]; int x0 = segment.x, y0 = segment.y, error = segment.error;
                if (++y0 == scanY) { x0 += segment.bumpX; error += segment.bumpError; x0 -= error >> 31; error &= ERRSTEP_MAX; }
                else {
                    long steps = (long) scanY - (y0 - 1); y0 = scanY; x0 += (int) (steps * segment.bumpX);
                    steps = error + steps * segment.bumpError; x0 += (int) (steps >> 31); error = (int) steps & ERRSTEP_MAX;
                }
                segment.x = x0; segment.y = y0; segment.error = error;
                int insert = current;
                while (insert > low && table[insert - 1].x > x0) { table[insert] = table[insert - 1]; insert--; }
                table[insert] = segment;
            }
            current = low;
        }
    }
}
