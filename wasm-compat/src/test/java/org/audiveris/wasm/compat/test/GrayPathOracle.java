package org.audiveris.wasm.compat.test;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.*;

public final class GrayPathOracle {
    public static void main(String[] args) throws Exception {
        StringBuilder hashes = new StringBuilder();
        byte[] all = new byte[GrayPathProbe.CASES * GrayPathProbe.WIDTH * GrayPathProbe.HEIGHT];
        for (int test = 0; test < GrayPathProbe.CASES; test++) {
            BufferedImage image = new BufferedImage(GrayPathProbe.WIDTH, GrayPathProbe.HEIGHT, BufferedImage.TYPE_BYTE_GRAY);
            byte[] expected = ((DataBufferByte)image.getRaster().getDataBuffer()).getData();
            System.arraycopy(GrayPathProbe.input(test), 0, expected, 0, expected.length);
            var g = image.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
                g.setColor(Color.WHITE); g.fill(new java.awt.geom.Path2D.Float(GrayPathProbe.shape(test)));
            } finally { g.dispose(); }
            byte[] actual = GrayPathProbe.portable(test);
            if (!Arrays.equals(actual, expected)) {
                int count = 0; for (int i = 0; i < actual.length; i++) { if (actual[i] != expected[i]) { count++; } }
                throw new AssertionError("Path fill " + test + ": " + count + " pixels differ");
            }
            hashes.append(Arrays.hashCode(expected)).append(',');
            System.arraycopy(expected, 0, all, test * expected.length, expected.length);
        }
        var output = java.nio.file.Path.of(args[0]); java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output, hashes);
        java.nio.file.Files.write(output.resolveSibling("jvm-pixels.binary"), all);
        var sha = java.security.MessageDigest.getInstance("SHA-256");
        var hex = java.util.HexFormat.of();
        String classSha;
        try (var stream = ClassLoader.getSystemResourceAsStream("sun/java2d/loops/ProcessPath.class")) {
            classSha = hex.formatHex(sha.digest(java.util.Objects.requireNonNull(stream).readAllBytes()));
        }
        String sourceSha;
        try (var zip = new java.util.zip.ZipFile(java.nio.file.Path.of(System.getProperty("java.home"), "lib/src.zip").toFile());
             var stream = zip.getInputStream(zip.getEntry("java.desktop/sun/java2d/loops/ProcessPath.java"))) {
            sourceSha = hex.formatHex(sha.digest(stream.readAllBytes()));
        }
        java.nio.file.Files.writeString(output.resolveSibling("jdk-reference.json"),
            "{\"javaRuntime\":\"" + System.getProperty("java.runtime.version")
                + "\",\"processPathClassSha256\":\"" + classSha
                + "\",\"processPathSourceSha256\":\"" + sourceSha + "\"}\n");
        System.out.println("Original Java2D vs ProcessPath: " + GrayPathProbe.CASES + " paths / "
            + (long)GrayPathProbe.CASES * GrayPathProbe.WIDTH * GrayPathProbe.HEIGHT + " pixels");
    }
}
