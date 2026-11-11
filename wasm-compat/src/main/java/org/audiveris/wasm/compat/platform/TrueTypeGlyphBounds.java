// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.fontbox.ttf.GlyfCompositeDescript;
import org.apache.fontbox.ttf.TrueTypeFont;

/** Scaled quadratic outlines and fixed-point synthetic italic, without native font calls. */
public final class TrueTypeGlyphBounds {
    // OpenJDK 25 freetypeScaler.c setupTransform uses this 16.16 synthetic-italic shear.
    // https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.desktop/share/native/libfontmanager/freetypeScaler.c
    private static final long OBLIQUE_XY = 0x0366A;
    private record Point(long x, long y, boolean on) { }
    private final long[] min = {Long.MAX_VALUE, Long.MAX_VALUE}, max = {Long.MIN_VALUE, Long.MIN_VALUE};

    public static Rectangle2D measure(TrueTypeFont font, int gid, double scale, boolean syntheticItalic) throws IOException {
        var contours = points(font, gid, scale, 0);
        var projected = new ArrayList<List<Point>>(); var bounds = new TrueTypeGlyphBounds();
        for (var contour : contours) {
            var row = new ArrayList<Point>();
            for (Point p : contour) {
                Point q = new Point(p.x + (syntheticItalic ? multiplyDivide(p.y, OBLIQUE_XY, 65536) : 0), p.y, p.on);
                row.add(q); if (q.on) { bounds.add(q); }
            }
            projected.add(row);
        }
        for (var contour : projected) { bounds.walk(contour); }
        if (bounds.min[0] >= bounds.max[0] || bounds.min[1] >= bounds.max[1]) { return new Rectangle2D.Double(); }
        return new Rectangle2D.Double(bounds.min[0] / 64.0, bounds.min[1] / 64.0,
                (bounds.max[0] - bounds.min[0]) / 64.0, (bounds.max[1] - bounds.min[1]) / 64.0);
    }

    private static List<List<Point>> points(TrueTypeFont font, int gid, double scale, int depth) throws IOException {
        if (depth > 16) { throw new IllegalArgumentException("Composite font recursion"); }
        var glyph = font.getGlyph().getGlyph(gid); var result = new ArrayList<List<Point>>();
        if (glyph == null || glyph.getNumberOfContours() == 0) { return result; }
        var description = glyph.getDescription();
        if (description instanceof GlyfCompositeDescript composite) {
            for (var component : composite.getComponents()) {
                if (component.getXScale() != 1 || component.getYScale() != 1 || component.getScale01() != 0
                        || component.getScale10() != 0 || (component.getFlags() & 2) == 0) {
                    throw new UnsupportedOperationException("Research candidate supports translated components only");
                }
                long dx = fixed(component.getXTranslate() * scale), dy = fixed(component.getYTranslate() * scale);
                for (var contour : points(font, component.getGlyphIndex(), scale, depth + 1)) {
                    var moved = new ArrayList<Point>();
                    for (Point p : contour) { moved.add(new Point(p.x + dx, p.y + dy, p.on)); }
                    result.add(moved);
                }
            }
        } else {
            int start = 0;
            for (int contour = 0; contour < description.getContourCount(); contour++) {
                int end = description.getEndPtOfContours(contour); var row = new ArrayList<Point>();
                for (int i = start; i <= end; i++) {
                    row.add(new Point(fixed(description.getXCoordinate(i) * scale), fixed(description.getYCoordinate(i) * scale),
                            (description.getFlags(i) & 1) != 0));
                }
                result.add(row); start = end + 1;
            }
        }
        return result;
    }

    private void walk(List<Point> points) {
        if (points.isEmpty()) { return; }
        Point first = points.get(0), last = points.get(points.size() - 1), start;
        int index, limit = points.size();
        if (first.on) { start = first; index = 1; }
        else if (last.on) { start = last; index = 0; limit--; }
        else { start = middle(first, last); index = 0; }
        Point current = start; add(start);
        while (index < limit) {
            Point next = points.get(index++);
            if (next.on) { add(next); current = next; continue; }
            Point control = next;
            while (true) {
                Point following = index < limit ? points.get(index++) : start;
                if (following.on) { quadratic(current, control, following); current = following; break; }
                Point implied = middle(control, following); quadratic(current, control, implied);
                current = implied; control = following;
            }
        }
    }

    private void add(Point point) {
        min[0] = Math.min(min[0], point.x); max[0] = Math.max(max[0], point.x);
        min[1] = Math.min(min[1], point.y); max[1] = Math.max(max[1], point.y);
    }
    private void quadratic(Point from, Point control, Point to) {
        add(to); extremum(0, from.x, control.x, to.x); extremum(1, from.y, control.y, to.y);
    }
    private void extremum(int axis, long from, long control, long to) {
        if (control >= min[axis] && control <= max[axis]) { return; }
        long a = from - control, b = to - control;
        long value = control + multiplyDivide(a, b, a + b);
        min[axis] = Math.min(min[axis], value); max[axis] = Math.max(max[axis], value);
    }
    private static Point middle(Point a, Point b) { return new Point((a.x + b.x) / 2, (a.y + b.y) / 2, true); }
    private static long fixed(double value) { return (long) Math.copySign(Math.floor(Math.abs(value) * 64 + 0.5), value); }
    private static long multiplyDivide(long a, long b, long c) {
        if (c == 0) { throw new ArithmeticException("Degenerate quadratic extremum"); }
        long product = a * b, denominator = Math.abs(c);
        long magnitude = (Math.abs(product) + denominator / 2) / denominator;
        return (product < 0) != (c < 0) ? -magnitude : magnitude;
    }
}
