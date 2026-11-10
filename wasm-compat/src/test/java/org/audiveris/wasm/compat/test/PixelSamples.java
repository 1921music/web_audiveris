package org.audiveris.wasm.compat.test;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import ij.process.*;
import org.audiveris.omr.sheet.Picture;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Prepare fixed raw grayscale pages and JVM binary references, never included in the Wasm entry. */
final class PixelSamples {
    static void prepare(Path input, Path output) throws Exception {
        Files.createDirectories(output); List<Map<String, Object>> pages = new ArrayList<>();
        List<Path> sources;
        try (var stream = Files.list(input)) { sources = stream.filter(Files::isRegularFile).sorted().toList(); }
        for (Path source : sources) {
            String lower = source.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".png") && !lower.endsWith(".tif") && !lower.endsWith(".tiff")) { continue; }
            var decoded = ImageIO.read(source.toFile());
            if (decoded == null) { throw new IllegalArgumentException("Cannot decode sample " + source); }
            var image = Picture.adjustImageFormat(decoded);
            ByteProcessor gray = image.getType() == java.awt.image.BufferedImage.TYPE_BYTE_GRAY
                    ? new ByteProcessor(image) : new ColorProcessor(image).convertToByteProcessor();
            String id = String.format(Locale.ROOT, "%02d", pages.size());
            byte[] pixels = (byte[]) gray.getPixels();
            Files.write(output.resolve(id + ".gray"), pixels);
            byte[] binary = (byte[]) new org.audiveris.omr.image.VerticalFilter(gray, 0.7, 0.9).filteredImage().getPixels();
            Files.write(output.resolve(id + ".binary"), binary);
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("id", id); page.put("file", source.getFileName().toString()); page.put("pageIndex", 0);
            page.put("width", gray.getWidth()); page.put("height", gray.getHeight()); page.put("pixels", pixels.length);
            page.put("sourceSha256", hash(Files.readAllBytes(source))); page.put("graySha256", hash(pixels)); page.put("binarySha256", hash(binary));
            pages.add(page); System.out.println("Prepared sample " + id + " " + gray.getWidth() + "x" + gray.getHeight());
        }
        if (pages.isEmpty()) { throw new IllegalArgumentException("No sample pages in " + input); }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("manifest.json").toFile(),
                Map.of("grayscale", "JVM ImageIO + original Picture.adjustImageFormat/buildGraySource convention", "meanCoefficient", 0.7,
                       "deviationCoefficient", 0.9, "pages", pages));
    }
    private static String hash(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
}
