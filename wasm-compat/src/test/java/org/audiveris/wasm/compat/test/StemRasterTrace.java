package org.audiveris.wasm.compat.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import ij.process.ByteProcessor;
import java.awt.*;
import java.awt.geom.PathIterator;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import org.audiveris.omr.run.RunTable;
import org.audiveris.omr.sheet.*;

/** JVM-only observation of actual StemScaler Java2D calls, with shared pixels and full delegation. */
public final class StemRasterTrace {
    private static final java.util.List<Map<String, Object>> pages = new ArrayList<>();
    public static void main(String[] args) throws Exception {
        StemProbe.beforeStem = StemRasterTrace::install;
        try { GridOracle.main(args); } finally { StemProbe.beforeStem = null; }
        Path output = Path.of(args[1]); var mapper = new ObjectMapper();
        Path original = output.resolveSibling("../stem-original-probe/jvm-reference.json");
        if (!mapper.readTree(output.toFile()).equals(mapper.readTree(original.toFile()))) {
            throw new AssertionError("Graphics recorder changed original stem results");
        }
        Path buffers = output.resolveSibling("stem-buffers"), reference = original.resolveSibling("stem-buffers");
        for (var page : pages) {
            String id = (String) page.get("id");
            if (!Arrays.equals(Files.readAllBytes(buffers.resolve(id + ".binary")), Files.readAllBytes(reference.resolve(id + ".binary")))) {
                throw new AssertionError("Graphics recorder changed stem buffer " + id);
            }
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolveSibling("raster-commands.json").toFile(), pages);
        System.out.println("Recorded original STEM_SEEDS drawing without changing results or pixels: " + pages.size() + " pages");
    }
    private static void install(Sheet sheet) {
        Picture original = sheet.getPicture();
        if (!(original instanceof PixelPicture)) { return; }
        var commands = new ArrayList<Map<String, Object>>();
        pages.add(Map.of("id", sheet.getId(), "width", sheet.getWidth(), "height", sheet.getHeight(), "commands", commands));
        try {
            var field = Sheet.class.getDeclaredField("picture"); field.setAccessible(true);
            field.set(sheet, new TracePicture(sheet, original, commands));
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static final class TracePicture extends Picture {
        private final Picture original;
        private final ByteProcessor pixels;
        TracePicture(Sheet sheet, Picture original, java.util.List<Map<String, Object>> commands) {
            super(sheet, sheet.getWidth(), sheet.getHeight()); this.original = original;
            ByteProcessor source = original.getSource(SourceKey.NO_STAFF);
            pixels = new ByteProcessor(source.getWidth(), source.getHeight(), (byte[]) source.getPixels(), null) {
                @Override public BufferedImage getBufferedImage() {
                    BufferedImage image = super.getBufferedImage();
                    return new BufferedImage(image.getColorModel(), image.getRaster(), image.isAlphaPremultiplied(), null) {
                        @Override public Graphics2D createGraphics() { return new Recorder(super.createGraphics(), commands); }
                    };
                }
            };
        }
        @Override public ByteProcessor getSource(SourceKey key) { return key == SourceKey.NO_STAFF ? pixels : original.getSource(key); }
        @Override public RunTable getVerticalTable(TableKey key) { return original.getVerticalTable(key); }
    }
    private static final class Recorder extends GraphicsDelegate {
        private final java.util.List<Map<String, Object>> commands;
        Recorder(Graphics2D delegate, java.util.List<Map<String, Object>> commands) { super(delegate); this.commands = commands; }
        private void record(java.awt.Shape shape, boolean stroke) {
            if (!Color.WHITE.equals(delegate.getColor()) || !delegate.getTransform().isIdentity()) {
                throw new AssertionError("Unexpected stem drawing state");
            }
            var command = new LinkedHashMap<String, Object>(); command.put("path", path(shape));
            if (stroke) {
                BasicStroke style = (BasicStroke) delegate.getStroke();
                if (style.getDashArray() != null) { throw new AssertionError("Unexpected dashed stem drawing"); }
                command.put("stroke", Map.of("width", style.getLineWidth(), "cap", style.getEndCap(),
                    "join", style.getLineJoin(), "miter", style.getMiterLimit()));
                command.put("outlinedPath", path(style.createStrokedShape(shape)));
            }
            commands.add(command);
        }
        @Override public void fill(java.awt.Shape shape) { record(shape, false); delegate.fill(shape); }
        @Override public void draw(java.awt.Shape shape) { record(shape, true); delegate.draw(shape); }
        @Override public void fillRect(int x, int y, int width, int height) {
            if (width > 0 && height > 0) { record(new Rectangle(x, y, width, height), false); }
            delegate.fillRect(x, y, width, height);
        }
    }
    private static Map<String, Object> path(java.awt.Shape shape) {
        PathIterator iterator = shape.getPathIterator(null); var segments = new ArrayList<double[]>();
        double[] coordinates = new double[6]; int winding = iterator.getWindingRule();
        while (!iterator.isDone()) {
            int type = iterator.currentSegment(coordinates), size = switch (type) { case 0, 1 -> 2; case 2 -> 4; case 3 -> 6; default -> 0; };
            double[] segment = new double[size + 1]; segment[0] = type;
            System.arraycopy(coordinates, 0, segment, 1, size); segments.add(segment); iterator.next();
        }
        return Map.of("winding", winding, "segments", segments);
    }
}
