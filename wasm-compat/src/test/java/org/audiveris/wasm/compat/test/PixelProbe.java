package org.audiveris.wasm.compat.test;

import ij.process.ByteProcessor;
import org.audiveris.omr.image.GlobalFilter;
import org.audiveris.omr.image.VerticalFilter;
import org.audiveris.omr.image.RandomFilter;
import org.audiveris.omr.run.*;
import org.audiveris.wasm.compat.platform.HeadlessConfiguration;
import org.audiveris.wasm.compat.platform.GrayPreprocessor;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.JSExport;
import org.audiveris.wasm.compat.platform.GrayInput;

/** First real pixel-processing probe: no file decoder, image wrapper or desktop window. */
public final class PixelProbe {
    public static void main(String[] args) { }
    @JSExport public static Int8Array adaptivePage(int width, int height, Int8Array pixels, double mean, double deviation) {
        byte[] owned = pixels.copyToJavaArray();
        ByteProcessor binary = GrayPreprocessor.adaptive(GrayInput.takeOwnership(width, height, owned), mean, deviation);
        return Int8Array.copyFromJavaArray((byte[]) binary.getPixels());
    }
    public static String bulkOracle() {
        byte[] pixels = {0, 1, 127, (byte) 128, (byte) 129, (byte) 254, (byte) 255, 40};
        ByteProcessor binary = GrayPreprocessor.adaptive(GrayInput.copyOf(4, 2, pixels), 0.7, 0.9);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < pixels.length; i++) { if (i > 0) { result.append(','); } result.append(binary.get(i)); }
        return result.toString();
    }
    @JSExport public static void configure() { HeadlessConfiguration.configure(java.util.Map.of(), java.util.Map.of()); }
    @JSExport public static String adaptiveCases() {
        StringBuilder result = new StringBuilder();
        for (int[] size : new int[][] {{1, 1}, {1, 7}, {9, 1}, {16, 11}, {65, 49}}) {
            for (int pattern = 0; pattern < 3; pattern++) {
                byte[] pixels = fixture(size[0], size[1], pattern);
                ByteProcessor input = GrayInput.copyOf(size[0], size[1], pixels).takeProcessor();
                for (double[] coefficients : new double[][] {{0.7, 0.9}, {1, 0}, {0.95, 0.3}}) {
                    ByteProcessor vertical = new VerticalFilter(input, coefficients[0], coefficients[1]).filteredImage();
                    ByteProcessor random = new RandomFilter(input, coefficients[0], coefficients[1]).filteredImage();
                    for (int i = 0; i < pixels.length; i++) {
                        if (vertical.get(i) != random.get(i)) { throw new AssertionError("Adaptive implementations differ"); }
                        if (input.get(i) != (pixels[i] & 255)) { throw new AssertionError("Adaptive filter mutated input"); }
                        result.append(vertical.get(i) == 0 ? '0' : '1');
                    }
                    result.append(';');
                }
            }
        }
        return result.toString();
    }
    @JSExport public static String runCases() {
        StringBuilder result = new StringBuilder();
        for (int[] size : new int[][] {{1, 1}, {1, 7}, {9, 1}, {16, 11}, {65, 49}}) {
            for (int pattern = 0; pattern < 3; pattern++) {
                ByteProcessor input = GrayInput.copyOf(size[0], size[1], fixture(size[0], size[1], pattern)).takeProcessor();
                ByteProcessor binary = new GlobalFilter(input, 127).filteredImage();
                for (Orientation orientation : new Orientation[] {Orientation.VERTICAL, Orientation.HORIZONTAL}) {
                    RunTable table = new RunTableFactory(orientation).createTable(binary);
                    for (int y = 0; y < size[1]; y++) { for (int x = 0; x < size[0]; x++) {
                        if (table.get(x, y) != binary.get(x, y)) { throw new AssertionError("Run table changed pixels"); }
                    } }
                    result.append(table.getWidth()).append(',').append(table.getHeight()).append(',').append(table.getWeight()).append(':');
                    for (int position = 0; position < table.getSize(); position++) {
                        for (java.util.Iterator<Run> iterator = table.iterator(position); iterator.hasNext();) {
                            Run run = iterator.next(); result.append(run.getStart()).append(',').append(run.getLength()).append('/');
                        }
                        result.append(';');
                    }
                    result.append('|');
                }
            }
        }
        return result.toString();
    }
    private static byte[] fixture(int width, int height, int pattern) {
        byte[] pixels = new byte[width * height]; int state = 72191;
        for (int y = 0; y < height; y++) { for (int x = 0; x < width; x++) {
            state = state * 1664525 + 1013904223;
            pixels[y * width + x] = pattern == 0 ? 0 : pattern == 1 ? (byte) 255 : (byte) (state >>> 24);
        } }
        return pixels;
    }
    @JSExport public static boolean ownership() {
        byte[] source = {0, 64, (byte) 128, (byte) 255};
        GrayInput input = GrayInput.copyOf(2, 2, source); source[0] = 99;
        ByteProcessor processor = input.takeProcessor();
        if (processor.get(0) != 0 || !input.isConsumed() || input.width() != 2 || input.height() != 2) { throw new AssertionError("Input ownership changed"); }
        try { input.takeProcessor(); throw new AssertionError("Input reused"); } catch (IllegalStateException expected) { }
        byte[] owned = {5};
        if (GrayInput.takeOwnership(1, 1, owned).takeProcessor().getPixels() != owned) { throw new AssertionError("Ownership transfer copied pixels"); }
        GrayInput pending = GrayInput.copyOf(2, 2, source);
        try { GrayPreprocessor.global(pending, -1); throw new AssertionError("Invalid threshold accepted"); } catch (IllegalArgumentException expected) { }
        try { GrayPreprocessor.adaptive(pending, Double.NaN, 1); throw new AssertionError("NaN coefficient accepted"); } catch (IllegalArgumentException expected) { }
        if (pending.isConsumed()) { throw new AssertionError("Invalid options consumed input"); }
        for (int[] size : new int[][] {{0, 2}, {-1, 2}, {3, 2}, {Integer.MAX_VALUE, 3}}) {
            try { GrayInput.copyOf(size[0], size[1], source); throw new AssertionError("Invalid dimensions accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        return true;
    }
    @JSExport public static String globalCases() {
        StringBuilder result = new StringBuilder(); int state = 72191;
        for (int[] size : new int[][] {{1, 1}, {1, 7}, {9, 1}, {16, 11}, {65, 49}}) {
            byte[] pixels = new byte[size[0] * size[1]];
            for (int i = 0; i < pixels.length; i++) { state = state * 1664525 + 1013904223; pixels[i] = (byte) (state >>> 24); }
            ByteProcessor input = GrayInput.copyOf(size[0], size[1], pixels).takeProcessor();
            for (int threshold : new int[] {0, 1, 127, 128, 254, 255}) {
                ByteProcessor binary = new GlobalFilter(input, threshold).filteredImage();
                for (int i = 0; i < pixels.length; i++) {
                    if (input.get(i) != (pixels[i] & 255)) { throw new AssertionError("Filter mutated input"); }
                    result.append(binary.get(i) == 0 ? '0' : '1');
                }
                result.append(';');
            }
        }
        return result.toString();
    }
    @JSExport public static boolean fontRejected() {
        try { new ByteProcessor(1, 1).getFont(); return false; }
        catch (UnsupportedOperationException expected) { return true; }
    }
    @JSExport public static String threshold() {
        byte[] pixels = {0, 1, 127, (byte) 128, (byte) 129, (byte) 254, (byte) 255, 40};
        ByteProcessor input = new ByteProcessor(4, 2, pixels, null);
        ByteProcessor binary = new GlobalFilter(input, 128).filteredImage();
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < pixels.length; i++) {
            if (i > 0) { result.append(','); }
            result.append(binary.get(i));
            if (input.get(i) != (pixels[i] & 255)) { throw new AssertionError("Gray pixel changed"); }
        }
        return result.toString();
    }
}
