package org.audiveris.wasm.compat.test;

import com.fasterxml.jackson.databind.*;
import java.awt.geom.Path2D;
import java.nio.file.*;
import java.util.*;

/** Research only: isolate pixel-center filling from native stroke-outline construction. */
public final class StemCenterRasterOracle {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]), inputs = Path.of(args[1]), output = root.resolve("center-pixels");
        Files.createDirectories(output); var mapper = new ObjectMapper(); var results = new ArrayList<Map<String, Object>>();
        for (var page : mapper.readTree(root.resolve("raster-commands.json").toFile())) {
            String id = page.get("id").asText(); int width = page.get("width").asInt(), height = page.get("height").asInt();
            byte[] pixels = Files.readAllBytes(inputs.resolve(id + ".binary"));
            byte[] expected = Files.readAllBytes(root.resolve("stem-buffers").resolve(id + ".binary"));
            int curves = 0, commands = 0;
            for (var command : page.get("commands")) {
                JsonNode description = command.has("stroke") ? command.get("outlinedPath") : command.get("path");
                Path2D shape = new Path2D.Double(description.get("winding").asInt());
                for (var segment : description.get("segments")) {
                    switch (segment.get(0).asInt()) {
                        case 0 -> shape.moveTo(segment.get(1).asDouble(), segment.get(2).asDouble());
                        case 1 -> shape.lineTo(segment.get(1).asDouble(), segment.get(2).asDouble());
                        case 2 -> { shape.quadTo(segment.get(1).asDouble(), segment.get(2).asDouble(), segment.get(3).asDouble(), segment.get(4).asDouble()); curves++; }
                        case 3 -> { shape.curveTo(segment.get(1).asDouble(), segment.get(2).asDouble(), segment.get(3).asDouble(), segment.get(4).asDouble(), segment.get(5).asDouble(), segment.get(6).asDouble()); curves++; }
                        case 4 -> shape.closePath();
                        default -> throw new AssertionError("Unknown path command");
                    }
                }
                var box = shape.getBounds();
                int left = Math.max(0, box.x), right = (int) Math.min(width, (long) box.x + box.width);
                int top = Math.max(0, box.y), bottom = (int) Math.min(height, (long) box.y + box.height);
                for (int y = top; y < bottom; y++) { for (int x = left; x < right; x++) {
                    int offset = y * width + x;
                    if (pixels[offset] == 0 && shape.contains(x + 0.5, y + 0.5)) { pixels[offset] = (byte) 255; }
                } }
                commands++;
            }
            int different = 0, missed = 0, excess = 0;
            for (int i = 0; i < pixels.length; i++) { if (pixels[i] != expected[i]) {
                different++; if (pixels[i] == 0) { missed++; } else { excess++; }
            } }
            Files.write(output.resolve(id + ".binary"), pixels);
            results.add(Map.of("id", id, "pixels", pixels.length, "commands", commands, "curvedSegments", curves,
                "different", different, "missedErasure", missed, "excessErasure", excess));
        }
        var report = Map.of("results", results, "appliesPreviouslyDetachedDrawing", true,
            "limitation", "Applied drawing versus unchanged recognition pixels; not a fill-precision measurement. No engine backend changed");
        mapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("center-comparison.json").toFile(), report);
        System.out.println(mapper.writeValueAsString(report));
    }
}
