// SPDX-License-Identifier: AGPL-3.0-or-later
// Adapted from Audiveris NaturalSpline/GeoPath (Audiveris contributors).
package org.audiveris.wasm;

/** Upstream line/quadratic/cubic interpolation as a function, without java.awt.Shape. */
public final class NaturalCurve {
    private final double[] axis, values, firstControl, secondControl;
    private final int degree;

    public NaturalCurve(double[] axis, double[] values) {
        if (axis == null || values == null || axis.length != values.length || axis.length < 2) {
            throw new IllegalArgumentException("At least two matching coordinate arrays are required");
        }
        for (int i = 0; i < axis.length; i++) {
            if (!Double.isFinite(axis[i]) || !Double.isFinite(values[i]) || (i > 0 && axis[i] <= axis[i - 1])) {
                throw new IllegalArgumentException("Finite coordinates and strictly increasing axis required");
            }
        }
        int n = axis.length - 1;
        if (n == 2) {
            // Upstream represents three knots as one quadratic, including its parameter convention.
            this.axis = new double[] {axis[0], axis[2]};
            this.values = new double[] {values[0], values[2]};
            firstControl = new double[] {2 * values[1] - (values[0] + values[2]) / 2};
            secondControl = null;
            degree = 2;
        } else {
            this.axis = axis.clone();
            this.values = values.clone();
            degree = n == 1 ? 1 : 3;
            firstControl = new double[n];
            secondControl = new double[n];
            if (degree == 3) {
                double[] derivatives = derivatives(values);
                for (int i = 0; i < n; i++) {
                    firstControl[i] = values[i] + derivatives[i] / 3;
                    secondControl[i] = values[i + 1] - derivatives[i + 1] / 3;
                }
            }
        }
    }

    private static double[] derivatives(double[] values) {
        int n = values.length - 1;
        double[] gamma = new double[n + 1], delta = new double[n + 1], result = new double[n + 1];
        gamma[0] = .5;
        for (int i = 1; i < n; i++) { gamma[i] = 1 / (4 - gamma[i - 1]); }
        gamma[n] = 1 / (2 - gamma[n - 1]);
        delta[0] = 3 * (values[1] - values[0]) * gamma[0];
        for (int i = 1; i < n; i++) { delta[i] = (3 * (values[i + 1] - values[i - 1]) - delta[i - 1]) * gamma[i]; }
        delta[n] = (3 * (values[n] - values[n - 1]) - delta[n - 1]) * gamma[n];
        result[n] = delta[n];
        for (int i = n - 1; i >= 0; i--) { result[i] = delta[i] - gamma[i] * result[i + 1]; }
        return result;
    }

    private int segment(double coordinate) {
        if (!Double.isFinite(coordinate) || coordinate < axis[0] || coordinate > axis[axis.length - 1]) {
            throw new IllegalArgumentException("Coordinate outside curve range");
        }
        // Choose the segment on the left at a shared endpoint, like GeoPath.getX/YSegment.
        int segment = 0;
        while (segment + 1 < axis.length - 1 && coordinate > axis[segment + 1]) { segment++; }
        return segment;
    }

    public double valueAt(double coordinate) {
        int i = segment(coordinate);
        double t = (coordinate - axis[i]) / (axis[i + 1] - axis[i]), u = 1 - t;
        double first = values[i], last = values[i + 1];
        if (degree == 1) { return first + t * (last - first); }
        if (degree == 2) { return first * u * u + 2 * firstControl[i] * t * u + last * t * t; }
        return first * u * u * u + 3 * firstControl[i] * t * u * u
                + 3 * secondControl[i] * t * t * u + last * t * t * t;
    }

    public double slopeAt(double coordinate) {
        int i = segment(coordinate);
        double span = axis[i + 1] - axis[i], t = (coordinate - axis[i]) / span, u = 1 - t;
        double first = values[i], last = values[i + 1];
        if (degree == 1) { return (last - first) / span; }
        if (degree == 2) { return (-2 * first * u + 2 * firstControl[i] * (1 - 2 * t) + 2 * last * t) / span; }
        return (-3 * first * u * u + 3 * firstControl[i] * (u * u - 2 * u * t)
                + 3 * secondControl[i] * (2 * t * u - t * t) + 3 * last * t * t) / span;
    }

    public double valueAtExt(double coordinate) {
        int last = axis.length - 1;
        if (coordinate < axis[0] || coordinate > axis[last]) {
            double slope = (values[last] - values[0]) / (axis[last] - axis[0]);
            return values[0] + slope * (coordinate - axis[0]);
        }
        return valueAt(coordinate);
    }
}
