// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

/** JDK rounding semantics; rint uses f64.nearest directly on Wasm GC. */
public final class RoundingMath {
    private RoundingMath() { }
    public static double rint(double value) {
        if (Double.isNaN(value) || value == 0 || value >= 0x1.0p52 || value <= -0x1.0p52) { return value; }
        double lower = Math.floor(value), fraction = value - lower;
        double result = fraction < 0.5 ? lower : fraction > 0.5 ? lower + 1
                : (((long) lower & 1L) == 0 ? lower : lower + 1);
        return result == 0 ? (value < 0 ? -0.0 : 0.0) : result;
    }
    public static long round(double value) {
        if (Double.isNaN(value) || value >= 0x1.0p52 || value <= -0x1.0p52) { return (long) value; }
        double lower = Math.floor(value);
        return (long) (value - lower >= 0.5 ? lower + 1 : lower);
    }
    public static int round(float value) {
        if (Float.isNaN(value) || value >= 0x1.0p23f || value <= -0x1.0p23f) { return (int) value; }
        // Promote first: adding 0.5 in float would round values just below a tie upward.
        double promoted = value, lower = Math.floor(promoted);
        return (int) (promoted - lower >= 0.5 ? lower + 1 : lower);
    }
}
