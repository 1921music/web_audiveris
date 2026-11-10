package org.audiveris.wasm.compat.test;

import java.util.*;
import java.nio.file.*;
import org.audiveris.wasm.compat.awt.image.Kernel;

/** Data, copy, clone and exception behavior against the original desktop Kernel API. */
public final class KernelOracle {
    public static void main(String[] args) throws Exception {
        if (!KernelContract.run()) { throw new AssertionError("Original Kernel contract"); }
        Random random = new Random(30197); int checks = 0;
        for (int i = 0; i < 512; i++) {
            int width = random.nextInt(15), height = random.nextInt(15), size = width * height;
            float[] data = new float[size + 5];
            for (int j = 0; j < data.length; j++) { data[j] = Float.intBitsToFloat(random.nextInt()); }
            var expected = new java.awt.image.Kernel(width, height, data); var actual = new Kernel(width, height, data);
            if (expected.getWidth() != actual.getWidth() || expected.getHeight() != actual.getHeight()
                    || expected.getXOrigin() != actual.getXOrigin() || expected.getYOrigin() != actual.getYOrigin()) { throw new AssertionError("Kernel dimensions"); }
            equal(expected.getKernelData(null), actual.getKernelData(null)); checks++;
            Arrays.fill(data, 123); equal(expected.getKernelData(null), actual.getKernelData(null)); checks++;
            float[] left = new float[size + 7], right = new float[size + 7]; Arrays.fill(left, 93); Arrays.fill(right, 93);
            if (expected.getKernelData(left) != left || actual.getKernelData(right) != right) { throw new AssertionError("Kernel destination identity"); }
            equal(left, right); checks++;
            var clone = (Kernel) actual.clone();
            if (clone == actual) { throw new AssertionError("Kernel clone identity"); }
            equal(((java.awt.image.Kernel) expected.clone()).getKernelData(null), clone.getKernelData(null)); checks++;
            if (size > 0) {
                String nativeError = error(() -> expected.getKernelData(new float[size - 1]));
                if (!nativeError.equals(error(() -> actual.getKernelData(new float[size - 1])))) { throw new AssertionError("Kernel short destination"); }
                checks++;
            }
        }
        for (int[] shape : new int[][]{{-1, 1}, {-1, -1}, {0, 4}, {65536, 65536}, {Integer.MAX_VALUE, Integer.MAX_VALUE}, {3, 5}}) {
            for (float[] data : new float[][]{null, new float[0], new float[1], new float[17]}) {
                String expected = error(() -> new java.awt.image.Kernel(shape[0], shape[1], data));
                String actual = error(() -> new Kernel(shape[0], shape[1], data));
                if (!expected.equals(actual)) { throw new AssertionError("Kernel constructor exceptions: " + expected + " / " + actual); }
                checks++;
            }
        }
        Tagged tagged = new Tagged(); var clone = (Tagged) tagged.clone();
        if (clone == tagged || clone.token != tagged.token || clone.number != 39) { throw new AssertionError("Kernel subclass clone"); } checks++;
        Path output = Path.of(args[0]); Files.createDirectories(output.getParent());
        Files.writeString(output, "{\"checks\":" + checks + ",\"randomKernels\":512,\"rawFloatBits\":true,\"overflowDimensions\":true,\"subclassClone\":true}\n");
        System.out.println("Kernel JVM conformance checks: " + checks);
    }
    private static final class Tagged extends Kernel {
        final Object token = new Object(); final int number = 39;
        Tagged() { super(1, 1, new float[]{1}); }
    }
    private static void equal(float[] expected, float[] actual) {
        if (expected.length != actual.length) { throw new AssertionError("Kernel array length"); }
        for (int i = 0; i < expected.length; i++) {
            if (Float.floatToRawIntBits(expected[i]) != Float.floatToRawIntBits(actual[i])) { throw new AssertionError("Kernel coefficient bits"); }
        }
    }
    private static String error(Runnable call) {
        try { call.run(); return "none"; } catch (RuntimeException failure) { return failure.getClass().getName(); }
    }
}
