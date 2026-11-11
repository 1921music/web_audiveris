// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.*;
import java.nio.file.*;
import java.util.Arrays;
import ij.process.ByteProcessor;
import org.audiveris.wasm.compat.platform.PackedPageRaster;

/** Independent native Graphics2D reference for the thin Path2D drawing operation. */
public final class GrayThinOracle {
    public static void main(String[] args) throws Exception {
        int width = GrayPathProbe.WIDTH, height = GrayPathProbe.HEIGHT, size = width * height;
        var image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY); var graphics = image.createGraphics();
        byte[] expected = ((DataBufferByte) image.getRaster().getDataBuffer()).getData(), all = new byte[GrayThinProbe.CASES * size];
        var hashes = new StringBuilder();
        int failures = 0, packedCases = 0; var families = new java.util.TreeMap<String,Integer>();
        try {
            graphics.setColor(Color.WHITE); graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            for (int test = 0; test < GrayThinProbe.CASES; test++) {
                BasicStroke style = new BasicStroke(new float[]{0f, .25f, .5f, 1f}[(test / 2) % 4], (test / 2) % 3, (test / 6) % 3);
                graphics.setStroke(style);
                graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, (test & 1) != 0 ? RenderingHints.VALUE_STROKE_PURE : RenderingHints.VALUE_STROKE_DEFAULT);
                System.arraycopy(GrayPathProbe.input(test / 2), 0, expected, 0, size);
                graphics.draw(new Path2D.Float(GrayThinProbe.shape(test / 2)));
                byte[] actual = GrayThinProbe.portable(test);
                if (!Arrays.equals(expected, actual)) {
                    Path output = Path.of(args[0]); Files.createDirectories(output);
                    if (failures == 0) {
                        Files.write(output.resolve("failed-native.binary"), expected); Files.write(output.resolve("failed-portable.binary"), actual);
                    }
                    failures++;
                    String family = (test / 2 < 512 ? "shape" + (test / 2 % 8) : test / 2 < 512 + 65 * 65 ? "line" : "large-line") + "/" + (test % 2);
                    families.merge(family,1,Integer::sum);
                }
                hashes.append(Arrays.hashCode(expected)).append(','); System.arraycopy(expected, 0, all, test * size, size);
                if ((test & 1) == 0) {
                    var buffer = new ByteProcessor(width,height,GrayPathProbe.input(test / 2),null);
                    var backend = new PackedPageRaster(buffer); backend.initialize(); backend.setStroke(style); backend.draw(GrayThinProbe.shape(test / 2));
                    if (!Arrays.equals(expected,(byte[])buffer.getPixels())) { throw new AssertionError("Packed backend thin path: " + test); }
                    packedCases++;
                }
            }
        } finally { graphics.dispose(); }
        if (failures != 0) { throw new AssertionError("Native thin path mismatches: " + failures + "; " + families); }
        GrayThinProbe.guards(); Path output = Path.of(args[0]); Files.createDirectories(output);
        Files.write(output.resolve("jvm-pixels.binary"), all); Files.writeString(output.resolve("jvm-reference.txt"), hashes);
        Files.writeString(output.resolve("jvm-runtime.txt"), System.getProperty("java.runtime.version"));
        var backend = new PackedPageRaster(new ByteProcessor(1,1)); int backendGuards = 0;
        try { backend.setStroke(shape -> shape); } catch (UnsupportedOperationException expectedError) { backendGuards++; }
        backend.setStroke(new BasicStroke(.5f,BasicStroke.CAP_SQUARE,BasicStroke.JOIN_MITER,10,new float[]{1,1},0));
        try { backend.draw(new java.awt.geom.Line2D.Double(0,0,1,1)); } catch (UnsupportedOperationException expectedError) { backendGuards++; }
        if (backendGuards != 2) { throw new AssertionError("Packed backend unsupported-style guards"); }
        Files.writeString(output.resolve("jvm-verification.json"),"{\"cases\":"+GrayThinProbe.CASES+",\"packedBackendDefaultCases\":"+packedCases+",\"packedBackendGuardCases\":"+backendGuards+",\"packedBackendPixelsExact\":true}\n");
        System.out.println("Native Graphics2D and pure Java thin paths match: " + GrayThinProbe.CASES + " cases / " + all.length + " pixels");
    }
}
