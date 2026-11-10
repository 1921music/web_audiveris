// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

/** Logging destination for the Worker-only stroke geometry profile. */
public final class StrokeRuntime {
    private StrokeRuntime() { }
    /** JVM fallback; the geometry compiler profile selects the unchanged JDK FdLibm implementation. */
    public static double cbrt(double value) { return Math.cbrt(value); }
    public static double acos(double value) { return Math.acos(value); }
    public static double ulp(double value) {
        int exponent = (int)((Double.doubleToRawLongBits(value) >>> 52) & 0x7ff);
        if (exponent == 0x7ff) { return Math.abs(value); }
        if (exponent == 0) { return Double.MIN_VALUE; }
        return Double.longBitsToDouble(exponent > 52 ? (long)(exponent - 52) << 52 : 1L << (exponent - 1));
    }
    public static int rejectClip(Object region) {
        throw new UnsupportedOperationException("Worker stroke geometry requires a null native Region; raster clipping is separate");
    }
    public static void info(boolean enabled, String text) { if (enabled) { System.out.println("INFO: " + text); } }
    public static void exception(boolean enabled, String text, Throwable error) {
        if (enabled) { System.err.println("WARNING: " + text); error.printStackTrace(System.err); }
    }
    public static void settings(boolean enabled, String renderer) {
        info(enabled, renderer + ": Worker stroke geometry; native raster/statistics are unsupported");
    }
}
