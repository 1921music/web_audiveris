package org.audiveris.wasm.compat.test;

import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.*;
import org.audiveris.wasm.compat.platform.StrokePaths;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

public final class StrokePathProbe {
    static final int CASES = 256;
    public static void main(String[] args) { }
    @JSExport public static String cbrts() {
        StringBuilder out = new StringBuilder();
        for (double value : cbrtInputs()) {
            out.append(Long.toHexString(Double.doubleToLongBits(org.audiveris.wasm.compat.platform.StrokeRuntime.cbrt(value)))).append(',');
        }
        return out.toString();
    }
    static double[] cbrtInputs() {
        double[] edge = {0.0, -0.0, 1, -1, 8, -8, Double.MIN_VALUE, Double.MIN_NORMAL,
            Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN};
        long state = 0x42d72b938529L; double[] values = new double[4096];
        for (int test = 0; test < 4096; test++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            values[test] = test < edge.length ? edge[test] : Double.longBitsToDouble(state);
        }
        return values;
    }
    @JSExport public static String acosValues() {
        double[] edge = {0, -0.0, 1, -1, 0x1.fffffffffffffp-1, 0x1.0000000000001p0, -0x1.fffffffffffffp-1,
            -0x1.0000000000001p0, Double.MIN_VALUE, Double.MIN_NORMAL, Double.NaN,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        StringBuilder out = new StringBuilder(); long state = 0x2475238568L;
        for (int test = 0; test < 4096; test++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            double value = test < edge.length ? edge[test] : ((state >>> 11) * 0x1.0p-53) * 2 - 1;
            out.append(Long.toHexString(Double.doubleToLongBits(org.audiveris.wasm.compat.platform.StrokeRuntime.acos(value)))).append(',');
        }
        return out.toString();
    }
    @JSExport public static String ulps() {
        StringBuilder out = new StringBuilder();
        for (double value : cbrtInputs()) {
            out.append(Long.toHexString(Double.doubleToLongBits(org.audiveris.wasm.compat.platform.StrokeRuntime.ulp(value)))).append(',');
        }
        return out.toString();
    }
    @JSExport public static boolean nativeRasterRejected() {
        try { StrokePaths.nativeRaster(); return false; }
        catch (UnsupportedOperationException expected) { return true; }
    }
    static Shape shape(int test) {
        if (test % 4 == 0) { return new Line2D.Double(-1.25, 4.5, 38.25, 29.125); }
        return GrayPathProbe.shape(test);
    }
    static BasicStroke stroke(int test) {
        float[] widths = {0.25f, 1, 2.1f, 4.5f, 8};
        return new BasicStroke(widths[test % widths.length], test % 3, (test / 3) % 3,
            10, test % 11 == 0 ? new float[]{3, 1, 2} : null, test % 4);
    }
    static String geometry(Shape shape) {
        StringBuilder out = new StringBuilder(); double[] coords = new double[6];
        var iterator = shape.getPathIterator(null); out.append(iterator.getWindingRule()).append(':');
        while (!iterator.isDone()) {
            int kind = iterator.currentSegment(coords); out.append(kind).append('/');
            int count = kind == PathIterator.SEG_CLOSE ? 0 : kind == PathIterator.SEG_CUBICTO ? 6 : kind == PathIterator.SEG_QUADTO ? 4 : 2;
            for (int i = 0; i < count; i++) { out.append(Long.toHexString(Double.doubleToLongBits(coords[i]))).append(','); }
            out.append(';'); iterator.next();
        }
        return out.toString();
    }
    @JSExport public static String geometry(int test, boolean normalize) {
        if (test < 0 || test >= CASES) { throw new IllegalArgumentException("Stroke case"); }
        return geometry(normalize ? StrokePaths.normalized(shape(test), stroke(test)) : StrokePaths.widened(shape(test), stroke(test)));
    }
    static byte[] rasterJava(int test, boolean normalize) {
        Shape outline = normalize ? StrokePaths.normalized(shape(test), stroke(test)) : StrokePaths.widened(shape(test), stroke(test));
        byte[] pixels = GrayPathProbe.input(test);
        org.audiveris.wasm.compat.platform.GrayPathRaster.fill(pixels, GrayPathProbe.WIDTH, GrayPathProbe.HEIGHT, new Path2D.Float(outline));
        return pixels;
    }
    @JSExport public static Int8Array strokePixels(int test, boolean normalize) {
        if (test < 0 || test >= CASES) { throw new IllegalArgumentException("Stroke case"); }
        return Int8Array.copyFromJavaArray(rasterJava(test, normalize));
    }
}
