// SPDX-License-Identifier: AGPL-3.0-or-later
// Adapted from Audiveris BasicLine (Audiveris contributors).
package org.audiveris.wasm;

/** Upstream least-squares line model without AWT points, rectangles, or logging. */
public final class LineFit {
    private int count;
    private double sx, sy, sx2, sy2, sxy;
    private double a, b, c;
    private boolean dirty = true;

    public void includePoint(double x, double y) {
        count++;
        sx += x; sy += y; sx2 += x * x; sy2 += y * y; sxy += x * y;
        dirty = true;
    }

    public void include(LineFit other) {
        count = Math.addExact(count, other.count);
        sx += other.sx; sy += other.sy; sx2 += other.sx2; sy2 += other.sy2; sxy += other.sxy;
        dirty = true;
    }

    private void compute() {
        if (count < 2) { throw new IllegalStateException("At least two points are required"); }
        double hDen = count * sx2 - sx * sx;
        double vDen = count * sy2 - sy * sy;
        if (Math.abs(hDen) >= Math.abs(vDen)) {
            a = (count * sxy - sx * sy) / hDen;
            b = -1;
            c = (sy * sx2 - sx * sxy) / hDen;
        } else {
            a = -1;
            b = (count * sxy - sx * sy) / vDen;
            c = (sx * sy2 - sy * sxy) / vDen;
        }
        double norm = Math.hypot(a, b);
        a /= norm; b /= norm; c /= norm;
        if (!Double.isFinite(a) || !Double.isFinite(b) || !Double.isFinite(c)) {
            throw new IllegalStateException("Line parameters are undefined");
        }
        dirty = false;
    }

    public int count() { return count; }
    public double getSlope() { if (dirty) { compute(); } return -a / b; }
    public double getInvertedSlope() { if (dirty) { compute(); } return -b / a; }
    public double yAtX(double x) {
        if (count == 1) { return sy; }
        if (dirty) { compute(); }
        if (b == 0) { throw new IllegalStateException("Line is vertical"); }
        return (-a * x - c) / b;
    }
    public double xAtY(double y) {
        if (count == 1) { return sx; }
        if (dirty) { compute(); }
        if (a == 0) { throw new IllegalStateException("Line is horizontal"); }
        return (-b * y - c) / a;
    }
    public double distanceOf(double x, double y) {
        if (dirty) { compute(); }
        return a * x + b * y + c;
    }

    public double meanDistance() {
        if (dirty) { compute(); }
        double squared = (a * a * sx2 + b * b * sy2 + c * c * count + 2 * a * b * sxy
                + 2 * a * c * sx + 2 * b * c * sy) / count;
        return Math.sqrt(Math.max(0, squared));
    }

    /** [section count, (pointCount,valid,distance(0,0),distance(1,0),distance(0,1),rms)...]. */
    public static double[] sections(int[] sections) {
        int total = sections[0], base = total + 2;
        double[] output = new double[1 + 6 * total];
        output[0] = total;
        for (int section = 0; section < total; section++) {
            int cursor = base + sections[section + 1];
            int first = sections[cursor++], runs = sections[cursor++];
            LineFit line = new LineFit();
            for (int row = 0; row < runs; row++) {
                int start = sections[cursor++], length = sections[cursor++];
                // Keep upstream point accumulation order before optimizing this hot path.
                for (int x = start; x < start + length; x++) { line.includePoint(x, first + row); }
            }
            int target = 1 + 6 * section;
            output[target] = line.count();
            if (line.count() >= 2) {
                output[target + 1] = 1;
                output[target + 2] = line.distanceOf(0, 0);
                output[target + 3] = line.distanceOf(1, 0);
                output[target + 4] = line.distanceOf(0, 1);
                output[target + 5] = line.meanDistance();
            }
        }
        return output;
    }
}
