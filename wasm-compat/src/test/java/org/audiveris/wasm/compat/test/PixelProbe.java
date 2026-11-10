package org.audiveris.wasm.compat.test;

import ij.process.ByteProcessor;
import org.audiveris.omr.image.GlobalFilter;
import org.teavm.jso.JSExport;
import org.audiveris.wasm.compat.platform.GrayInput;

/** First real pixel-processing probe: no file decoder, image wrapper or desktop window. */
public final class PixelProbe {
    public static void main(String[] args) throws Exception {
        ownership(); String simple = threshold(), cases = globalCases();
        if (args.length > 0) { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "{\"threshold\":\"" + simple + "\",\"globalCases\":\"" + cases + "\"}\n"); }
        System.out.println("Gray input and global threshold oracle passed");
    }
    @JSExport public static boolean ownership() {
        byte[] source = {0, 64, (byte) 128, (byte) 255};
        GrayInput input = GrayInput.copyOf(2, 2, source); source[0] = 99;
        ByteProcessor processor = input.takeProcessor();
        if (processor.get(0) != 0 || !input.isConsumed() || input.width() != 2 || input.height() != 2) { throw new AssertionError("Input ownership changed"); }
        try { input.takeProcessor(); throw new AssertionError("Input reused"); } catch (IllegalStateException expected) { }
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
