package org.audiveris.wasm.compat.test;

public final class StrokePathOracle {
    public static void main(String[] args) throws Exception {
        StringBuilder out = new StringBuilder();
        int pagePixels = GrayPathProbe.WIDTH * GrayPathProbe.HEIGHT;
        byte[] pixels = new byte[StrokePathProbe.CASES * 2 * pagePixels];
        for (int test = 0; test < StrokePathProbe.CASES; test++) {
            String original = StrokePathProbe.geometry(StrokePathProbe.stroke(test).createStrokedShape(StrokePathProbe.shape(test)));
            if (!original.equals(StrokePathProbe.geometry(test, false))) { throw new AssertionError("Native stroke outline " + test); }
            out.append(original).append('\n').append(StrokePathProbe.geometry(test, true)).append('\n');
            for (boolean normalize : new boolean[]{false, true}) {
                java.awt.Shape outline = normalize
                    ? org.audiveris.wasm.compat.platform.StrokePaths.normalized(StrokePathProbe.shape(test), StrokePathProbe.stroke(test))
                    : StrokePathProbe.stroke(test).createStrokedShape(StrokePathProbe.shape(test));
                var image = new java.awt.image.BufferedImage(GrayPathProbe.WIDTH, GrayPathProbe.HEIGHT, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
                byte[] expected = ((java.awt.image.DataBufferByte)image.getRaster().getDataBuffer()).getData();
                System.arraycopy(GrayPathProbe.input(test), 0, expected, 0, pagePixels);
                var g = image.createGraphics();
                try { g.setColor(java.awt.Color.WHITE); g.fill(new java.awt.geom.Path2D.Float(outline)); }
                finally { g.dispose(); }
                if (!java.util.Arrays.equals(expected, StrokePathProbe.rasterJava(test, normalize))) { throw new AssertionError("Native outline raster " + test + "/" + normalize); }
                System.arraycopy(expected, 0, pixels, (test * 2 + (normalize ? 1 : 0)) * pagePixels, pagePixels);
            }
        }
        var output = java.nio.file.Path.of(args[0]); java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output, out);
        java.nio.file.Files.write(output.resolveSibling("jvm-pixels.binary"), pixels);
        java.nio.file.Files.writeString(output.resolveSibling("cbrt-reference.txt"), StrokePathProbe.cbrts());
        StringBuilder strict = new StringBuilder();
        for (double value : StrokePathProbe.cbrtInputs()) { strict.append(Long.toHexString(Double.doubleToLongBits(StrictMath.cbrt(value)))).append(','); }
        java.nio.file.Files.writeString(output.resolveSibling("strict-cbrt-reference.txt"), strict);
        java.nio.file.Files.writeString(output.resolveSibling("acos-reference.txt"), StrokePathProbe.acosValues());
        StringBuilder ulps = new StringBuilder();
        for (double value : StrokePathProbe.cbrtInputs()) { ulps.append(Long.toHexString(Double.doubleToLongBits(Math.ulp(value)))).append(','); }
        if (!ulps.toString().equals(StrokePathProbe.ulps())) { throw new AssertionError("Native ULP adapter contract"); }
        java.nio.file.Files.writeString(output.resolveSibling("ulp-reference.txt"), ulps);
        StringBuilder metadata = new StringBuilder("{\"javaRuntime\":\"").append(System.getProperty("java.runtime.version")).append("\",\"sources\":{");
        var sha = java.security.MessageDigest.getInstance("SHA-256"); var hex = java.util.HexFormat.of(); boolean first = true;
        try (var zip = new java.util.zip.ZipFile(java.nio.file.Path.of(System.getProperty("java.home"), "lib/src.zip").toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement(); String name = entry.getName();
                if (!name.endsWith(".java") || !(name.startsWith("java.desktop/sun/java2d/marlin/")
                        || name.startsWith("java.desktop/sun/java2d/ReentrantContext")
                        || name.equals("java.desktop/sun/awt/geom/PathConsumer2D.java")
                        || name.equals("java.base/java/lang/FdLibm.java"))) { continue; }
                String sourceSha; try (var input = zip.getInputStream(entry)) { sourceSha = hex.formatHex(sha.digest(input.readAllBytes())); }
                String className = name.substring(name.indexOf('/') + 1, name.length() - 5) + ".class";
                String classSha; try (var input = ClassLoader.getSystemResourceAsStream(className)) {
                    classSha = hex.formatHex(sha.digest(java.util.Objects.requireNonNull(input).readAllBytes()));
                }
                if (!first) { metadata.append(','); } first = false;
                metadata.append('"').append(name).append("\":{\"sourceSha256\":\"").append(sourceSha)
                    .append("\",\"classSha256\":\"").append(classSha).append("\"}");
            }
        }
        java.nio.file.Files.writeString(output.resolveSibling("jdk-reference.json"), metadata.append("}}\n"));
        System.out.println("Original BasicStroke outlines: 256; native normalized outlines: 256");
    }
}
