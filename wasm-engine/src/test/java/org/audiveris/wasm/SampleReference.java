package org.audiveris.wasm;

import ij.process.ByteProcessor;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.audiveris.omr.image.VerticalFilter;

/** Decode fixtures outside the engine and compare the portable Java implementation to upstream. */
public final class SampleReference {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) { throw new IllegalArgumentException("Sample directory and output directory required"); }
        Path samples = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        try (var stale = Files.list(output)) {
            for (Path generated : stale.filter(p -> p.getFileName().toString()
                    .matches("sample-\\d+\\.(gray|binary|input\\.png|scale)")).toList()) {
                Files.delete(generated);
            }
        }
        StringBuilder manifest = new StringBuilder("[\n");
        int index = 0;
        try (var paths = Files.list(samples)) {
            for (Path path : paths.sorted().toList()) {
                if (!Files.isRegularFile(path) || !path.toString().toLowerCase(Locale.ROOT).matches(".*\\.(png|tiff?|jpe?g)$")) { continue; }
                BufferedImage decoded = ImageIO.read(path.toFile());
                if (decoded == null) { throw new IllegalArgumentException("Cannot decode " + path); }
                BufferedImage gray = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
                Graphics2D graphics = gray.createGraphics();
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, gray.getWidth(), gray.getHeight());
                graphics.drawImage(decoded, 0, 0, null);
                graphics.dispose();
                int width = gray.getWidth(), height = gray.getHeight();
                byte[] pixels = (byte[]) gray.getRaster().getDataElements(0, 0, width, height, null);
                long before = System.nanoTime();
                byte[] reference = (byte[]) new VerticalFilter(new ByteProcessor(width, height, pixels, null), .7, .9)
                        .filteredImage().getPixels();
                double referenceMs = (System.nanoTime() - before) / 1e6;
                before = System.nanoTime();
                AdaptiveBinarizer filter = new AdaptiveBinarizer(new GrayImage(width, height, width, pixels), 18, .7, .9);
                while (!filter.advance(32)) { }
                double portableMs = (System.nanoTime() - before) / 1e6;
                if (!Arrays.equals(reference, filter.result())) { throw new AssertionError("Binary mismatch: " + path); }
                ScaleEstimator scale = new ScaleEstimator(width, height, reference);
                int[] scaleResult = null;
                String scaleError = null;
                try {
                    while (!scale.advance(32)) { }
                    scaleResult = scale.result();
                } catch (IllegalStateException failure) { scaleError = failure.getMessage(); }
                String id = String.format(Locale.ROOT, "sample-%03d", index++);
                ImageIO.write(gray, "png", output.resolve(id + ".input.png").toFile());
                Files.write(output.resolve(id + ".gray"), pixels);
                Files.write(output.resolve(id + ".binary"), reference);
                if (scaleResult != null) { Files.writeString(output.resolve(id + ".scale"), Arrays.toString(scaleResult)); }
                if (index > 1) { manifest.append(",\n"); }
                manifest.append("{\"id\":\"").append(id).append("\",\"name\":").append(quote(path.getFileName().toString()))
                        .append(",\"width\":").append(width).append(",\"height\":").append(height)
                        .append(",\"upstreamJvmMs\":").append(referenceMs).append(",\"portableJvmMs\":").append(portableMs)
                        .append(",\"portableScale\":").append(Arrays.toString(scaleResult))
                        .append(",\"scaleError\":").append(scaleError == null ? "null" : quote(scaleError)).append('}');
                System.out.printf(Locale.ROOT, "%s %dx%d: exact match; upstream %.1f ms, portable %.1f ms%n", path.getFileName(), width, height, referenceMs, portableMs);
            }
        }
        if (index == 0) { throw new IllegalArgumentException("No sample images found"); }
        Files.writeString(output.resolve("manifest.json"), manifest.append("\n]\n").toString());
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
