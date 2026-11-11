// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import java.awt.geom.PathIterator;
import java.nio.file.*;
import java.util.Arrays;

/** Reflection is confined to this JVM oracle, calling the JDK's native span converter. */
public final class GraySpanOracle {
    public static void main(String[] args) throws Exception {
        // Initialize libawt through its public path before loading the native iterator directly.
        new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_BYTE_GRAY).createGraphics().dispose();
        Class<?> type = Class.forName("sun.java2d.pipe.ShapeSpanIterator");
        var constructor = type.getConstructor(boolean.class);
        var clip = type.getMethod("setOutputAreaXYWH", int.class, int.class, int.class, int.class);
        var append = type.getMethod("appendPath", PathIterator.class);
        var next = type.getMethod("nextSpan", int[].class); var dispose = type.getMethod("dispose");
        int width = GrayPathProbe.WIDTH, height = GrayPathProbe.HEIGHT, size = width * height;
        byte[] all = new byte[GraySpanProbe.CASES * size]; var hashes = new StringBuilder();
        for (int test = 0; test < GraySpanProbe.CASES; test++) {
            byte[] expected = GrayPathProbe.input(test / 2); Object nativeSpans = constructor.newInstance((test & 1) != 0);
            try {
                clip.invoke(nativeSpans, 0, 0, width, height); append.invoke(nativeSpans, GrayPathProbe.shape(test / 2).getPathIterator(null));
                int[] box = new int[4];
                while ((boolean) next.invoke(nativeSpans, (Object) box)) {
                    for (int y = box[1]; y < box[3]; y++) { Arrays.fill(expected, y * width + box[0], y * width + box[2], (byte) 255); }
                }
            } finally { dispose.invoke(nativeSpans); }
            if (!Arrays.equals(expected, GraySpanProbe.portable(test))) { throw new AssertionError("Native span pixels: " + test); }
            hashes.append(Arrays.hashCode(expected)).append(','); System.arraycopy(expected, 0, all, test * size, size);
        }
        GraySpanProbe.guards(); Path output = Path.of(args[0]); Files.createDirectories(output);
        Files.write(output.resolve("jvm-pixels.binary"), all); Files.writeString(output.resolve("jvm-reference.txt"), hashes);
        Files.writeString(output.resolve("jvm-runtime.txt"), System.getProperty("java.runtime.version"));
        System.out.println("Native ShapeSpanIterator and pure Java match: " + GraySpanProbe.CASES + " cases / " + all.length + " pixels");
    }
}
